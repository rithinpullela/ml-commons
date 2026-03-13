# Parameter Auto-Generation from Search Templates — Design Document

## 1. Problem

Creating a custom tool today requires manually defining every parameter:

```json
POST /_plugins/_ml/tools/_create
{
  "name": "ProductSearchTool",
  "description": "Search products by category, brand, price, and color.",
  "type": "search_template",
  "search_template_name": "product_search_v2",
  "params": {
    "category":  { "type": "string",  "description": "Product category",  "required": true  },
    "brand":     { "type": "string",  "description": "Brand name",        "required": false },
    "price_max": { "type": "number",  "description": "Maximum price",     "required": false },
    "color":     { "type": "string",  "description": "Product color",     "required": false }
  }
}
```

The search template *already contains all of this information* in its Mustache variables and DSL structure. A customer with 20+ templates shouldn't have to manually author param schemas that duplicate what's in the template.

---

## 2. Why Regex Extraction Fails

The naive approach is to regex-scan the template source for `{{variable}}` patterns. This section explains why that doesn't work.

### 2.1 The Naive Regex

```regex
\{\{[^#/^!>].*?\}\}
```

This attempts to match `{{...}}` tags while excluding section openers (`#`), closers (`/`), inverted sections (`^`), comments (`!`), and partials (`>`).

### 2.2 Mustache Syntax That Breaks It

OpenSearch search templates use the Mustache templating language. The full Mustache spec plus OpenSearch's custom extensions create **19 distinct syntax patterns**. The naive regex handles only 3 of them correctly.

#### Blocker 1: `{{#toJson}}variable{{/toJson}}` — Variables as Plain Text

OpenSearch's `toJson` helper serializes arrays/objects to JSON. The variable name appears as **bare text** between section tags, not wrapped in `{{ }}`:

```json
{"query":{"terms":{"tags": {{#toJson}}tags{{/toJson}} }}}
```

The regex sees `{{#toJson}}` (excluded by `#`) and `{{/toJson}}` (excluded by `/`), but **completely misses `tags`** — the actual parameter. This is one of the most commonly used patterns for array parameters.

The `join` helper has the same problem:

```json
{"query":{"match":{"emails":"{{#join}}emails{{/join}}"}}}
```

#### Blocker 2: `{{{variable}}}` — Triple Braces (Unescaped Output)

Triple braces mean "inject raw JSON without escaping." Used when passing entire JSON objects/arrays:

```json
{"query": {{{my_query}}}}
```

The regex matches `{{{my_query}}}` but captures it incorrectly — the greedy/lazy behavior produces `{my_query}` or leaves a trailing `}`. The variable name extraction is unreliable.

#### Blocker 3: `{{#section}}...{{/section}}` — Section Controllers Are Parameters Too

Sections are conditionals. The section controller variable (e.g., `genre` in `{{#genre}}...{{/genre}}`) is itself a parameter — it determines whether the block renders. But the regex **excludes all `{{#...}}` tags**, so section-only parameters are invisible:

```json
{"query":{"bool":{"must":[{"match":{"title":"{{query}}"}}]
  {{#genre}},"filter":[{"term":{"genre":"{{genre}}"}}]{{/genre}}}}}
```

Here `genre` appears both as a section controller AND as a variable inside the section. If it only appeared as a controller (common in boolean flag patterns like `{{#include_highlights}}...{{/include_highlights}}`), the regex would miss it entirely.

#### Blocker 4: `{{=<% %>=}}` — Delimiter Changes

Mustache allows changing tag delimiters. After `{{=<% %>=}}`, all variables use `<% %>` syntax:

```json
{{=<% %>=}}{"query":{"match":{"title":"<% query %>"}}}
```

The regex sees `{{=<% %>=}}` as a false positive, then **misses every subsequent variable** because they no longer use `{{ }}`.

#### Blocker 5: Scope Ambiguity in Nested Sections

Variables inside sections may refer to object fields, not root parameters:

```json
{{#user}}{"name":"{{name}}","email":"{{email}}"}{{/user}}
```

Is `name` a root parameter or a field on the `user` object? The regex has no scope tracking, so it can't tell.

#### Blocker 6: `{{/section}}` — Closing Tags Are False Positives

The exclusion regex `[^#/^!>]` is meant to exclude closing tags, but `{{/section}}` starts with `/` which IS in the exclusion set. However, subtle whitespace variations like `{{ /section }}` would slip through as a false positive.

### 2.3 Summary: Regex vs. Real Patterns

| Pattern | Example | Regex correct? | Real-world frequency |
|---|---|---|---|
| `{{var}}` | `{{query_text}}` | Yes | Very common |
| `{{{var}}}` | `{{{raw_query}}}` | Mis-parses | Common for JSON injection |
| `{{&var}}` | `{{&raw_query}}` | Needs post-processing | Rare |
| `{{.}}` | `{{.}}` in loops | False positive | Common in loops |
| `{{obj.field}}` | `{{user.email}}` | Extracts full path | Occasional |
| `{{#section}}` | `{{#genre}}` | **Missed** | Very common |
| `{{^section}}` | `{{^from}}0{{/from}}` | **Missed** | Common for defaults |
| `{{#toJson}}var{{/toJson}}` | `{{#toJson}}tags{{/toJson}}` | **Missed entirely** | Common for arrays |
| `{{#join}}var{{/join}}` | `{{#join}}emails{{/join}}` | **Missed entirely** | Common for lists |
| `{{#url}}{{var}}{{/url}}` | `{{#url}}{{q}}{{/url}}` | Inner var extracted | Rare |
| `{{=<% %>=}}` | Delimiter change | **Breaks everything** | Rare |

**Conclusion:** Regex-based extraction is unreliable for anything beyond the simplest templates. We need a proper parser.

---

## 3. Solution: AST-Based Extraction Using mustache.java

### 3.1 The Mustache Java Library

OpenSearch compiles all Mustache search templates using the **mustache.java** library (`com.github.spullara.mustache.java:compiler:0.9.14`), wrapped in a `CustomMustacheFactory` that adds OpenSearch-specific helpers.

This library exposes a **full Abstract Syntax Tree (AST)** after compilation. Every element in the template becomes a `Code` node with:

- `getName()` — the variable/section name
- `getCodes()` — child nodes (for recursion into sections)
- Type identity — `ValueCode`, `IterableCode`, `NotIterableCode`, `WriteCode`, etc.

### 3.2 The Compilation Chain in OpenSearch Core

```
StoredScriptSource.getSource()          → raw template string
    ↓
CustomMustacheFactory.compile(reader)   → Mustache AST (Code[] tree)
    ↓
CustomMustacheVisitor.iterable()        → intercepts toJson/join/url sections
    ↓
Code tree with:
  ValueCode      → {{variable}}, {{{variable}}}, {{&variable}}
  IterableCode   → {{#section}}...{{/section}}
  NotIterableCode → {{^section}}...{{/section}}
  ToJsonCode     → {{#toJson}}var{{/toJson}}  (extends IterableCode)
  JoinerCode     → {{#join}}var{{/join}}      (extends IterableCode)
  UrlEncoderCode → {{#url}}...{{/url}}        (extends DefaultMustache)
  WriteCode      → literal text (not a variable)
```

**Key file:** `OpenSearch/modules/lang-mustache/src/main/java/org/opensearch/script/mustache/CustomMustacheFactory.java`

### 3.3 How AST Walking Solves Every Blocker

| Blocker | Regex | AST |
|---|---|---|
| `{{#toJson}}tags{{/toJson}}` | Misses `tags` | `ToJsonCode.getName()` returns `"tags"` |
| `{{#join}}emails{{/join}}` | Misses `emails` | `JoinerCode.getName()` returns `"emails"` |
| `{{{var}}}` triple braces | Mis-parses | `ValueCode` with `encoded=false` |
| `{{=<% %>=}}` delimiter change | Breaks | Parser handles internally |
| `{{.}}` implicit iterator | False positive | `ValueCode` with name `"."` — filtered |
| `{{obj.field}}` dot notation | Full path | Split on `.`, take root |
| `{{#flag}}` section controllers | Missed | `IterableCode.getName()` returns `"flag"` |
| Scope/nesting | No tracking | Recursive walk with scope stack |

### 3.4 Dependency

ml-commons would add the same library OpenSearch core already uses:

```gradle
// ml-algorithms/build.gradle
implementation "com.github.spullara.mustache.java:compiler:0.9.14"
```

To get OpenSearch's custom helper handling (toJson, join, url), we use `CustomMustacheFactory` which is a `public` class in the `lang-mustache` module. Since `lang-mustache` is loaded as a module at runtime, the class is available on the classpath.

If direct use of `CustomMustacheFactory` isn't feasible due to module boundaries, we can use `DefaultMustacheFactory` from the library directly and handle the OpenSearch helpers (toJson, join, url) as special-case section names in our walker — which is only a few lines of code.

---

## 4. The Extraction Algorithm

### 4.1 AST Walker Pseudocode

```java
class TemplateAnalyzer {

    AnalysisResult analyze(String templateSource) {
        MustacheFactory factory = new DefaultMustacheFactory();
        Mustache compiled = factory.compile(new StringReader(templateSource), "template");

        AnalysisResult result = new AnalysisResult();
        walkCodes(compiled.getCodes(), result, /* scopeDepth */ 0, /* parentSectionName */ null);
        classifyParams(result);
        return result;
    }

    void walkCodes(Code[] codes, AnalysisResult result, int scopeDepth, String parentSection) {
        if (codes == null) return;

        for (Code code : codes) {
            String name = code.getName();

            if (code instanceof ValueCode) {
                // {{variable}}, {{{variable}}}, {{&variable}}
                if (".".equals(name)) continue;  // skip implicit iterator

                String rootName = name.contains(".") ? name.split("\\.")[0] : name;
                result.addVariable(rootName, scopeDepth, parentSection);

            } else if (isToJsonOrJoin(code, name)) {
                // {{#toJson}}var{{/toJson}} or {{#join}}var{{/join}}
                // getName() already returns the inner variable name
                // (extracted at compile time by CustomCode.extractVariableName)
                result.addVariable(name, scopeDepth, parentSection);
                result.markAsArrayType(name);

            } else if (isUrlHelper(code, name)) {
                // {{#url}}...{{/url}} — transparent wrapper, recurse into children
                walkCodes(code.getCodes(), result, scopeDepth, parentSection);

            } else if (code instanceof IterableCode) {
                // {{#section}}...{{/section}} — conditional/loop
                String sectionName = name;
                result.addSectionController(sectionName, scopeDepth, parentSection);

                // Recurse into section body at deeper scope
                walkCodes(code.getCodes(), result, scopeDepth + 1, sectionName);

            } else if (code instanceof NotIterableCode) {
                // {{^section}}...{{/section}} — inverted section
                result.addInvertedSection(name, scopeDepth, parentSection);

                // Recurse — inverted sections don't change scope context
                walkCodes(code.getCodes(), result, scopeDepth, parentSection);

            } else {
                // WriteCode (literal text) or other — recurse if has children
                walkCodes(code.getCodes(), result, scopeDepth, parentSection);
            }
        }
    }

    boolean isToJsonOrJoin(Code code, String name) {
        // With CustomMustacheFactory: code instanceof ToJsonCode || code instanceof JoinerCode
        // With DefaultMustacheFactory: check if section name matches "toJson", "join",
        //   or "join delimiter='...'" pattern
        return "toJson".equalsIgnoreCase(name)
            || "join".equalsIgnoreCase(name)
            || (name != null && name.matches("(?i)join delimiter='.*'"));
    }

    boolean isUrlHelper(Code code, String name) {
        return "url".equalsIgnoreCase(name);
    }
}
```

### 4.2 Requiredness Classification

After walking the AST, we classify each discovered parameter:

```java
void classifyParams(AnalysisResult result) {
    for (ParamInfo param : result.allParams()) {

        if (param.hasInvertedSectionDefault()) {
            // Pattern: {{var}}{{^var}}default{{/var}}
            param.setRequired(false);

        } else if (param.appearsOnlyAsSectionController() && param.onlyAtDepth(0)) {
            // Pattern: {{#flag}}...{{/flag}} at root scope, never used as {{flag}}
            // This is a boolean/conditional guard — optional
            param.setRequired(false);

        } else if (param.appearsAtRootScope()) {
            // Pattern: {{var}} at scope depth 0, outside all sections
            param.setRequired(true);

        } else if (param.appearsOnlyInsideOwnSection()) {
            // Pattern: {{#var}}...{{var}}...{{/var}} — self-guarding
            param.setRequired(false);

        } else if (param.onlyAppearsInNestedScope()) {
            // Pattern: only inside {{#other}}...{{var}}...{{/other}}
            // Could be object field, not root param — mark as contextual
            param.setRequired(false);
            param.setContextual(true);
        }
    }
}
```

### 4.3 Walkthrough: Shakespeare Search Template

**Template:**
```
{"query":{"bool":{"must":[{"match":{"title":"{{query_text}}"}}]
{{#genre}},"filter":[{"term":{"genre":"{{genre}}"}}]{{/genre}}}},
"size":{{result_size}}}
```

**AST after compilation:**

```
DefaultMustache (root)
├── WriteCode: '{"query":{"bool":{"must":[{"match":{"title":"'
├── ValueCode: name="query_text"                                    ← scope 0
├── WriteCode: '"}}]'
├── IterableCode: name="genre"                                      ← scope 0, section controller
│   ├── WriteCode: ',"filter":[{"term":{"genre":"'
│   ├── ValueCode: name="genre"                                     ← scope 1, inside {{#genre}}
│   └── WriteCode: '"}}]'
├── WriteCode: '}},"size":'
├── ValueCode: name="result_size"                                   ← scope 0
└── WriteCode: '}'
```

**Classification:**

| Param | How discovered | Scope | Classification |
|---|---|---|---|
| `query_text` | `ValueCode` at scope 0 | Root | **required** — root scope, no fallback |
| `genre` | `IterableCode` controller at scope 0 + `ValueCode` at scope 1 inside own section | Self-guarding | **optional** — only appears inside `{{#genre}}` |
| `result_size` | `ValueCode` at scope 0 | Root | **required** — root scope, no fallback |

### 4.4 Walkthrough: Template with toJson and Defaults

**Template:**
```
{"query":{"terms":{"tags":{{#toJson}}tags{{/toJson}}}},
"from":{{from}}{{^from}}0{{/from}},"size":{{size}}{{^size}}10{{/size}}}
```

**AST after compilation:**

```
DefaultMustache (root)
├── WriteCode: '{"query":{"terms":{"tags":'
├── ToJsonCode: name="tags"                                         ← scope 0, helper
├── WriteCode: '}},"from":'
├── ValueCode: name="from"                                          ← scope 0
├── NotIterableCode: name="from"                                    ← inverted section
│   └── WriteCode: '0'
├── WriteCode: ',"size":'
├── ValueCode: name="size"                                          ← scope 0
├── NotIterableCode: name="size"                                    ← inverted section
│   └── WriteCode: '10'
└── WriteCode: '}'
```

**Classification:**

| Param | How discovered | Classification |
|---|---|---|
| `tags` | `ToJsonCode` (regex would have **missed** this) | **required** — root scope, no fallback, array type |
| `from` | `ValueCode` + `NotIterableCode` with default `0` | **optional** — has inverted section default |
| `size` | `ValueCode` + `NotIterableCode` with default `10` | **optional** — has inverted section default |

---

## 5. Three Tiers of Parameter Specification

The creation API supports three mutually exclusive modes. Each successive tier adds more intelligence but also more complexity/latency.

### 5.1 Tier Overview

| Tier | What user provides | Params extracted by | Descriptions generated by | Type inferred by |
|---|---|---|---|---|
| **Tier 1: Auto (no LLM)** | Neither `params` nor `model_id` | AST walker | Programmatic defaults from DSL context | Heuristic from AST context |
| **Tier 2: Auto + LLM** | `model_id` only | AST walker | LLM | LLM |
| **Tier 3: Manual** | `params` | User | User | User |

### 5.2 Tier 1: Programmatic Extraction with Default Descriptions

**Request:**
```json
POST /_plugins/_ml/tools/_create
{
  "name": "ShakespeareSearchTool",
  "description": "Search Shakespeare plays by title, optionally filter by genre",
  "type": "search_template",
  "search_template_name": "shakespeare_search"
}
```

No `params`, no `model_id`. The system does everything programmatically.

**What happens:**

1. Fetch stored script → get template source
2. Compile template with mustache.java → get AST
3. Walk AST → extract variables, section controllers, helpers
4. Classify required/optional from section nesting
5. Generate default types from DSL context heuristics
6. Generate default descriptions from variable name + DSL position
7. Store and return generated params

**Default type heuristics (no LLM needed):**

| AST context | Inferred type | Rationale |
|---|---|---|
| `ValueCode` inside quotes: `"field":"{{var}}"` | `string` | Quoted value in DSL = text |
| `ValueCode` unquoted: `"size":{{var}}` | `integer` | Bare value in size/from context = numeric |
| `ToJsonCode` | `array` | toJson is used for array/object injection |
| `JoinerCode` | `array` | join concatenates list elements |
| Everything else | `string` | Safe default |

To determine quoted vs. unquoted, we look at the `WriteCode` node immediately preceding the `ValueCode` in the AST. If it ends with `"`, the variable is quoted (string). If it ends with `:` or a digit context, it's unquoted (numeric).

**Default description generation (no LLM needed):**

Built from the variable name and its DSL context:

| Variable name | DSL context (from surrounding WriteCode) | Generated description |
|---|---|---|
| `query_text` | `"match":{"title":"{{query_text}}"}` | `"Value for the 'title' field (match query)"` |
| `genre` | `"term":{"genre":"{{genre}}"}` | `"Value for the 'genre' field (term filter)"` |
| `result_size` | `"size":{{result_size}}` | `"Value for 'size'"` |
| `tags` | `"terms":{"tags":{{#toJson}}tags{{/toJson}}}` | `"Value for the 'tags' field (terms query, array)"` |

These defaults are functional but not elegant. They tell the LLM enough to use the tool correctly. For better descriptions, use Tier 2.

**Response:**
```json
{
  "tool_id": "abc123",
  "params": {
    "query_text":  { "type": "string",  "description": "Value for the 'title' field (match query)",      "required": true  },
    "genre":       { "type": "string",  "description": "Value for the 'genre' field (term filter)",      "required": false },
    "result_size": { "type": "integer", "description": "Value for 'size'",                               "required": true  }
  }
}
```

**Latency:** ~10ms (compile + walk, no network calls)

### 5.3 Tier 2: LLM-Enhanced Descriptions

**Request:**
```json
POST /_plugins/_ml/tools/_create
{
  "name": "ShakespeareSearchTool",
  "description": "Search Shakespeare plays by title, optionally filter by genre",
  "type": "search_template",
  "search_template_name": "shakespeare_search",
  "model_id": "haiku-model-id"
}
```

`model_id` provided → Phase 1 (AST extraction) runs as in Tier 1, then Phase 2 calls the LLM for better types and descriptions.

**What happens:**

1. Steps 1-4 from Tier 1 (AST extraction, classification)
2. Build prompt with template source + extracted variable names + their AST context
3. Call LLM via `MachineLearningNodeClient.predict()`
4. Parse LLM response → merge types and descriptions over Phase 1 defaults
5. **Required/optional stays from Phase 1** — the LLM does NOT determine requiredness (it's structural, not semantic)
6. Store and return

**LLM Prompt:**

```
You are analyzing an OpenSearch Mustache search template to determine
parameter types and descriptions.

## Template Source
{template_source}

## Parameters to Annotate
The following parameters were extracted from the template.
For each one, determine its type and write a brief description.

Parameters: {extracted_variable_names}

## Instructions

For each parameter, determine:

1. **type**: Infer from how the parameter is used in the query DSL:
   - "string" — used in match, term, or text field contexts
   - "integer" — used as a bare numeric value (e.g., "size": {{var}})
   - "float" — used in range queries with decimal values or boost
   - "boolean" — used in boolean contexts
   - "array" — used with {{#toJson}} or {{#join}} helpers
   - Default to "string" if ambiguous

2. **description**: A concise description of what this parameter controls.
   Base it on the field name, query clause, and role in the query.

## Rules
- Annotate EXACTLY the parameters listed — do not add or remove any
- Do NOT determine "required" — that is handled separately

Return ONLY valid JSON:
{
  "params": {
    "param_name": {
      "type": "<type>",
      "description": "<description>"
    }
  }
}
```

**Response:**
```json
{
  "tool_id": "abc123",
  "params": {
    "query_text":  { "type": "string",  "description": "Text to match against the title field using full-text search",   "required": true  },
    "genre":       { "type": "string",  "description": "Genre keyword to filter results (e.g., tragedy, comedy)",        "required": false },
    "result_size": { "type": "integer", "description": "Maximum number of search results to return",                     "required": true  }
  }
}
```

**Latency:** ~1-3s (AST extraction + one LLM call)

**Error handling:** If the LLM returns malformed JSON or misses parameters, fall back to Tier 1 defaults for the affected params and log a warning.

### 5.4 Tier 3: User-Provided Params (Manual Override)

**Request:**
```json
POST /_plugins/_ml/tools/_create
{
  "name": "ShakespeareSearchTool",
  "description": "Search Shakespeare plays by title, optionally filter by genre",
  "type": "search_template",
  "search_template_name": "shakespeare_search",
  "params": {
    "query_text":  { "type": "text",    "description": "Words to match in the play title", "required": true  },
    "genre":       { "type": "keyword", "description": "Genre filter (tragedy or comedy)", "required": false },
    "result_size": { "type": "integer", "description": "Max results to return",            "required": false }
  }
}
```

The user provides `params` directly. **No AST extraction, no LLM call.** The user's params are stored exactly as provided.

This is the existing behavior, unchanged. The user has full control — they can use custom types, override requiredness, add descriptions that reference their domain, etc.

**Latency:** ~10ms (validation + index)

### 5.5 Validation Rules

| Fields provided | Behavior |
|---|---|
| Neither `params` nor `model_id` | **Tier 1** — auto-extract with programmatic defaults |
| `model_id` only | **Tier 2** — auto-extract + LLM enhancement |
| `params` only | **Tier 3** — manual, store as-is |
| Both `params` and `model_id` | **Error:** `Cannot specify both 'params' and 'model_id'` |

### 5.6 Updates via PUT

After creation (any tier), the user can update params via `PUT /_plugins/_ml/tools/{tool_id}`:

```json
PUT /_plugins/_ml/tools/{tool_id}
{
  "params": {
    "result_size": { "description": "Number of results (max 100)", "required": false }
  }
}
```

This is the correction path for Tier 1 (fix bad defaults) and Tier 2 (fix LLM mistakes). For Tier 3, the user already provided the params they wanted.

---

## 6. Creation Flow

```
POST /_plugins/_ml/tools/_create
  │
  ├→ 1. Standard validation (tenant, name uniqueness, name format)
  │
  ├→ 2. Check what's provided: params? model_id? neither?
  │     ├→ Both present → Error
  │     ├→ params present → Tier 3 (skip to step 6)
  │     └→ Continue to step 3
  │
  ├→ 3. Fetch stored script via GetStoredScriptRequest
  │     ├→ response.getSource() == null → Error: template not found
  │     └→ templateSource = response.getSource().getSource()
  │
  ├→ 4. Phase 1: AST-based extraction
  │     ├→ Compile template: factory.compile(new StringReader(templateSource))
  │     ├→ Walk Code[] tree recursively
  │     ├→ Collect: variable names, section controllers, helper vars
  │     ├→ Classify: required/optional from section nesting
  │     └→ Generate: default types + descriptions from DSL context
  │
  ├→ 5. Phase 2: LLM enhancement (only if model_id provided — Tier 2)
  │     ├→ Build prompt with template source + extracted variables
  │     ├→ Call model via MachineLearningNodeClient.predict()
  │     ├→ Parse JSON response
  │     ├→ Merge LLM types/descriptions over Phase 1 defaults
  │     └→ On LLM failure: fall back to Phase 1 defaults, log warning
  │
  └→ 6. Store custom tool with params (existing indexing flow)
        └→ Return tool_id + generated/provided params in response
```

---

## 7. Edge Cases and Handling

### 7.1 Unsupported Mustache Features

| Feature | Handling |
|---|---|
| `{{=<% %>=}}` set delimiter | The mustache.java parser handles this transparently — variables in custom-delimiter regions are still parsed into `ValueCode` nodes |
| `{{>partial}}` partials | Not supported in OpenSearch search templates. If encountered, ignore (no params to extract) |
| `{{<parent}}` inheritance | Not practically usable in search templates. Ignore |
| Lambdas | Cannot be passed via JSON params. Not applicable |

### 7.2 Dot Notation

`{{user.email}}` — the AST gives us `name="user.email"`. We split on `.` and take the first segment (`user`) as the root parameter name. The full path is preserved as metadata for description generation.

### 7.3 Implicit Iterator

`{{.}}` inside a loop — filtered out during AST walking. It's never a root parameter.

### 7.4 Nested Object Sections

```mustache
{{#user}}{{name}} {{email}}{{/user}}
```

`name` and `email` appear at scope depth 1 inside the `user` section. They are classified as **contextual** — they may be fields on the `user` object, not root parameters. We include `user` as a root parameter (the section controller) but mark `name` and `email` as contextual with a note.

For Tier 2, the LLM can use template context to determine whether these are root params or object fields.

### 7.5 Boolean Flag Sections

```mustache
{{#include_highlights}},"highlight":{"fields":{"title":{}}}{{/include_highlights}}
```

`include_highlights` only appears as a section controller, never as `{{include_highlights}}`. The AST walker detects this pattern and classifies it as an optional boolean parameter with type `boolean`.

### 7.6 Template Source Format

`StoredScriptSource.getSource()` always returns a `String`, regardless of whether the template was created with `"source": "..."` (string) or `"source": {...}` (JSON object). OpenSearch serializes JSON objects to strings at storage time. Our extraction always receives a string.

---

## 8. Design Decisions

### 8.1 AST Walking vs. Regex

**Decision:** Use mustache.java AST walking, not regex.

**Rationale:** Regex fails for toJson/join helpers, triple braces, section controllers, delimiter changes, and scope tracking. The AST handles all of these correctly because it uses the same parser that OpenSearch uses at runtime. Zero edge cases by definition — if the parser accepts it, we can walk it.

### 8.2 Three Tiers Instead of Two

**Decision:** Support Tier 1 (no LLM) as the default, not require `model_id` or `params`.

**Rationale:** The simplest possible creation call should work:

```json
POST /_plugins/_ml/tools/_create
{
  "name": "MyTool",
  "description": "...",
  "type": "search_template",
  "search_template_name": "my_template"
}
```

This is the minimum viable custom tool. Programmatic extraction gives functional (if not beautiful) params with zero additional dependencies. Users who want better descriptions provide `model_id`. Users who want full control provide `params`.

### 8.3 LLM Does NOT Determine Required/Optional

**Decision:** Required/optional is always determined by AST analysis, never by the LLM.

**Rationale:** Requiredness is a structural property of the template, not a semantic one. The AST tells us definitively whether a variable is inside a conditional section. The LLM could hallucinate requiredness. We never give it the chance.

### 8.4 `params` and `model_id` Are Mutually Exclusive

**Decision:** Reject requests with both `params` and `model_id`.

**Rationale:** If the user provides manual params, auto-generation is unnecessary. If they want auto-generation, manual params would be overwritten. Mutual exclusion eliminates ambiguity.

### 8.5 JSON Prompt vs. Forced Tool Call for LLM

**Decision:** Use a JSON prompt for the Tier 2 LLM call, not forced tool calls.

**Rationale:** The function calling infrastructure (`FunctionCalling` interface, `OpenaiV1ChatCompletionsFunctionCalling`, etc.) is tightly coupled to `MLChatAgentRunner` and the ReAct loop. The creation flow makes a standalone call via `MachineLearningNodeClient.predict()`. A well-structured JSON prompt is sufficient because:
- The output schema is simple (flat map of param name → type + description)
- The response is validated server-side
- This is a creation-time operation, not latency-critical
- The user reviews and corrects via `PUT`

---

## 9. Implementation Notes

### 9.1 New Dependency

```gradle
// ml-algorithms/build.gradle
implementation "com.github.spullara.mustache.java:compiler:0.9.14"
```

This is the same version OpenSearch core uses. No version conflict.

### 9.2 Key Classes to Create

| Class | Location | Purpose |
|---|---|---|
| `MustacheTemplateAnalyzer` | `ml-algorithms/.../engine/tools/` | Compiles template, walks AST, returns `AnalysisResult` |
| `AnalysisResult` | Same package | Holds extracted params with names, types, descriptions, requiredness |
| `ParamAutoGenerator` | Same package | Orchestrates Tier 1/2 flow, calls analyzer + optionally LLM |

### 9.3 Modified Classes

| Class | Change |
|---|---|
| `CreateCustomToolTransportAction` | Add Tier 1/2/3 branching logic after template validation |
| `MLCustomToolInput` | Add optional `model_id` field |

### 9.4 Code Size Estimate

- `MustacheTemplateAnalyzer`: ~150 lines (compile + recursive walk + classification)
- `ParamAutoGenerator`: ~100 lines (tier routing + LLM prompt building + response parsing)
- `CreateCustomToolTransportAction` changes: ~30 lines (branching logic)

---

## 10. Appendix: Complete Mustache Syntax Reference for OpenSearch

For the full catalog of all 19 Mustache syntax patterns, their OpenSearch behavior, and extraction assessment, see [MUSTACHE_RESEARCH.md](MUSTACHE_RESEARCH.md).

For the deep dive into OpenSearch core's Mustache infrastructure (CustomMustacheFactory, MustacheScriptEngine, Code hierarchy, compilation chain), see [MUSTACHE_CORE_DEEP_DIVE.md](MUSTACHE_CORE_DEEP_DIVE.md).
