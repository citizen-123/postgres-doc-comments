#!/usr/bin/env groovy

/**
 * SQL DocString Parser (sqldoc)
 *
 * Parses /*@doc ... @doc* / blocks from PostgreSQL files, infers structure
 * from DDL (CREATE TABLE/FUNCTION), merges doc annotations on top, and
 * generates Markdown documentation.
 *
 * DDL inference: params, returns, fields, constraints, and FK dependencies
 * are auto-populated from the CREATE statement when not declared in a doc
 * block. Doc declarations always override inferred values. In strict mode,
 * the parser warns when a doc block declares items that don't exist in DDL.
 *
 * Usage:
 *   groovy sqldoc-parser.groovy [OPTIONS] <path>
 *
 * Options:
 *   --strict              Enable linter mode
 *   --out <dir>           Output directory (default: ./docs)
 *   --single-file         Combine all output into one markdown file
 *   --index               Generate index.md
 *   --dep-graph           Generate Mermaid dependency graph
 *   --undocumented        Report undocumented CREATE statements
 *   -h, --help            Show usage
 */

import groovy.io.FileType
import groovy.transform.Field

// ─── Config & Constants ──────────────────────────────────────────────

@Field Set BLOCK_TAGS = [
    'desc', 'returns', 'changelog', 'fields', 'constraints',
    'triggers', 'example', 'performance', 'security', 'deprecated'
] as Set

@Field Set MULTI_TAGS = ['param', 'throws', 'see', 'todo', 'example'] as Set

@Field Map STRICT_RULES = [
    'function'      : ['name', 'desc', 'param', 'returns'],
    'procedure'     : ['name', 'desc', 'param'],
    'view'          : ['name', 'desc', 'returns'],
    'trigger'       : ['name', 'desc'],
    'table'         : ['name', 'desc', 'fields'],
    'index'         : ['name', 'desc'],
    '_file'         : ['desc'],
    '_default'      : ['name', 'desc'],
]

@Field def DOC_BLOCK_PATTERN = ~/(?s)\/\*@doc\b(.*?)@doc\*\//
@Field def CREATE_PATTERN = ~/(?i)^\s*CREATE\s+(?:OR\s+REPLACE\s+)?(?:TEMP(?:ORARY)?\s+)?(FUNCTION|PROCEDURE|VIEW|TRIGGER|TABLE|INDEX|MATERIALIZED\s+VIEW)\s+(?:IF\s+NOT\s+EXISTS\s+)?(\S+)/
@Field def ALL_CREATE_PATTERN = ~/(?im)^\s*CREATE\s+(?:OR\s+REPLACE\s+)?(?:TEMP(?:ORARY)?\s+)?(FUNCTION|PROCEDURE|VIEW|TRIGGER|TABLE|INDEX|MATERIALIZED\s+VIEW)\s+(?:IF\s+NOT\s+EXISTS\s+)?(\S+)/

// ─── Argument Parsing ────────────────────────────────────────────────

def parseArgs(String[] args) {
    def config = [
        strict: false, outDir: './docs', singleFile: false,
        index: false, depGraph: false, undocumented: false, paths: [],
    ]
    def iter = args.toList().iterator()
    while (iter.hasNext()) {
        def arg = iter.next()
        switch (arg) {
            case '--strict':       config.strict = true; break
            case '--out':
                if (!iter.hasNext()) { System.err.println("--out requires a directory"); System.exit(1) }
                config.outDir = iter.next(); break
            case '--single-file':  config.singleFile = true; break
            case '--index':        config.index = true; break
            case '--dep-graph':    config.depGraph = true; break
            case '--undocumented': config.undocumented = true; break
            case '-h': case '--help': printUsage(); System.exit(0); break
            default: config.paths << arg
        }
    }
    if (config.paths.isEmpty()) {
        System.err.println("Error: No input path specified."); printUsage(); System.exit(1)
    }
    return config
}

def printUsage() {
    println """
Usage: groovy sqldoc-parser.groovy [OPTIONS] <path>

Options:
  --strict          Enable linter mode (enforce required tags, validate doc vs DDL)
  --out <dir>       Output directory (default: ./docs)
  --single-file     Combine all output into one markdown file
  --index           Generate index.md grouped by schema and type
  --dep-graph       Generate Mermaid dependency graph
  --undocumented    Report CREATE statements missing doc blocks
  -h, --help        Show this help
"""
}

// ─── Data Structures ─────────────────────────────────────────────────

class DDLParam {
    String name
    String type
    String defaultValue  // null if none
}

class DDLField {
    String name
    String type
    boolean nullable = true
    String defaultValue   // null if none
}

class DDLConstraint {
    String name          // may be null for unnamed
    String definition    // e.g. "PRIMARY KEY (user_id)"
}

class DDLInfo {
    List<DDLParam> params = []           // function/procedure params
    String returns                       // function return type/clause
    List<DDLField> fields = []           // table columns
    List<DDLConstraint> constraints = [] // table constraints
    List<String> fkDependencies = []     // tables referenced by FKs
    boolean parsed = false               // true if DDL was successfully parsed
}

class DocBlock {
    String scope
    String sourceFile
    String inferredName
    String inferredType
    Map<String, Object> tags = [:]
    int lineNumber
    List<String> lintErrors = []
    DDLInfo ddl = new DDLInfo()

    String getEffectiveName() { tags['name'] ?: inferredName ?: 'Untitled' }
    String getEffectiveType() { tags['type'] ?: inferredType ?: '' }
    String getEffectiveSchema() { tags['schema'] ?: '' }
    String getQualifiedName() {
        def s = effectiveSchema; def n = effectiveName
        if (!s || n.startsWith("${s}.")) return n
        return "${s}.${n}"
    }
}

// ─── DDL Parsing Helpers ─────────────────────────────────────────────

/**
 * Extract text inside balanced parentheses starting from the first '('.
 * Returns null if no balanced parens found.
 */
def extractBalancedParens(String text) {
    def start = text.indexOf('(')
    if (start < 0) return null

    def depth = 0
    def inQuote = false
    def quoteChar = (char) 0
    for (int i = start; i < text.length(); i++) {
        def c = text.charAt(i)
        if (inQuote) {
            if (c == quoteChar) inQuote = false
            continue
        }
        if (c == (char) "'" || c == (char) '"') {
            inQuote = true; quoteChar = c; continue
        }
        if (c == (char) '(') depth++
        if (c == (char) ')') {
            depth--
            if (depth == 0) return text.substring(start + 1, i)
        }
    }
    return null
}

/**
 * Split on commas respecting nested parens and quotes.
 */
def splitOnCommas(String text) {
    def parts = []
    def depth = 0
    def inQuote = false
    def quoteChar = (char) 0
    def current = new StringBuilder()

    for (int i = 0; i < text.length(); i++) {
        def c = text.charAt(i)
        if (inQuote) {
            current << c
            if (c == quoteChar) inQuote = false
            continue
        }
        if (c == (char) "'" || c == (char) '"') {
            inQuote = true; quoteChar = c; current << c; continue
        }
        if (c == (char) '(') { depth++; current << c; continue }
        if (c == (char) ')') { depth--; current << c; continue }
        if (c == (char) ',' && depth == 0) {
            parts << current.toString().trim()
            current = new StringBuilder()
            continue
        }
        current << c
    }
    if (current.toString().trim()) parts << current.toString().trim()
    return parts
}

// ─── Function DDL Parsing ────────────────────────────────────────────

/**
 * Parse a CREATE FUNCTION/PROCEDURE statement to extract params and returns.
 */
def parseFunctionDDL(String afterBlock) {
    def info = new DDLInfo()

    // Extract parameter list from first balanced parens
    def paramText = extractBalancedParens(afterBlock)
    if (paramText == null) return info

    info.parsed = true

    // Parse individual params
    if (paramText.trim()) {
        splitOnCommas(paramText).each { raw ->
            def param = parseSingleParam(raw.trim())
            if (param) info.params << param
        }
    }

    // Find RETURNS clause after the closing paren
    def afterParens = afterBlock.substring(afterBlock.indexOf(paramText) + paramText.length() + 1)
    def returnsMatch = (afterParens =~ /(?is)\bRETURNS\s+(.+?)(?:\s+AS\s+|\s+LANGUAGE\s+|\s+SECURITY\s+|\s+SET\s+|\s+COST\s+|\s+ROWS\s+|\s*\$\$|\s*;)/)
    if (returnsMatch.find()) {
        def returnsRaw = returnsMatch.group(1).trim()
        // If RETURNS TABLE(...), include the full table definition
        if (returnsRaw.toUpperCase().startsWith('TABLE')) {
            def tableContent = extractBalancedParens(returnsRaw)
            info.returns = tableContent ? "TABLE(${tableContent})" : returnsRaw
        } else {
            info.returns = returnsRaw.split(/\s+/)[0] // Just the type
        }
    }

    return info
}

/**
 * Parse a single function parameter like "days_back INT DEFAULT 30"
 * Handles: name TYPE, IN name TYPE, OUT name TYPE, INOUT name TYPE, VARIADIC name TYPE
 */
def parseSingleParam(String raw) {
    if (!raw) return null
    def param = new DDLParam()

    // Strip mode keywords
    def cleaned = raw.replaceFirst(/(?i)^\s*(IN\s+OUT|INOUT|IN|OUT|VARIADIC)\s+/, '')

    // Split into tokens
    def tokens = cleaned.split(/\s+/)
    if (tokens.length < 2) return null

    param.name = tokens[0]
    // Accumulate type (could be multi-word like "CHARACTER VARYING")
    def typeTokens = []
    def hitDefault = false
    def defaultTokens = []
    for (int i = 1; i < tokens.length; i++) {
        if (tokens[i].toUpperCase() == 'DEFAULT' || tokens[i] == '=') {
            hitDefault = true; continue
        }
        if (hitDefault) {
            defaultTokens << tokens[i]
        } else {
            typeTokens << tokens[i]
        }
    }
    param.type = typeTokens.join(' ')
    param.defaultValue = defaultTokens ? defaultTokens.join(' ') : null

    return param
}

// ─── Table DDL Parsing ───────────────────────────────────────────────

/**
 * Parse a CREATE TABLE statement to extract fields, constraints, and FK deps.
 */
def parseTableDDL(String afterBlock) {
    def info = new DDLInfo()

    def bodyText = extractBalancedParens(afterBlock)
    if (bodyText == null) return info

    info.parsed = true

    // Constraint keywords that start a table-level constraint
    def constraintStarters = ~/(?i)^\s*(CONSTRAINT\s+\S+\s+)?(PRIMARY\s+KEY|UNIQUE|CHECK|FOREIGN\s+KEY|EXCLUDE)\s*/

    splitOnCommas(bodyText).each { element ->
        def trimmed = element.trim()
        if (!trimmed) return

        if (trimmed =~ constraintStarters) {
            // Table-level constraint
            def constraint = parseConstraint(trimmed)
            if (constraint) {
                info.constraints << constraint
                // Extract FK dependencies
                def fkMatch = (trimmed =~ /(?i)REFERENCES\s+(\S+)/)
                if (fkMatch.find()) {
                    def ref = fkMatch.group(1).replaceAll(/\(.*/, '')
                    info.fkDependencies << ref
                }
            }
        } else {
            // Column definition
            def field = parseColumnDef(trimmed)
            if (field) {
                info.fields << field
                // Check for inline constraints
                extractInlineConstraints(trimmed, field.name, info)
            }
        }
    }
    return info
}

/**
 * Parse a column definition like "email TEXT NOT NULL DEFAULT 'x'"
 */
def parseColumnDef(String raw) {
    def field = new DDLField()
    def tokens = raw.split(/\s+/)
    if (tokens.length < 2) return null

    field.name = tokens[0]

    // Accumulate type tokens until we hit a constraint keyword
    def constraintKeywords = ['NOT', 'NULL', 'DEFAULT', 'PRIMARY', 'UNIQUE',
        'CHECK', 'REFERENCES', 'CONSTRAINT', 'GENERATED', 'COLLATE'] as Set
    def typeTokens = []
    def i = 1
    while (i < tokens.length && !constraintKeywords.contains(tokens[i].toUpperCase())) {
        typeTokens << tokens[i]
        i++
    }
    field.type = typeTokens.join(' ')

    // Check nullability
    def upperRaw = raw.toUpperCase()
    if (upperRaw.contains('NOT NULL')) field.nullable = false

    // Extract default
    def defaultMatch = (raw =~ /(?i)\bDEFAULT\s+(.+?)(?:\s+NOT\s+NULL|\s+NULL|\s+PRIMARY|\s+UNIQUE|\s+CHECK|\s+REFERENCES|\s+CONSTRAINT|\s+GENERATED|\s*$)/)
    if (defaultMatch.find()) {
        field.defaultValue = defaultMatch.group(1).trim()
    }

    return field
}

/**
 * Extract inline constraints from a column definition.
 * e.g. "user_id BIGINT PRIMARY KEY" or "email TEXT REFERENCES other(id)"
 */
def extractInlineConstraints(String raw, String colName, DDLInfo info) {
    def upper = raw.toUpperCase()
    if (upper.contains('PRIMARY KEY')) {
        info.constraints << new DDLConstraint(name: null, definition: "PRIMARY KEY (${colName})")
    }
    if (upper =~ /\bUNIQUE\b/) {
        info.constraints << new DDLConstraint(name: null, definition: "UNIQUE (${colName})")
    }
    def fkMatch = (raw =~ /(?i)\bREFERENCES\s+(\S+)/)
    if (fkMatch.find()) {
        def ref = fkMatch.group(1).replaceAll(/\(.*/, '')
        info.fkDependencies << ref
        def refClause = raw.replaceFirst(/(?i).*\b(REFERENCES\s+.+)/, '$1').trim()
        info.constraints << new DDLConstraint(name: null, definition: "FOREIGN KEY (${colName}) ${refClause}")
    }
}

/**
 * Parse a table-level constraint like "CONSTRAINT pk_users PRIMARY KEY (user_id)"
 */
def parseConstraint(String raw) {
    def constraint = new DDLConstraint()
    def named = (raw =~ /(?i)^\s*CONSTRAINT\s+(\S+)\s+(.+)/)
    if (named.find()) {
        constraint.name = named.group(1)
        constraint.definition = named.group(2).trim()
    } else {
        constraint.name = null
        constraint.definition = raw.trim()
    }
    return constraint
}

// ─── DDL + Doc Merge ─────────────────────────────────────────────────

/**
 * Merge DDL-inferred info into doc tags. Doc always overrides.
 * Validates doc declarations against DDL and warns on mismatches.
 */
def mergeDDLAndDoc(DocBlock doc) {
    if (!doc.ddl.parsed || doc.scope != 'object') return

    def type = doc.effectiveType

    if (type == 'function' || type == 'procedure') {
        mergeFunctionDDL(doc)
    } else if (type == 'table') {
        mergeTableDDL(doc)
    }
}

def mergeFunctionDDL(DocBlock doc) {
    def ddl = doc.ddl

    // ── :param ──
    if (!doc.tags['param'] || (doc.tags['param'] instanceof List && doc.tags['param'].isEmpty())) {
        // No doc params: auto-populate from DDL
        if (ddl.params) {
            doc.tags['param'] = ddl.params.collect { p ->
                def line = "${p.name} ${p.type}"
                if (p.defaultValue) line += " - [default: ${p.defaultValue}]"
                return line
            }
            doc.tags['_param_inferred'] = true
        }
    } else {
        // Doc params exist: validate against DDL
        validateDocParams(doc)
    }

    // ── :returns ──
    if (!doc.tags['returns'] && ddl.returns) {
        doc.tags['returns'] = ddl.returns
        doc.tags['_returns_inferred'] = true
    }
}

def mergeTableDDL(DocBlock doc) {
    def ddl = doc.ddl

    // ── :fields ──
    if (!doc.tags['fields'] && ddl.fields) {
        def lines = ddl.fields.collect { f ->
            def extras = []
            if (!f.nullable) extras << 'NOT NULL'
            if (f.defaultValue) extras << "default: ${f.defaultValue}"
            def desc = extras ? "- ${extras.join(', ')}" : ''
            return "  ${f.name} ${f.type} ${desc}".trim()
        }
        doc.tags['fields'] = lines.join('\n')
        doc.tags['_fields_inferred'] = true
    } else if (doc.tags['fields']) {
        validateDocFields(doc)
    }

    // ── :constraints ──
    if (!doc.tags['constraints'] && ddl.constraints) {
        def lines = ddl.constraints.collect { c ->
            def name = c.name ?: '(unnamed)'
            return "  ${name} ${c.definition}"
        }
        doc.tags['constraints'] = lines.join('\n')
        doc.tags['_constraints_inferred'] = true
    } else if (doc.tags['constraints']) {
        validateDocConstraints(doc)
    }

    // ── :depends (FK-derived, merge not override) ──
    if (ddl.fkDependencies) {
        def existing = doc.tags['depends'] ? doc.tags['depends'].split(/\s*,\s*/).collect { it.trim() } : []
        def fkDeps = ddl.fkDependencies.collect { dep ->
            // Normalize: strip schema if it matches doc schema
            def d = dep.contains('.') ? dep : (doc.effectiveSchema ? "${doc.effectiveSchema}.${dep}" : dep)
            return d
        }
        def merged = (existing + fkDeps).unique()
        if (merged.size() > existing.size()) {
            doc.tags['depends'] = merged.join(', ')
        }
    }
}

// ─── Validation ──────────────────────────────────────────────────────

/**
 * Validate doc-declared :param names against DDL params.
 */
def validateDocParams(DocBlock doc) {
    if (!doc.ddl.parsed || !doc.ddl.params) return

    def ddlNames = doc.ddl.params.collect { it.name.toLowerCase() } as Set
    def docParams = doc.tags['param'] instanceof List ? doc.tags['param'] : [doc.tags['param']]

    docParams.each { p ->
        def docName = p.trim().split(/\s+/)[0]?.toLowerCase()
        if (docName && !ddlNames.contains(docName)) {
            def msg = "[DDL] ${doc.effectiveName}: :param '${docName}' not found in function signature"
            doc.lintErrors << msg
        }
    }

    // Warn about DDL params missing from doc
    def docNames = docParams.collect { it.trim().split(/\s+/)[0]?.toLowerCase() }.findAll { it } as Set
    ddlNames.each { ddlName ->
        if (!docNames.contains(ddlName)) {
            def msg = "[DDL] ${doc.effectiveName}: parameter '${ddlName}' exists in DDL but missing from :param"
            doc.lintErrors << msg
        }
    }
}

/**
 * Validate doc-declared :fields against DDL columns.
 */
def validateDocFields(DocBlock doc) {
    if (!doc.ddl.parsed || !doc.ddl.fields) return

    def ddlNames = doc.ddl.fields.collect { it.name.toLowerCase() } as Set

    doc.tags['fields'].split('\n').findAll { it.trim() }.each { line ->
        def docColName = line.trim().split(/\s+/)[0]?.toLowerCase()
        if (docColName && !ddlNames.contains(docColName)) {
            def msg = "[DDL] ${doc.effectiveName}: :fields declares '${docColName}' which does not exist in table DDL"
            doc.lintErrors << msg
        }
    }

    // Warn about DDL columns missing from doc
    def docNames = doc.tags['fields'].split('\n').findAll { it.trim() }
        .collect { it.trim().split(/\s+/)[0]?.toLowerCase() }.findAll { it } as Set
    ddlNames.each { ddlName ->
        if (!docNames.contains(ddlName)) {
            def msg = "[DDL] ${doc.effectiveName}: column '${ddlName}' exists in DDL but missing from :fields"
            doc.lintErrors << msg
        }
    }
}

/**
 * Validate doc-declared :constraints against DDL constraints.
 */
def validateDocConstraints(DocBlock doc) {
    if (!doc.ddl.parsed || !doc.ddl.constraints) return

    def ddlConstraintNames = doc.ddl.constraints
        .findAll { it.name }
        .collect { it.name.toLowerCase() } as Set

    doc.tags['constraints'].split('\n').findAll { it.trim() }.each { line ->
        def docName = line.trim().split(/\s+/)[0]?.toLowerCase()
        if (docName && docName != '(unnamed)' && ddlConstraintNames && !ddlConstraintNames.contains(docName)) {
            def msg = "[DDL] ${doc.effectiveName}: :constraints declares '${docName}' which does not exist in table DDL"
            doc.lintErrors << msg
        }
    }
}

// ─── Tag Parsing ─────────────────────────────────────────────────────

def parseTags(String raw) {
    def tags = [:] as LinkedHashMap
    def currentTag = null
    def currentLines = []

    def flush = {
        if (currentTag != null) {
            def value = currentLines.join('\n').trim()
            if (MULTI_TAGS.contains(currentTag)) {
                if (!tags.containsKey(currentTag)) tags[currentTag] = []
                if (tags[currentTag] instanceof List) {
                    tags[currentTag] << value
                } else {
                    tags[currentTag] = [tags[currentTag], value]
                }
            } else {
                tags[currentTag] = value
            }
        }
    }

    raw.eachLine { line ->
        def tagMatch = (line =~ /^\s*:(\w+)\s*(.*)/)
        if (tagMatch.matches()) {
            flush()
            currentTag = tagMatch[0][1]
            def rest = tagMatch[0][2]?.trim() ?: ''
            currentLines = rest ? [rest] : []
        } else if (currentTag != null) {
            def trimmed = line.replaceFirst(/^\s{2,}/, '')
            if (trimmed || BLOCK_TAGS.contains(currentTag)) {
                currentLines << trimmed
            }
        }
    }
    flush()
    return tags
}

// ─── File Parsing ────────────────────────────────────────────────────

def parseFile(String content, String sourcePath) {
    def blocks = []
    def matcher = content =~ DOC_BLOCK_PATTERN
    while (matcher.find()) {
        def blockEnd = matcher.end()
        def rawBody = matcher.group(1)
        def lineNum = content.substring(0, matcher.start()).count('\n') + 1

        def doc = new DocBlock(lineNumber: lineNum, sourceFile: sourcePath)
        doc.tags = parseTags(rawBody)

        def afterBlock = content.substring(blockEnd).trim()
        def createMatch = (afterBlock =~ CREATE_PATTERN)

        if (createMatch.find() && createMatch.start() < 10) {
            doc.scope = 'object'
            doc.inferredType = createMatch[0][1].toLowerCase().replaceAll(/\s+/, '_')
            doc.inferredName = createMatch[0][2].replaceAll(/[("')\s]/, '')
            if (doc.inferredName.contains('.')) {
                def parts = doc.inferredName.split(/\./, 2)
                if (!doc.tags['schema']) doc.tags['schema'] = parts[0]
                doc.inferredName = parts[1]
            }
            if (doc.inferredType == 'materialized_view') doc.inferredType = 'view'

            // ── DDL Inference ──
            def type = doc.tags['type'] ?: doc.inferredType
            if (type == 'function' || type == 'procedure') {
                doc.ddl = parseFunctionDDL(afterBlock)
            } else if (type == 'table') {
                doc.ddl = parseTableDDL(afterBlock)
            }
        } else if (blocks.isEmpty()) {
            doc.scope = 'file'
        } else {
            doc.scope = 'object'
        }
        blocks << doc
    }
    return blocks
}

def findAllCreateStatements(String content) {
    def creates = []
    def matcher = content =~ ALL_CREATE_PATTERN
    while (matcher.find()) {
        def objType = matcher.group(1).toLowerCase().replaceAll(/\s+/, '_')
        def rawName = matcher.group(2).replaceAll(/[("')\s]/, '')
        if (objType == 'materialized_view') objType = 'view'
        def objName = rawName.contains('.') ? rawName.split(/\./, 2)[1] : rawName
        creates << [type: objType, name: objName]
    }
    return creates
}

// ─── Linting ─────────────────────────────────────────────────────────

def lint(List<DocBlock> blocks) {
    def errors = []
    blocks.each { doc ->
        def type = doc.scope == 'file' ? '_file' : (doc.effectiveType ?: '_default')
        def required = STRICT_RULES[type] ?: STRICT_RULES['_default']
        required.each { tag ->
            if (tag == 'name' && doc.inferredName) return
            // Allow DDL-inferred tags to satisfy requirements
            if (doc.tags["_${tag}_inferred"]) return
            def val = doc.tags[tag]
            def empty = !val ||
                (val instanceof String && val.trim().isEmpty()) ||
                (val instanceof List && val.isEmpty())
            if (empty) {
                def msg = "[LINT] ${doc.effectiveName}: missing required tag :${tag}"
                doc.lintErrors << msg; errors << msg
            }
        }
        if (doc.tags['deprecated'] && !(doc.tags['deprecated'] =~ /(?i)(use|replace|see|migrate)/)) {
            def msg = "[LINT] ${doc.effectiveName}: :deprecated should suggest a replacement"
            doc.lintErrors << msg; errors << msg
        }
    }
    // Collect DDL validation warnings that were already added during merge
    blocks.each { doc ->
        doc.lintErrors.findAll { it.startsWith('[DDL]') }.each { errors << it }
    }
    return errors.unique()
}

// ─── Schema Inheritance ──────────────────────────────────────────────

def applyInheritance(List<DocBlock> blocks) {
    def fileBlock = blocks.find { it.scope == 'file' }
    if (!fileBlock) return
    def fileSchema = fileBlock.tags['schema']
    if (!fileSchema) return
    blocks.findAll { it.scope == 'object' && !it.tags['schema'] }.each {
        it.tags['schema'] = fileSchema
    }
}

// ─── Markdown Renderers ─────────────────────────────────────────────

def renderChangelog(String raw) {
    def sb = new StringBuilder()
    sb << "| Date | Author | Change |\n|------|--------|--------|\n"
    raw.split('\n').findAll { it.trim() }.each { line ->
        def parts = line.split(/\|/).collect { it.trim() }
        if (parts.size() >= 3) {
            sb << "| ${parts[0]} | ${parts[1]} | ${parts[2]} |\n"
        } else {
            sb << "| | | ${line.trim()} |\n"
        }
    }
    return sb.toString()
}

def renderColumnarTable(String raw, String[] headers) {
    def sb = new StringBuilder()
    sb << "| ${headers.join(' | ')} |\n"
    sb << "|${headers.collect { '------' }.join('|')}|\n"
    raw.split('\n').findAll { it.trim() }.each { line ->
        def parts = line.trim().split(/\s+-\s+/, 2)
        def colDef = parts[0].trim().split(/\s+/, 2)
        def col1 = colDef[0] ?: ''
        def col2 = colDef.size() > 1 ? colDef[1] : ''
        def col3 = parts.size() > 1 ? parts[1] : ''
        sb << "| `${col1}` | `${col2}` | ${col3} |\n"
    }
    return sb.toString()
}

def renderReturns(String raw, String type) {
    if (type == 'view' || raw.contains('\n')) {
        return renderColumnarTable(raw, ['Column', 'Type', 'Description'] as String[])
    }
    return "`${raw.trim()}`"
}

def renderParams(Object params) {
    def list = params instanceof List ? params : [params]
    def sb = new StringBuilder()
    sb << "| Parameter | Type | Description |\n|-----------|------|-------------|\n"
    list.each { p ->
        def parts = p.trim().split(/\s+/, 3)
        def name = parts[0] ?: ''
        def type = parts.size() > 1 ? parts[1] : ''
        def desc = parts.size() > 2 ? parts[2].replaceFirst(/^-\s*/, '') : ''
        sb << "| `${name}` | `${type}` | ${desc} |\n"
    }
    return sb.toString()
}

def renderListTag(Object items) {
    def list = items instanceof List ? items : [items]
    def sb = new StringBuilder()
    list.each { sb << "- ${it.trim()}\n" }
    return sb.toString()
}

def renderBlock(DocBlock doc) {
    def sb = new StringBuilder()
    def name = doc.effectiveName
    def type = doc.effectiveType

    if (doc.scope == 'file') { sb << "# ${name}\n\n" }
    else { sb << "## ${name}${type ? ' `' + type + '`' : ''}\n\n" }

    if (doc.tags['deprecated']) sb << "> **DEPRECATED:** ${doc.tags['deprecated']}\n\n"
    if (doc.tags['schema'])     sb << "**Schema:** `${doc.tags['schema']}`\n\n"
    if (doc.tags['version'])    sb << "**Version:** ${doc.tags['version']}\n\n"
    if (doc.tags['desc'])       sb << "${doc.tags['desc']}\n\n"

    def meta = []
    if (doc.tags['author']) meta << "**Author:** ${doc.tags['author']}"
    if (doc.tags['since'])  meta << "**Since:** ${doc.tags['since']}"
    if (meta) sb << meta.join(' | ') << "\n\n"

    if (doc.tags['param']) {
        def inferred = doc.tags['_param_inferred'] ? ' *(auto-generated from DDL)*' : ''
        sb << "### Parameters${inferred}\n\n" << renderParams(doc.tags['param']) << "\n"
    }
    if (doc.tags['returns']) {
        def inferred = doc.tags['_returns_inferred'] ? ' *(auto-generated from DDL)*' : ''
        sb << "### Returns${inferred}\n\n" << renderReturns(doc.tags['returns'], type) << "\n"
    }
    if (doc.tags['fields']) {
        def inferred = doc.tags['_fields_inferred'] ? ' *(auto-generated from DDL)*' : ''
        sb << "### Fields${inferred}\n\n" << renderColumnarTable(doc.tags['fields'], ['Column', 'Type', 'Description'] as String[]) << "\n"
    }
    if (doc.tags['constraints']) {
        def inferred = doc.tags['_constraints_inferred'] ? ' *(auto-generated from DDL)*' : ''
        sb << "### Constraints${inferred}\n\n" << renderColumnarTable(doc.tags['constraints'], ['Name', 'Definition', 'Description'] as String[]) << "\n"
    }
    if (doc.tags['triggers']) {
        sb << "### Triggers\n\n" << renderColumnarTable(doc.tags['triggers'], ['Name', 'Event', 'Description'] as String[]) << "\n"
    }
    if (doc.tags['throws']) {
        def throwsList = doc.tags['throws'] instanceof List ? doc.tags['throws'] : [doc.tags['throws']]
        sb << "### Throws\n\n"
        throwsList.each { t ->
            def parts = t.split(/\s+-\s+/, 2)
            sb << "- **`${parts[0].trim()}`**"
            if (parts.size() > 1) sb << " --- ${parts[1].trim()}"
            sb << "\n"
        }
        sb << "\n"
    }
    if (doc.tags['security'])    sb << "### Security\n\n${doc.tags['security']}\n\n"
    if (doc.tags['performance']) sb << "### Performance Notes\n\n${doc.tags['performance']}\n\n"

    if (doc.tags['example']) {
        def examples = doc.tags['example'] instanceof List ? doc.tags['example'] : [doc.tags['example']]
        sb << "### Example${examples.size() > 1 ? 's' : ''}\n\n"
        examples.eachWithIndex { ex, i ->
            if (examples.size() > 1) sb << "**Example ${i + 1}:**\n\n"
            sb << "```sql\n${ex}\n```\n\n"
        }
    }
    if (doc.tags['depends']) {
        sb << "### Dependencies\n\n"
        doc.tags['depends'].split(/\s*,\s*/).each { sb << "- `${it.trim()}`\n" }
        sb << "\n"
    }
    if (doc.tags['see'])  sb << "### See Also\n\n" << renderListTag(doc.tags['see']) << "\n"
    if (doc.tags['todo']) sb << "### TODO\n\n" << renderListTag(doc.tags['todo']) << "\n"
    if (doc.tags['changelog']) sb << "### Changelog\n\n" << renderChangelog(doc.tags['changelog']) << "\n"

    if (doc.lintErrors) {
        sb << "> **Lint warnings:**\n"
        doc.lintErrors.each { sb << "> - ${it}\n" }
        sb << "\n"
    }
    return sb.toString()
}

// ─── Index Page Generation ──────────────────────────────────────────

def generateIndex(List<DocBlock> allBlocks, File outDir) {
    def sb = new StringBuilder()
    sb << "# SQL Documentation Index\n\n"

    def fileBlocks = allBlocks.findAll { it.scope == 'file' }
    def objectBlocks = allBlocks.findAll { it.scope == 'object' }

    def typeCounts = objectBlocks.countBy { it.effectiveType ?: 'other' }
    sb << "## Summary\n\n| Type | Count |\n|------|-------|\n"
    typeCounts.sort().each { t, c -> sb << "| ${t} | ${c} |\n" }
    sb << "| **Total** | **${objectBlocks.size()}** |\n\n"

    def deprecatedBlocks = objectBlocks.findAll { it.tags['deprecated'] }
    if (deprecatedBlocks) {
        sb << "## Deprecated Objects\n\n> The following objects are deprecated and should be migrated:\n\n"
        deprecatedBlocks.each { doc ->
            sb << "- ~~`${doc.qualifiedName}`~~ `${doc.effectiveType}` --- ${doc.tags['deprecated']}\n"
        }
        sb << "\n"
    }

    def bySchema = objectBlocks.groupBy { it.effectiveSchema ?: '(no schema)' }
    bySchema.sort().each { schema, blocks ->
        sb << "## Schema: `${schema}`\n\n"
        def byType = blocks.groupBy { it.effectiveType ?: 'other' }
        byType.sort().each { t, typeBlocks ->
            sb << "### ${t.capitalize()}s\n\n| Name | Description | Version |\n|------|-------------|----------|\n"
            typeBlocks.sort { it.effectiveName }.each { doc ->
                def desc = doc.tags['desc'] ? doc.tags['desc'].split('\n')[0].take(80) : ''
                def ver = doc.tags['version'] ?: ''
                def pre = doc.tags['deprecated'] ? '~~' : ''
                sb << "| ${pre}`${doc.effectiveName}`${pre} | ${desc} | ${ver} |\n"
            }
            sb << "\n"
        }
    }

    if (fileBlocks) {
        sb << "## Source Files\n\n"
        fileBlocks.sort { it.effectiveName }.each { doc ->
            def desc = doc.tags['desc'] ? doc.tags['desc'].split('\n')[0].take(80) : ''
            sb << "- **${doc.effectiveName}** --- ${desc}\n"
        }
        sb << "\n"
    }

    new File(outDir, 'index.md').text = sb.toString()
    println "Generated: ${outDir}/index.md"
}

// ─── Dependency Graph Generation ────────────────────────────────────

def sanitizeMermaid(String name) { name.replaceAll(/[^a-zA-Z0-9_]/, '_') }

def generateDepGraph(List<DocBlock> allBlocks, File outDir) {
    def sb = new StringBuilder()
    sb << "graph LR\n"

    def objectBlocks = allBlocks.findAll { it.scope == 'object' }
    def nodes = [] as Set
    def edges = []

    def typeShapes = [
        'function' : { n -> "${sanitizeMermaid(n)}[\"${n}\"]" },
        'procedure': { n -> "${sanitizeMermaid(n)}[\"${n}\"]" },
        'view'     : { n -> "${sanitizeMermaid(n)}([\"${n}\"])" },
        'table'    : { n -> "${sanitizeMermaid(n)}[(\"${n}\")]" },
        'trigger'  : { n -> "${sanitizeMermaid(n)}{\"${n}\"}" },
    ]

    objectBlocks.each { doc ->
        def name = doc.qualifiedName
        nodes << name
        def shapeFn = typeShapes[doc.effectiveType]
        sb << "    ${shapeFn ? shapeFn(name) : sanitizeMermaid(name) + '[\"' + name + '\"]'}\n"
        if (doc.tags['deprecated']) sb << "    style ${sanitizeMermaid(name)} stroke-dasharray: 5 5\n"

        if (doc.tags['depends']) {
            doc.tags['depends'].split(/\s*,\s*/).each { dep ->
                def d = dep.trim(); nodes << d
                edges << [from: name, to: d, style: 'hard']
            }
        }
        if (doc.tags['see']) {
            def seeList = doc.tags['see'] instanceof List ? doc.tags['see'] : [doc.tags['see']]
            seeList.each { ref ->
                def r = ref.trim()
                if (r.contains('.')) { nodes << r; edges << [from: name, to: r, style: 'soft'] }
            }
        }
    }

    def definedNodes = objectBlocks.collect { it.qualifiedName } as Set
    (nodes - definedNodes).each { n ->
        sb << "    ${sanitizeMermaid(n)}[\"${n}\"]\n"
        sb << "    style ${sanitizeMermaid(n)} fill:#f5f5f5,stroke:#999\n"
    }

    edges.each { e ->
        def arrow = e.style == 'hard' ? ' --> ' : ' -.-> '
        sb << "    ${sanitizeMermaid(e.from)}${arrow}${sanitizeMermaid(e.to)}\n"
    }

    new File(outDir, 'dependencies.mermaid').text = sb.toString()
    println "Generated: ${outDir}/dependencies.mermaid"

    def mdSb = new StringBuilder()
    mdSb << "# Dependency Graph\n\n"
    mdSb << "> Solid arrows = `:depends` (hard dependency)\n"
    mdSb << "> Dashed arrows = `:see` (soft reference)\n"
    mdSb << "> Dashed borders = deprecated objects\n"
    mdSb << "> Gray fill = external (undocumented) dependencies\n\n"
    mdSb << "```mermaid\n"
    mdSb << sb.toString()
    mdSb << "```\n\n"
    mdSb << "## Legend\n\n"
    mdSb << "| Shape | Type |\n"
    mdSb << "|-------|------|\n"
    mdSb << "| Rectangle `[ ]` | Function / Procedure |\n"
    mdSb << "| Stadium `([ ])` | View |\n"
    mdSb << "| Cylinder `[( )]` | Table |\n"
    mdSb << "| Diamond `{ }` | Trigger |\n"

    new File(outDir, 'dependencies.md').text = mdSb.toString()
    println "Generated: ${outDir}/dependencies.md"
}

// ─── Undocumented Object Report ─────────────────────────────────────

def generateUndocumentedReport(Map fileBlocksMap, Map fileContents, File outDir) {
    def sb = new StringBuilder()
    sb << "# Undocumented Objects Report\n\n"
    def totalUndoc = 0; def totalDoc = 0

    fileContents.sort { it.key.path }.each { sqlFile, content ->
        def allCreates = findAllCreateStatements(content)
        if (allCreates.isEmpty()) return

        def blocks = fileBlocksMap[sqlFile] ?: []
        def docNames = blocks.findAll { it.scope == 'object' }
            .collect { (it.inferredName ?: it.tags['name'] ?: '').replaceAll(/[("')\s]/, '') }
            .findAll { it } as Set

        def undoc = allCreates.findAll { !docNames.contains(it.name.replaceAll(/[("')\s]/, '')) }

        if (undoc) {
            sb << "## ${sqlFile.name}\n\n| Object | Type | Status |\n|--------|------|--------|\n"
            allCreates.each { c ->
                def documented = docNames.contains(c.name.replaceAll(/[("')\s]/, ''))
                sb << "| `${c.name}` | ${c.type} | ${documented ? 'Documented' : '**MISSING**'} |\n"
                if (documented) totalDoc++ else totalUndoc++
            }
            sb << "\n"
        } else {
            totalDoc += allCreates.size()
        }
    }

    def total = totalDoc + totalUndoc
    def coverage = total > 0 ? ((totalDoc / total) * 100).round(1) : 100.0

    def summary = "## Coverage Summary\n\n| Metric | Value |\n|--------|-------|\n" +
        "| Total objects | ${total} |\n| Documented | ${totalDoc} |\n" +
        "| Undocumented | ${totalUndoc} |\n| **Coverage** | **${coverage}%** |\n\n"

    if (totalUndoc == 0) {
        sb << "${summary}All objects are documented.\n"
    } else {
        sb = new StringBuilder("# Undocumented Objects Report\n\n${summary}${sb.toString().replaceFirst(/^# Undocumented Objects Report\n\n/, '')}")
    }

    new File(outDir, 'undocumented.md').text = sb.toString()
    println "Generated: ${outDir}/undocumented.md"
    if (totalUndoc > 0) System.err.println("[WARN] ${totalUndoc} undocumented object(s) found (${coverage}% coverage)")
}

// ─── File Discovery ──────────────────────────────────────────────────

def collectSqlFiles(String path) {
    def f = new File(path)
    if (f.isFile()) return [f]
    def files = []
    f.eachFileRecurse(FileType.FILES) { if (it.name.endsWith('.sql')) files << it }
    return files.sort { it.path }
}

// ─── Directory README Generation ────────────────────────────────────

/**
 * After all docs are generated, walk the output directory tree and create
 * a README.md in every directory that contains .md doc files or subdirectories
 * with docs. Each README serves as a table of contents.
 */
def generateDirectoryReadmes(File outDir, Map<File, List<DocBlock>> dirBlocksMap) {
    // Collect all directories that have .md files (excluding README.md itself and top-level reports)
    def topLevelReports = ['index.md', 'dependencies.md', 'undocumented.md', 'sql-docs.md'] as Set

    // Build a tree: dir -> list of .md filenames in that dir
    def dirContents = [:] as LinkedHashMap  // File(dir) -> List<String> of .md filenames
    def dirChildren = [:] as LinkedHashMap  // File(dir) -> Set<File> of child dirs with docs

    outDir.eachFileRecurse(FileType.FILES) { f ->
        if (!f.name.endsWith('.md')) return
        if (f.name == 'README.md') return
        if (f.parentFile == outDir && topLevelReports.contains(f.name)) return

        def dir = f.parentFile
        if (!dirContents.containsKey(dir)) dirContents[dir] = []
        dirContents[dir] << f.name

        // Register parent chain up to outDir
        def current = dir
        while (current != outDir && current != null) {
            def parent = current.parentFile
            if (parent != null) {
                if (!dirChildren.containsKey(parent)) dirChildren[parent] = [] as Set
                dirChildren[parent] << current
            }
            current = parent
        }
    }

    // Generate README.md in each directory that has content
    def allDirs = (dirContents.keySet() + dirChildren.keySet()).unique()
    allDirs.each { dir ->
        def sb = new StringBuilder()
        def relativePath = outDir.toPath().relativize(dir.toPath()).toString()
        def dirName = relativePath ?: dir.name

        sb << "# ${dirName}\n\n"

        // Subdirectories
        def children = dirChildren[dir]?.sort { it.name }
        if (children) {
            sb << "## Subdirectories\n\n"
            children.each { child ->
                def childRel = dir.toPath().relativize(child.toPath()).toString()
                def childBlocks = dirBlocksMap[child]
                def desc = ''
                if (childBlocks) {
                    def fileBlock = childBlocks.find { it.scope == 'file' }
                    if (fileBlock?.tags?.desc) {
                        desc = " --- ${fileBlock.tags.desc.split('\n')[0].take(80)}"
                    }
                }
                sb << "- [${childRel}/](${childRel}/README.md)${desc}\n"
            }
            sb << "\n"
        }

        // Doc files in this directory
        def files = dirContents[dir]?.sort()
        if (files) {
            def blocks = dirBlocksMap[dir] ?: []
            def objectBlocks = blocks.findAll { it.scope == 'object' }

            // Summary counts if there are objects
            if (objectBlocks) {
                def typeCounts = objectBlocks.countBy { it.effectiveType ?: 'other' }
                sb << "## Summary\n\n| Type | Count |\n|------|-------|\n"
                typeCounts.sort().each { t, c -> sb << "| ${t} | ${c} |\n" }
                sb << "| **Total** | **${objectBlocks.size()}** |\n\n"
            }

            sb << "## SQL Files\n\n"
            sb << "| File | Description |\n"
            sb << "|------|-------------|\n"
            files.each { fname ->
                def sqlName = fname.replaceAll(/\.md$/, '.sql')
                // Find the file-level block for this file
                def fileBlock = blocks.find { b ->
                    b.scope == 'file' && (b.effectiveName == sqlName || b.effectiveName == fname.replaceAll(/\.md$/, ''))
                }
                def desc = ''
                if (fileBlock?.tags?.desc) {
                    desc = fileBlock.tags.desc.split('\n')[0].take(80)
                }
                sb << "| [${fname}](${fname}) | ${desc} |\n"
            }
            sb << "\n"

            // Per-file object listing
            files.each { fname ->
                def sqlName = fname.replaceAll(/\.md$/, '.sql')
                def fileObjects = objectBlocks.findAll { b ->
                    def bSourceName = new File(b.sourceFile).name
                    bSourceName == sqlName
                }
                if (fileObjects) {
                    sb << "### ${fname.replaceAll(/\.md$/, '')}\n\n"
                    sb << "| Object | Type | Version | Description |\n"
                    sb << "|--------|------|---------|-------------|\n"
                    fileObjects.sort { it.effectiveName }.each { doc ->
                        def desc = doc.tags['desc'] ? doc.tags['desc'].split('\n')[0].take(60) : ''
                        def ver = doc.tags['version'] ?: ''
                        def pre = doc.tags['deprecated'] ? '~~' : ''
                        sb << "| ${pre}`${doc.effectiveName}`${pre} | ${doc.effectiveType} | ${ver} | ${desc} |\n"
                    }
                    sb << "\n"
                }
            }
        }

        def readmeFile = new File(dir, 'README.md')
        readmeFile.text = sb.toString()
        println "Generated: ${readmeFile.path}"
    }
}

// ─── Main ────────────────────────────────────────────────────────────

def config = parseArgs(args)
def outDir = new File(config.outDir)
outDir.mkdirs()

def allMarkdown = new StringBuilder()
def totalLintErrors = []
def allBlocks = []
def fileBlocksMap = [:] as LinkedHashMap
def fileContentsMap = [:] as LinkedHashMap
// Track blocks per output directory for README generation
def dirBlocksMap = [:] as LinkedHashMap  // File(outputDir) -> List<DocBlock>

config.paths.each { path ->
    def inputRoot = new File(path)
    def isDir = inputRoot.isDirectory()

    collectSqlFiles(path).each { sqlFile ->
        def content = sqlFile.text
        def blocks = parseFile(content, sqlFile.path)
        fileContentsMap[sqlFile] = content
        fileBlocksMap[sqlFile] = blocks
        if (blocks.isEmpty()) return

        applyInheritance(blocks)
        blocks.each { mergeDDLAndDoc(it) }
        allBlocks.addAll(blocks)
        if (config.strict) totalLintErrors.addAll(lint(blocks))

        def md = new StringBuilder()
        blocks.each { md << renderBlock(it) << "---\n\n" }

        if (config.singleFile) {
            allMarkdown << "<!-- Source: ${sqlFile.path} -->\n\n" << md
        } else {
            // Mirror the input directory structure in the output
            def relativePath = ''
            if (isDir) {
                relativePath = inputRoot.toPath().relativize(sqlFile.parentFile.toPath()).toString()
            }
            def targetDir = relativePath ? new File(outDir, relativePath) : outDir
            targetDir.mkdirs()

            def outFile = new File(targetDir, sqlFile.name.replaceAll(/\.sql$/, '.md'))
            outFile.text = md.toString()
            println "Generated: ${outFile.path}"

            // Track blocks per output directory
            if (!dirBlocksMap.containsKey(targetDir)) dirBlocksMap[targetDir] = []
            dirBlocksMap[targetDir].addAll(blocks)
        }
    }
}

if (config.singleFile) {
    def outFile = new File(outDir, 'sql-docs.md')
    outFile.text = allMarkdown.toString()
    println "Generated: ${outFile.path}"
}

if (config.index)        generateIndex(allBlocks, outDir)
if (config.depGraph)     generateDepGraph(allBlocks, outDir)
if (config.undocumented) generateUndocumentedReport(fileBlocksMap, fileContentsMap, outDir)

// Generate per-directory READMEs (unless single-file mode)
if (!config.singleFile && dirBlocksMap) {
    generateDirectoryReadmes(outDir, dirBlocksMap)
}

if (config.strict && totalLintErrors) {
    System.err.println("\n${totalLintErrors.size()} lint error(s) found:")
    totalLintErrors.each { System.err.println("  ${it}") }
    System.exit(1)
}

println "\nDone."
