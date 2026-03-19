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

```mustache
{
  "query": {
    "terms": {
      "tags": {{#toJson}}tags{{/toJson}}
    }
  }
}
```

The regex sees `{{#toJson}}` (excluded by `#`) and `{{/toJson}}` (excluded by `/`), but **completely misses `tags`** — the actual parameter. This is one of the most commonly used patterns for array parameters.

The `join` helper has the same problem:

```mustache
{
  "query": {
    "match": {
      "emails": "{{#join}}emails{{/join}}"
    }
  }
}
```

#### Blocker 2: `{{{variable}}}` — Triple Braces (Unescaped Output)

Triple braces mean "inject raw JSON without escaping." Used when passing entire JSON objects/arrays:

```mustache
{
  "query": {{{my_query}}}
}
```

The regex matches `{{{my_query}}}` but captures it incorrectly — the greedy/lazy behavior produces `{my_query}` or leaves a trailing `}`. The variable name extraction is unreliable.

#### Blocker 3: `{{#section}}...{{/section}}` — Section Controllers Are Parameters Too

Sections are conditionals. The section controller variable (e.g., `genre` in `{{#genre}}...{{/genre}}`) is itself a parameter — it determines whether the block renders. But the regex **excludes all `{{#...}}` tags**, so section-only parameters are invisible:

```mustache
{
  "query": {
    "bool": {
      "must": [
        { "match": { "title": "{{query}}" } }
      ]
      {{#genre}},
      "filter": [
        { "term": { "genre": "{{genre}}" } }
      ]
      {{/genre}}
    }
  }
}
```

Here `genre` appears both as a section controller AND as a variable inside the section. If it only appeared as a controller (common in boolean flag patterns like `{{#include_highlights}}...{{/include_highlights}}`), the regex would miss it entirely.

#### Blocker 4: `{{=<% %>=}}` — Delimiter Changes

Mustache allows changing tag delimiters. After `{{=<% %>=}}`, all variables use `<% %>` syntax:

```mustache
{{=<% %>=}}
{
  "query": {
    "match": {
      "title": "<% query %>"
    }
  }
}
```

The regex sees `{{=<% %>=}}` as a false positive, then **misses every subsequent variable** because they no longer use `{{ }}`.

#### Blocker 5: Scope Ambiguity in Nested Sections

Variables inside sections may refer to object fields, not root parameters:

```mustache
{{#user}}
{
  "name": "{{name}}",
  "email": "{{email}}"
}
{{/user}}
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

### 4.3 Walkthrough: Product Search Template

**Template:**

```mustache
{
  "query": {
    "bool": {
      "must": [
        { "match": { "title": "{{query_text}}" } }
      ]
      {{#category}},
      "filter": [
        { "term": { "category.keyword": "{{category}}" } }
      ]
      {{/category}}
    }
  },
  "from": {{from}}{{^from}}0{{/from}},
  "size": {{size}}{{^size}}10{{/size}}
}
```

**AST after compilation:**

```
DefaultMustache (root)
├── WriteCode: '{"query":{"bool":{"must":[{"match":{"title":"'
├── ValueCode: name="query_text"                                    ← scope 0
├── WriteCode: '"}}]'
├── IterableCode: name="category"                                   ← scope 0, section controller
│   ├── WriteCode: ',"filter":[{"term":{"category.keyword":"'
│   ├── ValueCode: name="category"                                  ← scope 1, inside {{#category}}
│   └── WriteCode: '"}}]'
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

| Param | How discovered | Scope | Classification |
|---|---|---|---|
| `query_text` | `ValueCode` at scope 0 | Root | **required** — root scope, no fallback |
| `category` | `IterableCode` controller at scope 0 + `ValueCode` at scope 1 inside own section | Self-guarding | **optional** — only appears inside `{{#category}}` |
| `from` | `ValueCode` at scope 0 + `NotIterableCode` with default `0` | Root + default | **optional** — has inverted section default |
| `size` | `ValueCode` at scope 0 + `NotIterableCode` with default `10` | Root + default | **optional** — has inverted section default |

### 4.4 Walkthrough: Template with toJson and Defaults

**Template:**

```mustache
{
  "query": {
    "bool": {
      "must": [
        { "terms": { "tags": {{#toJson}}tags{{/toJson}} } }
      ]
      {{#author}},
      "filter": [
        { "term": { "author": "{{author}}" } }
      ]
      {{/author}}
    }
  },
  "size": {{size}}{{^size}}10{{/size}}
}
```

**AST after compilation:**

```
DefaultMustache (root)
├── WriteCode: '{"query":{"bool":{"must":[{"terms":{"tags":'
├── ToJsonCode: name="tags"                                         ← scope 0, helper
├── WriteCode: '}}]'
├── IterableCode: name="author"                                     ← scope 0, section controller
│   ├── WriteCode: ',"filter":[{"term":{"author":"'
│   ├── ValueCode: name="author"                                    ← scope 1, inside {{#author}}
│   └── WriteCode: '"}}]'
├── WriteCode: '}},"size":'
├── ValueCode: name="size"                                          ← scope 0
├── NotIterableCode: name="size"                                    ← inverted section
│   └── WriteCode: '10'
└── WriteCode: '}'
```

**Classification:**

| Param | How discovered | Classification |
|---|---|---|
| `tags` | `ToJsonCode` (regex would have **missed** this) | **required** — root scope, no fallback, array type |
| `author` | `IterableCode` controller + `ValueCode` inside own section | **optional** — self-guarding |
| `size` | `ValueCode` + `NotIterableCode` with default `10` | **optional** — has inverted section default |

---

## 5. Three Modes of Parameter Specification

The creation API supports three mutually exclusive modes. Each successive mode adds more intelligence but also more complexity/latency.

### 5.1 Mode Overview

| Mode | What user provides | Params extracted by | Descriptions generated by | Type inferred by |
|---|---|---|---|---|
| **Mode 1: Auto (no LLM)** | Neither `params` nor `model_id` | AST walker | Programmatic defaults from DSL context | Heuristic from AST context |
| **Mode 2: Auto + LLM** | `model_id` + `llm_interface` | AST walker | LLM (via forced tool call) | Heuristic from AST (LLM does not override types) |
| **Mode 3: Manual** | `params` | User | User | User |

### 5.2 Mode 1: Programmatic Extraction with Default Descriptions

**Request:**

```json
POST /_plugins/_ml/tools/_create
{
  "name": "ProductSearch",
  "description": "Search products by title with optional category filter",
  "type": "search_template",
  "search_template_name": "product_search_v2"
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
| `ValueCode` unquoted: `"size":{{var}}` | `number` | Bare value in size/from context = numeric |
| `ToJsonCode` | `array` | toJson is used for array/object injection |
| `JoinerCode` | `array` | join concatenates list elements |
| Section controller with no inner value usage | `boolean` | Acts as a conditional guard |
| Everything else | `string` | Safe default |

To determine quoted vs. unquoted, we look at the `WriteCode` node immediately preceding the `ValueCode` in the AST. If it ends with `"`, the variable is quoted (string). If it ends with `:` or a digit context, it's unquoted (numeric).

**Actual response (tested against running cluster):**

```json
{
  "tool_id": "uaG2Bp0BXkQCQSaYzA9b",
  "params": {
    "query_text": {
      "type": "string",
      "description": "Value for the 'title' field (match)",
      "required": true
    },
    "category": {
      "type": "string",
      "description": "Value for the 'category.keyword' field (term)",
      "required": false
    },
    "from": {
      "type": "number",
      "description": "Value for the 'from' field",
      "required": false,
      "default": "0"
    },
    "size": {
      "type": "number",
      "description": "Value for the 'size' field",
      "required": false,
      "default": "10"
    }
  }
}
```

**Latency:** ~10ms (compile + walk, no network calls)

### 5.3 Mode 2: LLM-Enhanced Descriptions

**Request:**

```json
POST /_plugins/_ml/tools/_create
{
  "name": "ProductSearch",
  "description": "Search products by title with optional category filter",
  "type": "search_template",
  "search_template_name": "product_search_v2",
  "model_id": "wKG4Bp0BXkQCQSaYUQ97",
  "llm_interface": "bedrock/converse/claude"
}
```

`model_id` + `llm_interface` provided → Phase 1 (AST extraction) runs as in Mode 1, then Phase 2 calls the LLM via a forced tool call to get better descriptions.

**What happens:**

1. Steps 1-6 from Mode 1 (AST extraction, classification, heuristic types/descriptions)
2. Build a dynamic JSON Schema with one string property per parameter
3. Build a user prompt with template source + extracted variable names + their AST context
4. Configure function calling for the specified `llm_interface`
5. Force a tool call via `tool_choice: required` (Bedrock: `toolChoice: {any: {}}`)
6. Call LLM via `MLPredictionTaskAction` with the rendered tool definition
7. Parse tool call response → extract description strings per parameter
8. **Merge:** LLM provides descriptions only; required/optional, defaults, and types stay from Phase 1
9. Store and return

**Key design choice:** The LLM does not determine types or requiredness — those are structural properties from the AST. The LLM's role is limited to enriching descriptions, which is what it does best. On failure, the system falls back to Mode 1 defaults gracefully.

**Supported `llm_interface` values:**

| Value | Provider | Tool call mechanism |
|---|---|---|
| `bedrock/converse/claude` | AWS Bedrock (Claude) | `toolChoice: {any: {}}` |
| `openai/v1/chat/completions` | OpenAI-compatible | `tool_choice: "required"` |
| `gemini/v1beta/generateContent` | Google Gemini | `functionCallingConfig: {mode: "ANY"}` |

**Actual response (tested against running cluster with Bedrock Claude Sonnet):**

```json
{
  "tool_id": "wqG4Bp0BXkQCQSaYsQ_0",
  "params": {
    "query_text": {
      "type": "string",
      "description": "The text to search for in document titles using match query",
      "required": true
    },
    "category": {
      "type": "string",
      "description": "Optional category filter to restrict results to documents with a specific category value",
      "required": false
    },
    "from": {
      "type": "number",
      "description": "The starting offset for pagination, determining which result to begin returning from (defaults to 0)",
      "required": false,
      "default": "0"
    },
    "size": {
      "type": "number",
      "description": "The maximum number of search results to return per page (defaults to 10)",
      "required": false,
      "default": "10"
    }
  }
}
```

**Latency:** ~1-3s (AST extraction + one LLM call)

**Error handling:** If the LLM returns malformed JSON, misses parameters, or the call fails entirely, the system falls back to Mode 1 defaults and logs a warning. The tool is still created successfully.

### 5.4 Mode 1 vs. Mode 2: Side-by-Side Comparison

The following tables show real outputs from the running cluster for all four test templates.

#### Product Search (`product_search_v2`)

| Param | Mode 1 Description | Mode 2 Description (LLM) |
|---|---|---|
| `query_text` | Value for the 'title' field (match) | The text to search for in document titles using match query |
| `category` | Value for the 'category.keyword' field (term) | Optional category filter to restrict results to documents with a specific category value |
| `from` | Value for the 'from' field | The starting offset for pagination, determining which result to begin returning from (defaults to 0) |
| `size` | Value for the 'size' field | The maximum number of search results to return per page (defaults to 10) |

#### Log Search (`log_search_v1`)

Template:

```mustache
{
  "query": {
    "bool": {
      "must": [
        { "range": { "@timestamp": { "gte": "{{start_date}}", "lte": "{{end_date}}" } } }
      ]
      {{#level}},
      "filter": [
        { "term": { "level": "{{level}}" } }
      ]
      {{/level}}
      {{#service}},
      "must": [
        { "match": { "service": "{{service}}" } }
      ]
      {{/service}}
    }
  },
  "sort": [{ "@timestamp": { "order": "desc" } }],
  "size": {{size}}{{^size}}50{{/size}}
}
```

| Param | Mode 1 Description | Mode 2 Description (LLM) |
|---|---|---|
| `start_date` | Value for the 'gte' field (range) | The earliest timestamp to include in the search results, defining the beginning of the time range filter |
| `end_date` | Value for the 'lte' field | The latest timestamp to include in the search results, defining the end of the time range filter |
| `level` | Value for the 'level' field (term) | The log level to filter by (e.g., ERROR, WARN, INFO, DEBUG) to narrow results to specific severity levels |
| `service` | Value for the 'service' field (match) | The service name to search for in log entries, used to filter results to a specific application or component |
| `size` | Value for the 'size' field | The maximum number of search results to return, with a default limit of 50 documents |

#### Geo Search (`geo_search_v1`)

Template:

```mustache
{
  "query": {
    "bool": {
      "must": [
        {
          "geo_distance": {
            "distance": "{{radius}}{{^radius}}10km{{/radius}}",
            "location": {
              "lat": {{lat}},
              "lon": {{lon}}
            }
          }
        }
      ]
      {{#type}},
      "filter": [
        { "term": { "type": "{{type}}" } }
      ]
      {{/type}}
    }
  },
  "size": {{size}}{{^size}}20{{/size}}
}
```

| Param | Type | Required | Mode 1 Description | Mode 2 Description (LLM) |
|---|---|---|---|---|
| `radius` | string | false (default: 10km) | Value for the 'distance' field (must) | The search radius distance from the center point, specified with a unit (e.g., "5km", "1000m") with a default of 10km if not provided |
| `lat` | number | true | Value for the 'lat' field | The latitude coordinate of the center point for the geographical distance search |
| `lon` | number | true | Value for the 'lon' field | The longitude coordinate of the center point for the geographical distance search |
| `type` | string | false | Value for the 'type' field (term) | An optional filter to restrict results to documents matching a specific type value |
| `size` | number | false (default: 20) | Value for the 'size' field | The maximum number of search results to return, defaulting to 20 if not specified |

#### Tags Search with toJson (`tags_search_v1`)

Template:

```mustache
{
  "query": {
    "bool": {
      "must": [
        { "terms": { "tags": {{#toJson}}tags{{/toJson}} } }
      ]
      {{#author}},
      "filter": [
        { "term": { "author": "{{author}}" } }
      ]
      {{/author}}
    }
  },
  "size": {{size}}{{^size}}10{{/size}}
}
```

| Param | Type | Required | Mode 1 Description | Mode 2 Description (LLM) |
|---|---|---|---|---|
| `tags` | **array** | true | Value for 'tags' (array) | An array of tag values to filter documents that must contain any of the specified tags |
| `author` | string | false | Value for the 'author' field (term) | The specific author name to filter documents by, restricting results to only that author's content |
| `size` | number | false (default: 10) | Value for the 'size' field | The maximum number of documents to return in the search results, defaults to 10 if not specified |

Note how the `tags` parameter was correctly identified as `array` type from the `{{#toJson}}` helper — a regex-based approach would have missed this entirely.

### 5.5 Mode 3: User-Provided Params (Manual Override)

**Request:**

```json
POST /_plugins/_ml/tools/_create
{
  "name": "ProductSearch",
  "description": "Search products by title with optional category filter",
  "type": "search_template",
  "search_template_name": "product_search_v2",
  "params": {
    "query_text":  { "type": "text",    "description": "Words to match in the product title",                       "required": true  },
    "category":    { "type": "keyword", "description": "Product category filter (e.g., electronics, clothing)",     "required": false },
    "from":        { "type": "integer", "description": "Pagination offset",                                         "required": false },
    "size":        { "type": "integer", "description": "Max results per page",                                      "required": false }
  }
}
```

The user provides `params` directly. **No AST extraction, no LLM call.** The user's params are stored exactly as provided.

This is the existing behavior, unchanged. The user has full control — they can use custom types, override requiredness, add descriptions that reference their domain, etc.

**Actual response (tested against running cluster):**

```json
{
  "tool_id": "xqG5Bp0BXkQCQSaYJA9V",
  "params": {
    "query_text": {
      "type": "text",
      "description": "Words to match in the product title",
      "required": true
    },
    "category": {
      "type": "keyword",
      "description": "Product category filter (e.g., electronics, clothing)",
      "required": false
    },
    "from": {
      "type": "integer",
      "description": "Pagination offset",
      "required": false
    },
    "size": {
      "type": "integer",
      "description": "Max results per page",
      "required": false
    }
  }
}
```

**Latency:** ~10ms (validation + index)

### 5.6 Validation Rules

| Fields provided | Behavior |
|---|---|
| Neither `params` nor `model_id` | **Mode 1** — auto-extract with programmatic defaults |
| `model_id` + `llm_interface` | **Mode 2** — auto-extract + LLM enrichment |
| `model_id` without `llm_interface` | **Error:** `'llm_interface' is required when 'model_id' is provided` |
| `params` only | **Mode 3** — manual, store as-is |
| Both `params` and `model_id` | **Error:** `Cannot specify both 'params' and 'model_id'` |

### 5.7 Updates via PUT

After creation (any mode), the user can update params via `PUT /_plugins/_ml/tools/{tool_id}`:

```json
PUT /_plugins/_ml/tools/{tool_id}
{
  "params": {
    "result_size": { "description": "Number of results (max 100)", "required": false }
  }
}
```

This is the correction path for Mode 1 (fix bad defaults) and Mode 2 (fix LLM mistakes). For Mode 3, the user already provided the params they wanted.

---

## 6. Creation Flow

```
POST /_plugins/_ml/tools/_create
  │
  ├→ 1. Standard validation (tenant, name uniqueness, name format)
  │
  ├→ 2. Check what's provided: params? model_id? neither?
  │     ├→ Both present → Error
  │     ├→ params present → Mode 3 (skip to step 6)
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
  ├→ 5. Phase 2: LLM enhancement (only if model_id provided — Mode 2)
  │     ├→ Validate llm_interface is provided
  │     ├→ Build dynamic JSON Schema (one string property per param)
  │     ├→ Configure function calling for the llm_interface
  │     ├→ Force tool call (tool_choice: required / toolChoice: {any: {}})
  │     ├→ Call model via MLPredictionTaskAction
  │     ├→ Parse tool call response → extract descriptions
  │     ├→ Merge: LLM descriptions replace AST defaults; type/required/default stay from AST
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
{{#user}}
{
  "name": "{{name}}",
  "email": "{{email}}"
}
{{/user}}
```

`name` and `email` appear at scope depth 1 inside the `user` section. They are classified as **contextual** — they may be fields on the `user` object, not root parameters. We include `user` as a root parameter (the section controller) but mark `name` and `email` as contextual with a note.

For Mode 2, the LLM can use template context to determine whether these are root params or object fields.

### 7.5 Boolean Flag Sections

```mustache
{{#include_highlights}},
"highlight": {
  "fields": { "title": {} }
}
{{/include_highlights}}
```

`include_highlights` only appears as a section controller, never as `{{include_highlights}}`. The AST walker detects this pattern and classifies it as an optional boolean parameter with type `boolean`.

### 7.6 Template Source Format

`StoredScriptSource.getSource()` always returns a `String`, regardless of whether the template was created with `"source": "..."` (string) or `"source": {...}` (JSON object). OpenSearch serializes JSON objects to strings at storage time. Our extraction always receives a string.

---

## 8. Design Decisions

### 8.1 AST Walking vs. Regex

**Decision:** Use mustache.java AST walking, not regex.

**Rationale:** Regex fails for toJson/join helpers, triple braces, section controllers, delimiter changes, and scope tracking. The AST handles all of these correctly because it uses the same parser that OpenSearch uses at runtime. Zero edge cases by definition — if the parser accepts it, we can walk it.

### 8.2 Three Modes Instead of Two

**Decision:** Support Mode 1 (no LLM) as the default, not require `model_id` or `params`.

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

### 8.3 LLM Does NOT Determine Required/Optional or Types

**Decision:** Required/optional and types are always determined by AST analysis, never by the LLM.

**Rationale:** Requiredness is a structural property of the template, not a semantic one. The AST tells us definitively whether a variable is inside a conditional section. Similarly, types are inferred from the surrounding DSL context (quoted = string, unquoted = number, toJson = array). The LLM could hallucinate either. We never give it the chance — it only enhances descriptions.

### 8.4 `params` and `model_id` Are Mutually Exclusive

**Decision:** Reject requests with both `params` and `model_id`.

**Rationale:** If the user provides manual params, auto-generation is unnecessary. If they want auto-generation, manual params would be overwritten. Mutual exclusion eliminates ambiguity.

### 8.5 Forced Tool Call vs. JSON Prompt for LLM

**Decision:** Use forced tool calling (not free-form JSON) for the Mode 2 LLM call.

**Rationale:** Free-form JSON prompts require fragile parsing. By using the function calling infrastructure with `tool_choice: required`, the LLM is forced to return structured output matching our dynamic schema. Each extracted parameter becomes a required property in the tool's input schema, so the LLM must provide a description for every parameter — no more, no less. The response arrives as a structured tool call that can be parsed reliably.

### 8.6 `llm_interface` Required for Mode 2

**Decision:** Require `llm_interface` alongside `model_id`.

**Rationale:** Different LLM providers use different tool call formats (OpenAI, Bedrock Converse, Gemini). The `llm_interface` value configures function calling correctly — without it, we can't force a tool call or parse the response. This mirrors how agents specify their LLM interface.

---

## 9. Implementation Notes

### 9.1 New Dependency

```gradle
// ml-algorithms/build.gradle
implementation "com.github.spullara.mustache.java:compiler:0.9.14"
```

This is the same version OpenSearch core uses. No version conflict.

### 9.2 Key Classes

| Class | Location | Purpose |
|---|---|---|
| `MustacheTemplateAnalyzer` | `ml-algorithms/.../engine/tools/` | Compiles template, walks AST, returns extracted params |
| `LLMParameterEnricher` | Same package | Mode 2: builds dynamic tool schema, calls LLM, merges descriptions |
| `CreateCustomToolTransportAction` | `plugin/.../action/tools/` | Routes to Mode 1/2/3 based on input fields |
| `MLCustomToolInput` | `common/.../transport/tools/` | Request DTO with `model_id` and `llm_interface` fields |

### 9.3 Code Size

- `MustacheTemplateAnalyzer`: ~390 lines (compile + recursive walk + classification + heuristics)
- `LLMParameterEnricher`: ~290 lines (schema building + prompt + function calling + merge)
- `CreateCustomToolTransportAction` mode routing: ~60 lines

---

## 10. Appendix: Complete Mustache Syntax Reference for OpenSearch

For the full catalog of all 19 Mustache syntax patterns, their OpenSearch behavior, and extraction assessment, see [MUSTACHE_RESEARCH.md](MUSTACHE_RESEARCH.md).

For the deep dive into OpenSearch core's Mustache infrastructure (CustomMustacheFactory, MustacheScriptEngine, Code hierarchy, compilation chain), see [MUSTACHE_CORE_DEEP_DIVE.md](MUSTACHE_CORE_DEEP_DIVE.md).
