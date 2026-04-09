# postgres-doc-comments: sqldoc — SQL DocString Parser
A postgresql doc comment specification and accompanying example scripts. 

## Project Specification v1.1

---

## 1. Overview

sqldoc is a Groovy-based documentation generator for PostgreSQL codebases. It parses structured comment blocks embedded in `.sql` files, infers structural metadata from DDL statements, merges the two layers, and produces Markdown documentation suitable for repository browsing, CI/CD pipelines, and developer onboarding.

The tool operates entirely offline against static `.sql` files. It does not require a running database connection.

---

## 2. Design Philosophy

Doc blocks use a tag-based format inspired by JavaDoc. Every tag is optional by default. The parser follows three governing principles:

- **Doc declarations always override inferred values.** If a doc block specifies `:param`, `:fields`, `:returns`, or `:constraints`, those values are authoritative regardless of what the DDL says.
- **DDL fills gaps, not the other way around.** When a doc block omits a tag that can be inferred from the CREATE statement, the parser auto-populates it. Auto-populated sections are marked in output.
- **Validation warns but does not fabricate.** In strict mode, the parser warns when a doc block declares something that cannot be verified against the DDL (e.g., a `:param` name that doesn't appear in the function signature). It never silently drops or rewrites doc content.

---

## 3. Doc Block Syntax

### 3.1 Delimiters

Doc blocks use symmetric delimiters:

```sql
/*@doc
:tag  value
@doc*/
```

PostgreSQL supports nested block comments, so `/*@doc ... @doc*/` will not conflict with standard `/* ... */` comments in surrounding code.

### 3.2 Scope Inference

The parser determines whether a block is file-level or object-level by position:

- If the block appears before any SQL statement in the file, it is **file-level**.
- If the block immediately precedes a `CREATE` statement (within 10 characters of whitespace), it is **object-level**.
- If neither condition is met, it is treated as an **orphan object-level block**.

No explicit delimiter variant (e.g., `/*@doc-file`) is required.

### 3.3 Tag Format

Tags are prefixed with `:` to avoid collision with PostgreSQL's `$` and `@` sigils. Values follow on the same line. Multi-line values are expressed via indented continuation lines (2+ spaces):

```sql
:desc       Returns users with activity in the last N days.
            Excludes soft-deleted accounts.
```

The parser collects continuation lines until it encounters the next `:tag` or the closing `@doc*/` delimiter.

### 3.4 Multi-Value Tags

Certain tags may appear multiple times within a single block. Each occurrence is accumulated into a list:

- `:param` — one per function/procedure parameter
- `:throws` — one per error condition
- `:see` — one per cross-reference
- `:todo` — one per work item
- `:example` — one per usage example

All other tags use last-write-wins if duplicated.

---

## 4. Supported Tags

### 4.1 Identity & Metadata

| Tag | Scope | Description |
|-----|-------|-------------|
| `:name` | file, object | Display name. Auto-inferred from filename (file-level) or `CREATE` statement (object-level) if omitted. |
| `:type` | object | Object type (`function`, `procedure`, `view`, `table`, `trigger`, `index`). Auto-inferred from `CREATE` keyword. |
| `:schema` | file, object | PostgreSQL schema. Cascades from file-level to object-level blocks that omit it. Auto-inferred from schema-qualified names in `CREATE` (e.g., `public.users`). |
| `:version` | file, object | Semantic version string for the object. |
| `:author` | file, object | Original author. |
| `:since` | file, object | Date of initial creation. |

### 4.2 Documentation

| Tag | Scope | Multi-line | Description |
|-----|-------|------------|-------------|
| `:desc` | file, object | Yes | Free-form description. |
| `:param` | object | No (multi-value) | Function/procedure parameter. Format: `name TYPE - description [default: value]` |
| `:returns` | object | Yes | Return type (single-line for scalars, multi-line column table for `TABLE` returns and views). |
| `:fields` | object | Yes | Table column definitions. Format per line: `name TYPE - description` |
| `:constraints` | object | Yes | Table constraints. Format per line: `name DEFINITION - description` |
| `:triggers` | object | Yes | Triggers associated with a table. Format per line: `name EVENT - description` |
| `:throws` | object | No (multi-value) | Error conditions. Format: `errcode - description` |
| `:example` | object | Yes (multi-value) | SQL usage example. Rendered in fenced code blocks. |

### 4.3 Operational

| Tag | Scope | Multi-line | Description |
|-----|-------|------------|-------------|
| `:security` | object | Yes | GRANT statements and access control notes. |
| `:performance` | object | Yes | Index dependencies, expected row counts, known slow paths. |
| `:depends` | object | No | Comma-separated list of hard dependencies (tables, functions, views). FK dependencies from DDL are auto-merged. |
| `:see` | object | No (multi-value) | Soft cross-references to related objects. Rendered as "See Also" and as dashed edges in the dependency graph. |

### 4.4 Lifecycle

| Tag | Scope | Multi-line | Description |
|-----|-------|------------|-------------|
| `:deprecated` | object | Yes | Deprecation notice. Should include a replacement pointer (enforced in strict mode). |
| `:todo` | object | No (multi-value) | Tracked work items. |
| `:changelog` | file, object | Yes | Change history. Format per line: `date \| author \| description`. Rendered as a Markdown table. |

---

## 5. DDL Inference

When a doc block precedes a `CREATE TABLE` or `CREATE FUNCTION/PROCEDURE` statement, the parser attempts to extract structural metadata directly from the DDL. This is a regex-based parser targeting common PostgreSQL patterns.

### 5.1 Function/Procedure Inference

Extracted from the function signature and `RETURNS` clause:

| Inferred Tag | Source | What is captured |
|-------------|--------|------------------|
| `:param` | Parameter list inside `CREATE FUNCTION name(...)` | Name, type, default value. Handles `IN`/`OUT`/`INOUT`/`VARIADIC` mode keywords. |
| `:returns` | `RETURNS` clause before `AS`/`LANGUAGE`/`$$` | Scalar type or `TABLE(...)` definition. |

### 5.2 Table Inference

Extracted from the table body inside `CREATE TABLE name (...)`:

| Inferred Tag | Source | What is captured |
|-------------|--------|------------------|
| `:fields` | Column definitions | Name, type, nullability (`NOT NULL`), default values. |
| `:constraints` | Table-level and inline constraints | Named and unnamed `PRIMARY KEY`, `UNIQUE`, `CHECK`, `FOREIGN KEY`, `EXCLUDE`. |
| `:depends` | `REFERENCES` clauses in FK constraints | Referenced table names, merged with any existing `:depends` values. |

### 5.3 Merge Rules

1. If a doc block **does not declare** a tag that can be inferred, the inferred value is used. Auto-populated sections are annotated with *(auto-generated from DDL)* in rendered output.
2. If a doc block **declares** a tag, the doc value is used verbatim. The inferred value is discarded.
3. Exception: `:depends` values derived from FK constraints are **merged** with doc-declared `:depends`, not overridden. Duplicates are removed.
4. DDL-inferred tags satisfy `--strict` mode requirements (a function with no `:param` in doc but params in DDL will not trigger a missing-tag lint error).

### 5.4 Known Limitations

The regex-based DDL parser handles approximately 90% of real-world PostgreSQL DDL. The following patterns degrade gracefully (skipped, not misrepresented):

- `CREATE TABLE ... AS SELECT` (no column list to parse)
- Multi-line `CHECK` expressions with nested function calls and parens
- Composite/array types with parens (e.g., `INTEGER[]`, `point(x, y)`)
- `GENERATED ALWAYS AS (expr) STORED` with complex expressions
- `CREATE TABLE ... PARTITION BY` clauses
- Domain types and custom type constructors

When the parser cannot extract DDL, it sets `parsed = false` and does not populate any inferred tags. No warnings are emitted for unparseable DDL unless `--strict` is active and a required tag is missing.

---

## 6. Validation

Validation runs during `--strict` mode and produces two categories of warnings:

### 6.1 Lint Warnings `[LINT]`

Missing required tags per object type:

| Object Type | Required Tags |
|-------------|---------------|
| `function` | `:name`, `:desc`, `:param`, `:returns` |
| `procedure` | `:name`, `:desc`, `:param` |
| `view` | `:name`, `:desc`, `:returns` |
| `table` | `:name`, `:desc`, `:fields` |
| `trigger` | `:name`, `:desc` |
| `index` | `:name`, `:desc` |
| file-level | `:desc` |

Additional lint rules:

- `:deprecated` tags that do not contain a replacement keyword (`use`, `replace`, `see`, `migrate`) trigger a warning.
- `:name` is satisfied by DDL inference (does not require explicit declaration).
- DDL-inferred tags satisfy their respective requirements.

### 6.2 DDL Warnings `[DDL]`

Cross-validation between doc declarations and parsed DDL:

| Condition | Warning |
|-----------|---------|
| `:param` declares a name not in the function signature | `:param 'x' not found in function signature` |
| DDL parameter exists but is absent from `:param` | `parameter 'x' exists in DDL but missing from :param` |
| `:fields` declares a column not in the table DDL | `:fields declares 'x' which does not exist in table DDL` |
| DDL column exists but is absent from `:fields` | `column 'x' exists in DDL but missing from :fields` |
| `:constraints` declares a name not in DDL | `:constraints declares 'x' which does not exist in table DDL` |

---

## 7. Schema Inheritance

The `:schema` tag cascades from file-level blocks to object-level blocks that do not declare their own `:schema`.

Additionally, when the parser infers an object name from a schema-qualified `CREATE` statement (e.g., `CREATE TABLE public.users`), it splits the qualified name into schema (`public`) and name (`users`) automatically. The schema portion is stored as the inferred schema and participates in inheritance. It will not override an explicitly declared `:schema` tag.

---

## 8. Output Structure

### 8.1 Directory Mirroring

When the input path is a directory, the output directory mirrors the input's subdirectory structure. A SQL file at `src/sql/core/tables/users.sql` produces documentation at `docs/core/tables/users.md`.

This preserves the organizational intent of the source repository and allows developers to navigate documentation using the same mental model they use for source files.

### 8.2 Per-Directory README Generation

Every output directory that contains one or more generated `.md` files (or subdirectories with generated files) receives an auto-generated `README.md` that serves as a table of contents. This includes the output root and every nested subdirectory.

Each README contains:

- **Subdirectories section**: links to child directory READMEs with descriptions pulled from file-level `:desc` tags when available.
- **Summary table**: object counts by type for all SQL files in that directory.
- **SQL Files table**: links to each generated doc file with the file-level `:desc` as the description column.
- **Per-file object listings**: for each SQL file, a table of all documented objects showing name, type, version, and first line of description.

Example output tree for a nested input:

```
docs/
├── README.md                          # root TOC
├── index.md                           # global index (--index)
├── dependencies.md                    # global dep graph (--dep-graph)
├── undocumented.md                    # coverage report (--undocumented)
├── auth/
│   ├── README.md                      # TOC for auth/
│   └── sessions.md
├── core/
│   ├── README.md                      # TOC for core/ (links to functions/, tables/)
│   ├── functions/
│   │   ├── README.md                  # TOC for core/functions/
│   │   └── user_mgmt.md
│   └── tables/
│       ├── README.md                  # TOC for core/tables/
│       ├── users.md
│       └── profiles.md
└── reporting/
    ├── README.md                      # TOC for reporting/
    └── views/
        ├── README.md                  # TOC for reporting/views/
        └── dashboards.md
```

README generation is automatic and does not require a CLI flag. It is suppressed in `--single-file` mode.

---

## 9. Output Formats

### 9.1 Per-File Markdown (default)

Each `.sql` file produces a corresponding `.md` file in the output directory, placed at a path that mirrors the input directory structure. File-level blocks render as `# Heading`, object-level blocks render as `## Heading` with a type badge (e.g., `` `function` ``, `` `table` ``).

### 9.2 Single-File Markdown (`--single-file`)

All documentation is combined into a single `sql-docs.md` file in the output root. Each source file's content is preceded by an HTML comment indicating the source path. Directory mirroring and README generation are disabled in this mode.

### 9.3 Index Page (`--index`)

Generates `index.md` in the output root containing:

- **Summary table**: object counts by type across the entire codebase.
- **Deprecated objects**: callout block listing all deprecated objects with their deprecation notices.
- **Per-schema sections**: tables grouped by schema, then by type, showing name, first line of description, and version.
- **Source files**: list of file-level blocks with descriptions.

The index page provides a global view across all schemas and directories. Per-directory READMEs provide the local navigational view.

### 9.4 Dependency Graph (`--dep-graph`)

Generates two files in the output root:

- `dependencies.mermaid` — raw Mermaid graph definition.
- `dependencies.md` — Markdown wrapper with fenced Mermaid block and legend.

Graph conventions:

| Element | Representation |
|---------|----------------|
| Function / Procedure | Rectangle `[ ]` |
| View | Stadium `([ ])` |
| Table | Cylinder `[( )]` |
| Trigger | Diamond `{ }` |
| `:depends` edge | Solid arrow `-->` |
| `:see` edge | Dashed arrow `-.->` |
| Deprecated object | Dashed border (`stroke-dasharray: 5 5`) |
| External dependency (not documented) | Gray fill (`fill:#f5f5f5,stroke:#999`) |

External dependencies are objects that appear in `:depends` or `:see` tags but are not defined in any doc block within the scanned files. They are rendered with gray fill to visually distinguish them from documented objects.

### 9.5 Undocumented Object Report (`--undocumented`)

Generates `undocumented.md` in the output root containing:

- **Coverage summary**: total objects, documented count, undocumented count, coverage percentage.
- **Per-file tables**: every `CREATE` statement in files that contain at least one undocumented object, with status column (`Documented` or `**MISSING**`).

The parser detects `CREATE` statements by scanning for the `CREATE [OR REPLACE] [TEMPORARY] TYPE [IF NOT EXISTS] name` pattern. It matches documented objects by comparing inferred names from doc blocks against names from the full-file scan.

A warning is printed to stderr when undocumented objects exist, but this does not affect the exit code. Use `--strict` to fail the pipeline on missing documentation.

---

## 10. CLI Interface

```
Usage: groovy sqldoc-parser.groovy [OPTIONS] <path>

Arguments:
  <path>              Single .sql file or directory (recursively scanned).
                      Multiple paths can be specified.

Options:
  --strict            Enable linter mode. Enforces required tags per object
                      type and validates doc declarations against DDL.
                      Exits with code 1 if any errors are found.
  --out <dir>         Output directory for generated Markdown.
                      Default: ./docs
  --single-file       Combine all output into one sql-docs.md file.
                      Disables directory mirroring and README generation.
  --index             Generate index.md with global summary and per-schema tables.
  --dep-graph         Generate dependencies.mermaid and dependencies.md.
  --undocumented      Generate undocumented.md with coverage report.
                      Prints a warning to stderr if undocumented objects exist.
  -h, --help          Show usage information.
```

### 10.1 Exit Codes

| Code | Meaning |
|------|---------|
| 0 | Success (or success with `--undocumented` warnings on stderr) |
| 1 | `--strict` mode found lint or DDL validation errors |

### 10.2 Pipeline Integration

Recommended CI/CD stage configuration (Jenkins/Groovy):

```groovy
stage('SQL Docs') {
    steps {
        sh '''
            groovy sqldoc-parser.groovy \
                --strict \
                --index \
                --dep-graph \
                --undocumented \
                --out docs/sql \
                src/sql/
        '''
    }
    post {
        always {
            archiveArtifacts artifacts: 'docs/sql/**', allowEmptyArchive: true
        }
    }
}
```

The `--strict` flag causes the stage to fail if documentation standards are not met. The `--undocumented` flag emits warnings to stderr but does not affect the exit code.

---

## 11. File Discovery

When `<path>` is a directory, the parser recursively collects all files ending in `.sql` and processes them in alphabetical path order. Non-`.sql` files are ignored.

When `<path>` is a single file, only that file is processed. Output is placed directly in the output root with no directory mirroring.

Multiple paths can be specified: `groovy sqldoc-parser.groovy path1/ path2/file.sql`. Each directory path maintains its own relative structure in the output.

---

## 12. Complete Tag Reference

```sql
/*@doc
:name           object_name
:type           function|procedure|view|table|trigger|index
:schema         public
:desc           Multi-line description of the object.
                Continuation lines are indented.
:version        1.2.0
:author         jharrell
:since          2026-01-15
:param          days_back INT - Lookback window [default: 30]
:param          include_bots BOOLEAN - Include service accounts [default: false]
:returns        TABLE(user_id BIGINT, last_active TIMESTAMPTZ)
:fields
  user_id       BIGSERIAL    - Primary key, auto-generated
  email         TEXT         - Unique, used for auth
:constraints
  pk_users        PRIMARY KEY (user_id)
  uq_users_email  UNIQUE (email)
:triggers
  trg_audit  AFTER INSERT OR UPDATE - Writes to audit_log
:throws         invalid_parameter_value - When days_back is negative
:depends        public.users, public.activity_log
:see            public.related_function
:security
  GRANT SELECT ON public.users TO readonly_role;
  GRANT EXECUTE ON FUNCTION public.my_func TO app_role;
:performance
  Uses idx_activity_log_ts. Expect ~500K rows in production.
:example
  SELECT * FROM get_active_users(7, false);
:example
  SELECT * FROM get_active_users(days_back := 30, include_bots := true);
:deprecated     Use new_function instead. Scheduled for removal in v3.0.
:todo           Add partial index on (email) WHERE is_deleted = false
:todo           Consider partitioning after 1M rows
:changelog
  2026-04-08 | jharrell | Added include_bots param
  2026-01-15 | jharrell | Initial creation
@doc*/
```

---

## 13. Implementation Details

### 13.1 Runtime

| Property | Value |
|----------|-------|
| Language | Groovy (JVM) |
| Target JDK | 11+ |
| Dependencies | None beyond Groovy standard library |
| Invocation | `groovy sqldoc-parser.groovy` (script mode, no compilation step) |

### 13.2 Parsing Pipeline

Processing proceeds in this order for each `.sql` file:

1. Scan file content for `/*@doc ... @doc*/` blocks using regex `(?s)/\*@doc\b(.*?)@doc\*/`.
2. For each block, examine the text following the closing delimiter for a `CREATE` statement.
3. Parse tags from the block body by splitting on `^:(\w+)\s*(.*)` line patterns.
4. If a `CREATE` statement is found, parse DDL structure using balanced-paren extraction and comma splitting (respecting nested parens and quoted strings).
5. Apply schema inheritance from file-level block.
6. Merge DDL-inferred tags into doc tags (doc overrides, DDL fills gaps).
7. Run validation (strict mode only).
8. Render each block to Markdown.

After all files are processed:

9. Generate per-directory README.md files (unless `--single-file`).
10. Generate global index, dependency graph, and undocumented report (if flags are set).

### 13.3 DDL Parsing Internals

The parser uses two helper functions for robust DDL extraction:

- **`extractBalancedParens(text)`** — finds the first `(` and returns everything up to its matching `)`, respecting nested parens and quoted strings (single and double quotes).
- **`splitOnCommas(text)`** — splits a string on `,` at depth 0 only, preserving nested expressions like `CHECK (a > 0 AND b < 10)` as single elements.

Table column definitions are distinguished from table-level constraints by checking whether an element starts with a constraint keyword (`PRIMARY KEY`, `UNIQUE`, `CHECK`, `FOREIGN KEY`, `CONSTRAINT`, `EXCLUDE`). Everything else is treated as a column definition.

Inline constraints within column definitions (e.g., `user_id BIGSERIAL PRIMARY KEY`) are also detected and added to the constraints list.

### 13.4 Directory README Generation

After all per-file documentation is generated, the parser walks the output directory tree bottom-up. For each directory that contains `.md` files or subdirectories with `.md` files, it generates a `README.md` with:

- Links to subdirectory READMEs (with descriptions from file-level `:desc` tags)
- Summary counts of documented objects by type
- A table of SQL files with descriptions
- Per-file object listings with name, type, version, and description

Top-level report files (`index.md`, `dependencies.md`, `undocumented.md`, `sql-docs.md`) are excluded from README listings to avoid redundancy.

---

## 14. Example Workflows

### 14.1 Minimal Documentation

A developer adds a bare doc block with just a description. The parser infers everything else from DDL:

```sql
/*@doc
:desc  Deactivates a user account.
@doc*/
CREATE OR REPLACE FUNCTION public.deactivate_user(
    target_email TEXT,
    reason TEXT DEFAULT 'unspecified'
) RETURNS BOOLEAN AS $$ ...
```

Generated output includes auto-populated Parameters and Returns sections with an *(auto-generated from DDL)* annotation. The function's name, type, schema, param types, defaults, and return type are all inferred.

### 14.2 Full Documentation

A developer provides complete doc annotations. DDL inference is skipped for any tag that has an explicit declaration:

```sql
/*@doc
:desc       Deactivates a user account by email.
:version    2.0.0
:param      target_email TEXT - Email address of user to deactivate
:param      reason TEXT - Reason for deactivation [default: 'unspecified']
:returns    BOOLEAN
:throws     no_data_found - When no user matches target_email
:security
  GRANT EXECUTE TO admin_role;
:performance
  Uses idx_users_email. Constant time lookup.
:example
  SELECT deactivate_user('user@example.com', 'inactive 90 days');
:changelog
  2026-04-08 | jharrell | Added reason parameter
  2026-01-15 | jharrell | Initial creation
@doc*/
CREATE OR REPLACE FUNCTION public.deactivate_user( ...
```

### 14.3 Validation Catch

A developer documents a parameter that was renamed in DDL but not updated in the doc block:

```sql
/*@doc
:desc  Updates user email.
:param old_email TEXT - Previous email address
:param new_email TEXT - New email address
@doc*/
CREATE OR REPLACE FUNCTION public.update_email(
    user_id BIGINT,
    new_email TEXT
) ...
```

Strict mode produces:

```
[DDL] update_email: :param 'old_email' not found in function signature
[DDL] update_email: parameter 'user_id' exists in DDL but missing from :param
```

### 14.4 Nested Repository

Given this source structure:

```
src/sql/
├── core/
│   ├── tables/
│   │   ├── users.sql
│   │   └── profiles.sql
│   └── functions/
│       └── user_mgmt.sql
├── reporting/
│   └── views/
│       └── dashboards.sql
└── auth/
    └── sessions.sql
```

Running `groovy sqldoc-parser.groovy --index --dep-graph --undocumented src/sql/` produces:

```
docs/
├── README.md
├── index.md
├── dependencies.md
├── dependencies.mermaid
├── undocumented.md
├── core/
│   ├── README.md
│   ├── tables/
│   │   ├── README.md
│   │   ├── users.md
│   │   └── profiles.md
│   └── functions/
│       ├── README.md
│       └── user_mgmt.md
├── reporting/
│   ├── README.md
│   └── views/
│       ├── README.md
│       └── dashboards.md
└── auth/
    ├── README.md
    └── sessions.md
```

A developer browsing `docs/core/` sees the `README.md` linking to `tables/` and `functions/`. Drilling into `docs/core/tables/README.md` shows a summary of all tables with links to individual docs. The global `index.md` provides a cross-cutting view by schema and type regardless of directory structure.

---

## 15. Future Considerations

The following items are out of scope for v1.x but may be addressed in future iterations:

- **Path B: `pg_dump` input mode.** Accept `pg_dump --schema-only` output as an alternative to raw `.sql` files for more reliable DDL parsing.
- **HTML output.** Generate styled HTML documentation with search and navigation in addition to Markdown.
- **Configurable strict rules.** Allow per-project `.sqldocrc` files to customize which tags are required per type and to define custom tags.
- **`--fail-undocumented` flag.** Make undocumented objects a hard failure (exit code 1) separate from `--strict`.
- **View column introspection.** Parse `SELECT` statements in `CREATE VIEW` to infer column names (limited feasibility without DB connection).
- **Integration with `information_schema`.** Optional live-DB mode that merges runtime schema metadata with doc blocks for authoritative type, nullability, and default information.
- **Incremental generation.** Only regenerate docs for files that have changed since the last run, using file modification timestamps or git diff.
- **Custom output templates.** Allow users to provide their own Mustache/Handlebars templates for rendered output.
