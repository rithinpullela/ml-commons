# Mustache Template Syntax in OpenSearch Search Templates — Comprehensive Research

## Purpose

This document catalogs every Mustache syntax feature that can appear in OpenSearch search templates (stored scripts with `"lang": "mustache"`). For each feature, we assess whether our regex-based parameter extraction approach can handle it, and identify blockers.

**Target regex**: `\{\{[^#/^!>].*?\}\}` (matches `{{...}}` tags that are not section openers, closers, inverted sections, comments, or partials).

---

## 1. Standard Mustache Syntax Features

### 1.1 Basic Variable Interpolation — `{{variable}}`

**What it does**: Replaces the tag with the value of `variable` from the provided params. HTML-escapes the output by default. In OpenSearch's context, the `JsonEscapeEncoder` applies JSON string escaping instead of HTML escaping.

**OpenSearch search template example**:
```json
{
  "script": {
    "lang": "mustache",
    "source": "{\"query\":{\"match\":{\"title\":\"{{query_text}}\"}},\"size\":{{result_size}}}"
  }
}
```

**Regex extraction assessment**:
- Extracted correctly by `\{\{[^#/^!>].*?\}\}` — matches `{{query_text}}` and `{{result_size}}`
- Variable name: strip `{{` and `}}` then trim
- No issues

---

### 1.2 Unescaped Variable — `{{{variable}}}` (Triple Braces)

**What it does**: Outputs the value without any escaping (no JSON string escaping). In OpenSearch's `CustomMustacheFactory`, this bypasses the `JsonEscapeEncoder` and writes the raw value.

**When used**: When you need to inject raw JSON (an object or array) directly into the template without the value being quoted/escaped.

**OpenSearch search template example**:
```json
{
  "source": "{\"query\": {{{my_query}}}}"
}
// With params: { "my_query": {"match_all": {}} }
// Renders:     {"query": {"match_all": {}}}
```

**Regex extraction assessment**:
- **PROBLEM**: The regex `\{\{[^#/^!>].*?\}\}` will match `{{{variable}}}` but will capture it as `{{variable}}` with a trailing `}` left over, or may match `{variable}` as the inner content depending on greedy/lazy behavior.
- To extract the variable name, you need to handle triple braces as a special case: strip `{{{` and `}}}`.
- **Impact**: Variable name extraction works if you normalize by stripping all leading `{` and trailing `}` characters. The variable itself is still a parameter. Medium risk.

---

### 1.3 Unescaped Variable (Alternate) — `{{&variable}}`

**What it does**: Same as triple braces — outputs without escaping. The `&` prefix is an alternative syntax from the Mustache spec, useful when delimiter changes make triple braces awkward.

**OpenSearch search template example**:
```json
{
  "source": "{\"query\": {{&my_query}}}"
}
```

**Regex extraction assessment**:
- The regex matches `{{&my_query}}` — the `&` character is not in the exclusion set `[^#/^!>]`.
- **PROBLEM**: The extracted "variable name" will be `&my_query`. You must strip the leading `&` to get the actual parameter name.
- Low risk — easy to handle in post-processing.

---

### 1.4 Sections (Conditionals/Loops) — `{{#section}}...{{/section}}`

**What it does**: Depending on the value of `section`:
- **Falsy value or empty list**: The block is skipped entirely (acts as conditional)
- **Non-empty list/array**: The block is rendered once per item (acts as loop)
- **Truthy non-list value**: The block is rendered once with `section` as context (acts as conditional with context)

This is the most critical feature for search templates because it enables **optional clauses**.

**OpenSearch search template example — Conditional**:
```json
{
  "source": "{\"query\":{\"bool\":{\"must\":[{\"match\":{\"title\":\"{{query_text}}\"}}]{{#genre}},\"filter\":[{\"term\":{\"genre\":\"{{genre}}\"}}]{{/genre}}}}}"
}
// genre provided:     → filter clause included
// genre not provided: → filter clause omitted
```

**OpenSearch search template example — Loop (array iteration)**:
```json
{
  "source": "{\"fields\":[{{#text_fields}}\"{{field_name}}\"{{^last}},{{/last}}{{/text_fields}}]}"
}
// With params: { "text_fields": [{"field_name": "title", "last": false}, {"field_name": "body", "last": true}] }
```

**Regex extraction assessment**:
- The regex **correctly excludes** `{{#section}}` and `{{/section}}` tags (due to `[^#/^!>]` exclusion).
- **However**, `section` is itself a parameter name that must be tracked for required/optional analysis.
- The section name must be extracted separately with a different regex like `\{\{#(\w+)\}\}`.
- **Critical for required/optional**: A variable that appears ONLY inside `{{#x}}...{{/x}}` is optional (because the block is skipped when `x` is absent). A variable outside any section is required.

---

### 1.5 Inverted Sections — `{{^section}}...{{/section}}`

**What it does**: Renders the block only when the value is falsy (null, false, empty list, or absent). This is the "else" branch.

**OpenSearch search template example — Default values**:
```json
{
  "source": "{\"from\":\"{{from}}{{^from}}0{{/from}}\",\"size\":\"{{size}}{{^size}}10{{/size}}\"}"
}
// from not provided: renders "from": "0"
// from = 5:          renders "from": "5"
```

**OpenSearch search template example — If/else pattern**:
```json
{
  "source": "{\"range\":{\"@timestamp\":{\"gte\":{{#year_scope}}\"now-1y/d\"{{/year_scope}}{{^year_scope}}\"now-1d/d\"{{/year_scope}}}}}"
}
```

**Regex extraction assessment**:
- The regex **correctly excludes** `{{^section}}` tags (the `^` is in the exclusion set).
- **Critical for required/optional**: A variable with a default via `{{^var}}default{{/var}}` is optional.
- The inverted section name must be extracted separately, e.g., `\{\{\^(\w+)\}\}`.
- The closing tag `{{/section}}` uses `/` which is also excluded — correct.

---

### 1.6 Comments — `{{!comment}}`

**What it does**: Ignored during rendering. Can contain any text, including newlines.

**OpenSearch search template example**:
```json
{
  "source": "{\"query\":{\"match\":{\"title\":\"{{query_text}}\"}}{{! This is a comment that won't appear in output}}}"
}
```

**Regex extraction assessment**:
- The regex **correctly excludes** comments (the `!` is in the exclusion set).
- No false positives from comments.

---

### 1.7 Partials — `{{>partial}}`

**What it does**: In standard Mustache, includes another template by name. The included template inherits the calling context.

**OpenSearch search template status**: **NOT SUPPORTED**. Both Elasticsearch and OpenSearch explicitly do not support Mustache partials in search templates. The `lang-mustache` module does not register partial resolvers.

**Regex extraction assessment**:
- The regex **correctly excludes** partials (the `>` is in the exclusion set).
- Since partials are unsupported, this is a non-issue.

---

### 1.8 Implicit Iterator — `{{.}}`

**What it does**: Refers to the current element in a loop context. When iterating over a list of primitives (strings, numbers), `{{.}}` outputs the current item.

**OpenSearch search template example**:
```json
{
  "source": "{\"query\":{\"terms\":{\"tags\":[{{#tags}}\"{{.}}\"{{^last}},{{/last}}{{/tags}}]}}}"
}
// With params: { "tags": ["prod", "es01"] }
```

**Regex extraction assessment**:
- The regex matches `{{.}}` — the `.` is not in the exclusion set.
- **PROBLEM**: `{{.}}` is NOT a parameter name. It's a context reference. It should be **excluded** from the extracted parameter list.
- **Impact**: False positive. The regex will extract `.` as if it were a variable name. Must be filtered out in post-processing.

---

### 1.9 Dot Notation / Nested Access — `{{object.property}}`

**What it does**: Accesses nested properties. `{{client.name}}` first resolves `client` in the context, then accesses `.name` on the result.

**OpenSearch search template example**:
```json
{
  "source": "{\"query\":{\"match\":{\"email\":\"{{user.email}}\"}}}"
}
// With params: { "user": { "email": "john@example.com" } }
```

**Regex extraction assessment**:
- The regex matches `{{user.email}}`.
- **PROBLEM**: The extracted name is `user.email`, but the actual top-level parameter is `user` (an object). The regex cannot distinguish between:
  - A flat parameter named `user.email` (unlikely in search templates)
  - A nested access on the `user` object parameter
- **Impact**: For our use case (extracting parameter names for the tool schema), the top-level parameter name would be `user`, not `user.email`. This requires splitting on `.` and taking the first segment.
- Dot notation is uncommon in search templates (users typically pass flat params), but it's a potential edge case.

---

### 1.10 Set Delimiter — `{{=<% %>=}}`

**What it does**: Changes the Mustache tag delimiters from `{{ }}` to custom strings. Useful when the template content contains literal `{{` characters (e.g., in JSON contexts).

**OpenSearch search template example**:
```json
{
  "source": "{{=<% %>=}}{\"query\":{\"match\":{\"message\":\"<%query_string%>\"}}}<%={{ }}=%>"
}
```

**Regex extraction assessment**:
- **MAJOR BLOCKER**: If a template uses custom delimiters, our regex `\{\{...\}\}` will **completely miss** all variables defined within the custom delimiter region.
- The regex would match the `{{=<% %>=}}` tag itself, which is not a variable.
- **Impact**: Any template using set-delimiter syntax will produce incorrect results — both false positives (the delimiter-change tag) and false negatives (variables using custom delimiters).
- **Mitigation**: This syntax is rare in practice. Most search templates use `{{ }}` throughout. Could detect `{{=` and reject/warn.

---

### 1.11 Lambdas

**What it does**: In standard Mustache, if a variable's value is a callable (function/lambda), the function is invoked. Section lambdas receive the raw block text.

**OpenSearch search template status**: **NOT APPLICABLE**. Search template parameters come from JSON `params` — there is no way to pass a lambda/function via JSON. Lambdas cannot appear in search template usage.

**Regex extraction assessment**: Not a concern.

---

### 1.12 Blocks / Inheritance — `{{$block}}...{{/block}}` and `{{<parent}}...{{/parent}}`

**What it does**: Optional Mustache spec extension for template inheritance. Allows defining overridable blocks.

**OpenSearch search template status**: **NOT SUPPORTED**. The OpenSearch `CustomMustacheFactory` does not implement the inheritance extension. These tags will not work in search templates.

**Regex extraction assessment**:
- The regex would match `{{$block}}` (the `$` is not in the exclusion set) — potential false positive.
- Since these aren't supported, they shouldn't appear in practice. Non-issue.

---

## 2. OpenSearch-Specific Mustache Features

OpenSearch's `CustomMustacheFactory` (in the `lang-mustache` module) registers several helpers that extend standard Mustache. These are implemented as custom section handlers.

### 2.1 `{{#toJson}}variable{{/toJson}}`

**What it does**: Converts the parameter value to its JSON representation:
- **Arrays/Lists**: Rendered as JSON arrays — `["val1", "val2"]`
- **Maps/Objects**: Rendered as JSON objects — `{"key": "value"}`
- **Primitives**: Stringified

**When used**: When passing arrays or objects as parameters that need to be inserted as-is into the query JSON.

**OpenSearch search template example**:
```json
{
  "source": "{\"query\":{\"terms\":{\"tags\":{{#toJson}}tags{{/toJson}}}}}"
}
// With params: { "tags": ["prod", "es01"] }
// Renders:     {"query":{"terms":{"tags":["prod","es01"]}}}
```

```json
{
  "source": "{\"query\":{{#toJson}}my_query{{/toJson}}}"
}
// With params: { "my_query": {"match_all": {}} }
// Renders:     {"query":{"match_all":{}}}
```

**Regex extraction assessment**:
- The regex `\{\{[^#/^!>].*?\}\}` does NOT match `{{#toJson}}` (excluded by `#`) or `{{/toJson}}` (excluded by `/`).
- **However**, the variable name `tags` appears as plain text between the section tags, not as a `{{tags}}` tag. It will NOT be matched by the regex.
- **PROBLEM**: Variables used only with `{{#toJson}}varname{{/toJson}}` will be **completely missed** by the variable-extraction regex.
- **Impact**: MAJOR — this is a common pattern. The inner content is a raw variable name, not wrapped in `{{ }}`.
- **Mitigation**: Need a separate regex to extract content from `{{#toJson}}...{{/toJson}}` blocks: `\{\{#toJson\}\}(\w+)\{\{/toJson\}\}`.

---

### 2.2 `{{#join}}variable{{/join}}`

**What it does**: Concatenates array values into a single comma-delimited string.

**OpenSearch search template example**:
```json
{
  "source": "{\"query\":{\"match\":{\"emails\":\"{{#join}}emails{{/join}}\"}}}"
}
// With params: { "emails": ["a@b.com", "c@d.com"] }
// Renders:     {"query":{"match":{"emails":"a@b.com,c@d.com"}}}
```

**Custom delimiter**:
```json
{
  "source": "{\"format\":\"{{#join delimiter='||'}}date.formats{{/join delimiter='||'}}\"}"
}
// With params: { "date": { "formats": ["epoch_millis", "date_optional_time"] } }
// Renders:     {"format":"epoch_millis||date_optional_time"}
```

**Regex extraction assessment**:
- Same problem as `toJson`: the variable name is plain text between section tags, not in `{{ }}`.
- **PROBLEM**: Variables used with `{{#join}}varname{{/join}}` will be missed.
- The custom delimiter syntax adds complexity: `{{#join delimiter='||'}}date.formats{{/join delimiter='||'}}` — the variable here is `date.formats` (dot notation).
- **Mitigation**: Need a separate regex: `\{\{#join(?:\s+delimiter='[^']*')?\}\}([\w.]+)\{\{/join`.

---

### 2.3 `{{#url}}...{{/url}}`

**What it does**: URL-encodes the rendered content using UTF-8 encoding. Applies `URLEncoder.encode()`.

**OpenSearch search template example**:
```json
{
  "source": "{\"url\":\"{{#url}}{{host}}/{{page}}{{/url}}\"}"
}
// With params: { "host": "http://example.com", "page": "hello-world" }
// Renders:     {"url":"http%3A%2F%2Fexample.com%2Fhello-world"}
```

**Regex extraction assessment**:
- The `{{#url}}` and `{{/url}}` section tags are correctly excluded by the regex.
- The **inner variables** `{{host}}` and `{{page}}` ARE standard `{{ }}` tags — they WILL be matched by the regex.
- **No problem** — variables inside `{{#url}}` blocks are standard tags and are extracted normally.
- `url` itself is NOT a parameter — it's a helper function name. Since `{{#url}}` is excluded by the `#` in the regex, no false positive.

---

### 2.4 JSON Encoding (Default Behavior)

**What it does**: OpenSearch's `CustomMustacheFactory` registers `JsonEscapeEncoder` as the default encoder for content types `application/json` and `application/json; charset=UTF-8`. All `{{variable}}` output is JSON-escaped by default (quotes, backslashes, control characters are escaped).

**Impact on extraction**: None — this is a rendering behavior, not a syntax feature. Does not affect parameter name extraction.

---

### 2.5 Default Values Pattern

**What it does**: OpenSearch follows the standard Mustache inverted-section pattern for defaults. There is no special "default value" syntax beyond `{{^var}}default{{/var}}`.

**OpenSearch search template example**:
```json
{
  "source": "{\"from\":{{from}}{{^from}}0{{/from}},\"size\":{{size}}{{^size}}10{{/size}}}"
}
```

**Regex extraction assessment**:
- `{{from}}` and `{{size}}` are matched by the variable regex.
- `{{^from}}` and `{{/from}}` are excluded (correct).
- The presence of an inverted section `{{^var}}` signals that `var` is **optional** (has a default).
- Handled correctly.

---

## 3. Encoding Types Registered in CustomMustacheFactory

| MIME Type | Encoder | Behavior |
|---|---|---|
| `application/json; charset=UTF-8` | `JsonEscapeEncoder` | JSON-escapes string values (quotes, backslashes, control chars) |
| `application/json` | `JsonEscapeEncoder` | Same as above |
| `text/plain` | `DefaultEncoder` | No encoding — raw output |
| `application/x-www-form-urlencoded` | `UrlEncoder` | URL-encodes values |

These affect rendering behavior only, not template syntax or parameter extraction.

---

## 4. Edge Cases and Complex Patterns

### 4.1 Section Used as JSON Key for Conditional Inclusion

A pattern seen in OpenSearch templates where the section tag wraps a JSON key-value pair:

```json
{
  "source": {
    "query": {
      "bool": {
        "filter": [
          { "{{#color}}": { "term": { "color": "{{color}}" } } },
          { "{{#price_max}}": { "range": { "price": { "lte": "{{price_max}}" } } } }
        ]
      }
    }
  }
}
```

**Note**: This pattern uses the `source` as a JSON object (not a string). OpenSearch serializes it to a string internally. The `{{#color}}` appears as a JSON key.

**Regex extraction assessment**:
- `{{#color}}` is excluded (correct).
- `{{color}}` inside the block is matched (correct).
- `color` appears both as a section name AND as a variable — it IS the same parameter, and it's optional (because it's inside its own conditional section).

---

### 4.2 Variable Appearing Both Inside and Outside Sections

```json
{
  "source": "{\"query\":{\"match\":{\"title\":\"{{query}}\"}},\"highlight\":{{#query}}{\"fields\":{\"title\":{}}}{{/query}}}"
}
```

Here `query` is used as a variable (`{{query}}`) outside the section and also as a section condition (`{{#query}}`). Since it appears outside a conditional section, it is **required**.

---

### 4.3 Nested Sections

```json
{
  "source": "{\"query\":{\"bool\":{\"must\":[{\"match\":{\"title\":\"{{query_text}}\"}}]{{#filters}},\"filter\":[{{#category}}{\"term\":{\"category\":\"{{category}}\"}}{{/category}}]{{/filters}}}}}"
}
```

`category` is inside both `{{#filters}}` and `{{#category}}`. It is optional (doubly nested in conditional sections).

---

### 4.4 Whitespace in Tags

Mustache allows whitespace inside tags: `{{ variable }}`, `{{ #section }}`, `{{ /section }}`.

**Regex extraction assessment**:
- `{{ variable }}` — the regex matches it, but the extracted name includes leading/trailing spaces. Must trim.
- `{{ #section }}` — the space before `#` means the first character is a space, NOT `#`. The regex `[^#/^!>]` sees a space and MATCHES this as a variable.
- **PROBLEM**: `{{ #section }}` with leading space would be a false positive — extracted as `#section` (a variable) instead of being excluded as a section opener.
- **Impact**: Low risk — templates stored as JSON strings rarely have spaces in tags. But if someone writes them, the regex breaks.

---

### 4.5 The `last` Virtual Variable in Loops

When iterating arrays with sections, a common pattern uses `{{^last}}` for comma handling:

```json
"[{{#items}}\"{{.}}\"{{^last}},{{/last}}{{/items}}]"
```

The `last` variable is NOT a user parameter — it is typically expected to be a property on each array element. If the array items don't have a `last` property, the inverted section `{{^last}}` always renders (because `last` is always absent/falsy).

**Regex extraction assessment**: `last` won't appear as a `{{ }}` variable tag in this pattern, so no false positive from the regex. But if someone uses `{{last}}` elsewhere, it could be a false positive if it's not a real parameter.

---

## 5. Summary Table

| # | Syntax Pattern | Example | Regex Matches? | Extracts Correctly? | Impact on Required/Optional | Risk Level | Notes |
|---|---|---|---|---|---|---|---|
| 1 | `{{variable}}` | `{{query_text}}` | Yes | Yes — name is `query_text` | Required if outside sections | None | Core case |
| 2 | `{{{variable}}}` | `{{{my_query}}}` | Partial | Needs normalization — strip extra braces | Same as basic variable | Low | Strip all leading `{` and trailing `}` |
| 3 | `{{&variable}}` | `{{&my_query}}` | Yes | Needs post-processing — strip `&` | Same as basic variable | Low | Strip leading `&` |
| 4 | `{{#section}}` | `{{#genre}}` | No (excluded) | N/A | Marks block as conditional | None | Extract separately for optional analysis |
| 5 | `{{/section}}` | `{{/genre}}` | No (excluded) | N/A | Closes conditional block | None | Extract separately |
| 6 | `{{^section}}` | `{{^from}}` | No (excluded) | N/A | Marks variable as having default (optional) | None | Extract separately |
| 7 | `{{!comment}}` | `{{! note }}` | No (excluded) | N/A | No impact | None | Correctly ignored |
| 8 | `{{>partial}}` | `{{>box}}` | No (excluded) | N/A | N/A — not supported in OpenSearch | None | Not supported |
| 9 | `{{.}}` | `{{.}}` | Yes | **False positive** — not a parameter | N/A | Low | Filter out `.` in post-processing |
| 10 | `{{obj.prop}}` | `{{user.email}}` | Yes | Extracts `user.email` — top-level param is `user` | Same rules apply to top-level param | Medium | Split on `.`, take first segment |
| 11 | `{{=<% %>=}}` | `{{=<% %>=}}` | Yes (false positive) | **Breaks extraction** for entire region | All variables in custom-delimiter region missed | **HIGH** | Detect and reject/warn |
| 12 | `{{#toJson}}var{{/toJson}}` | `{{#toJson}}tags{{/toJson}}` | No — inner var is plain text | **MISSED** — variable not in `{{ }}` | Typically optional (inside toJson section) | **HIGH** | Need separate regex for toJson content |
| 13 | `{{#join}}var{{/join}}` | `{{#join}}emails{{/join}}` | No — inner var is plain text | **MISSED** — variable not in `{{ }}` | Typically optional (inside join section) | **HIGH** | Need separate regex for join content |
| 14 | `{{#join delimiter='x'}}var{{/join ...}}` | `{{#join delimiter='\|\|'}}fmts{{/join ...}}` | No — inner var is plain text | **MISSED** | Same as join | **HIGH** | More complex regex needed |
| 15 | `{{#url}}{{var}}{{/url}}` | `{{#url}}{{host}}{{/url}}` | Yes (inner `{{var}}` tags) | Yes — inner vars extracted normally | Variables may be required | None | url block is transparent |
| 16 | `{{ variable }}` (spaces) | `{{ query }}` | Yes | Needs trimming | Same as basic | Low | Trim whitespace |
| 17 | `{{ #section }}` (space before `#`) | `{{ #genre }}` | **Yes (false positive)** | Extracted as ` #genre` — wrong | Misidentified as variable | Medium | Rare in JSON-string templates |
| 18 | Lambdas | N/A | N/A | N/A | N/A | None | Cannot pass lambdas via JSON params |
| 19 | `{{$block}}` | `{{$title}}` | Yes (false positive) | Would extract `$title` | N/A — not supported | None | Not supported in OpenSearch |

---

## 6. Blockers for Regex-Based Extraction

### HIGH-Risk Blockers

1. **`{{#toJson}}variable{{/toJson}}`** — The variable name is plain text between section tags, not wrapped in `{{ }}`. The standard variable regex completely misses these. This is a **commonly used pattern** for arrays and objects in search templates.

2. **`{{#join}}variable{{/join}}`** — Same issue as toJson. The variable name is bare text inside the helper section.

3. **`{{=<% %>=}}` (Set Delimiter)** — If used, all variables in the custom-delimiter region are invisible to the `{{ }}` regex. The delimiter-change tag itself is a false positive.

### MEDIUM-Risk Issues

4. **Dot notation `{{obj.prop}}`** — Extracts the full dotted path rather than the top-level parameter name. Needs splitting on `.`.

5. **Whitespace in tags `{{ #section }}`** — Space before `#` defeats the exclusion check. Rare in practice but possible.

### LOW-Risk Issues

6. **Triple braces `{{{var}}}`** — Needs normalization to strip extra braces.

7. **Ampersand syntax `{{&var}}`** — Needs stripping of `&` prefix.

8. **Implicit iterator `{{.}}`** — False positive that needs filtering.

---

## 7. Recommended Regex Strategy

A single regex is insufficient. The extraction should use multiple passes:

### Pass 1: Extract standard variables
```regex
\{\{2,3\}([^#/^!>=&\s][^}]*?)\}{2,3}
```
Handles `{{var}}`, `{{{var}}}`, and avoids section/comment/partial tags.
Post-processing: trim whitespace, normalize by stripping extra braces.

### Pass 2: Extract toJson variables
```regex
\{\{#toJson\}\}\s*([\w.]+)\s*\{\{/toJson\}\}
```
Captures the bare variable name inside toJson helper blocks.

### Pass 3: Extract join variables
```regex
\{\{#join(?:\s+delimiter='[^']*')?\}\}\s*([\w.]+)\s*\{\{/join(?:\s+delimiter='[^']*')?\}\}
```
Captures the bare variable name inside join helper blocks.

### Pass 4: Extract section names (for required/optional analysis)
```regex
\{\{#(\w+)\}\}
```
Captures section openers. Must exclude helper names: `toJson`, `join`, `url`.

### Pass 5: Extract inverted section names (for default/optional detection)
```regex
\{\{\^(\w+)\}\}
```
Captures inverted section openers. Variables with matching inverted sections are optional.

### Pass 6: Detect set-delimiter (blocker detection)
```regex
\{\{=.+?=\}\}
```
If matched, warn that the template uses custom delimiters and cannot be fully parsed.

---

## 8. Required/Optional Determination Logic

Given the extracted data from the passes above:

1. **Build a section nesting map**: Track which variables appear inside which `{{#section}}...{{/section}}` blocks.

2. **Rules**:
   - A variable that appears **outside all conditional sections** is **required**.
   - A variable that appears **only inside** `{{#sameName}}...{{/sameName}}` (where the section name matches the variable name) is **optional** — the section guards its usage.
   - A variable that appears **only inside** `{{#otherName}}...{{/otherName}}` (different section name) is **conditionally required** — required when `otherName` is provided, but the block may be skipped entirely.
   - A variable with an inverted section default `{{var}}{{^var}}default{{/var}}` is **optional**.
   - Variables extracted from `{{#toJson}}var{{/toJson}}` — the `toJson` section itself doesn't make the variable optional (it's a helper, not a conditional). However, if the `toJson` block is nested inside another conditional section, that outer section determines optionality.
   - Similarly, `{{#join}}var{{/join}}` — the join helper itself doesn't affect optionality.

3. **Helper function names to exclude from parameter lists**: `toJson`, `join`, `url`. These are function names, not parameter names.

---

## 9. Real-World Template Patterns in This Codebase

### From `RestGenerativeSearchResponseIT.java`:
```
{"query":{"match":{"text":"{{query_text}}"}},
 "ext":{"generative_qa_parameters":{
   "llm_model":"{{llm_model}}",
   "llm_question":"{{llm_question}}",
   "context_size":{{context_size}},
   "message_size":{{message_size}},
   "timeout":{{timeout}}
 }}}
```
All variables are basic `{{var}}` — no sections, no helpers. All required.

### From `CUSTOM_TOOLS_TEST_GUIDE.md`:
```
{"query":{"bool":{"must":[{"match":{"title":"{{query_text}}"}}]
 {{#genre}},"filter":[{"term":{"genre":"{{genre}}"}}]{{/genre}}}},
 "size":{{result_size}}}
```
Uses conditional section `{{#genre}}...{{/genre}}`. Variables: `query_text` (required), `genre` (optional), `result_size` (required).

### From `V2_RESTRUCTURED.md`:
```json
{
  "query": {
    "bool": {
      "must": [{ "match": { "category": "{{category}}" } }],
      "filter": [
        { "{{#color}}": { "term": { "color": "{{color}}" } } },
        { "{{#price_max}}": { "range": { "price": { "lte": "{{price_max}}" } } } }
      ]
    }
  }
}
```
Uses sections as JSON keys for conditional filter clauses. `category` (required), `color` (optional), `price_max` (optional).

---

## 10. Conclusions

1. **A single regex is insufficient** for complete parameter extraction from Mustache search templates. The `{{#toJson}}` and `{{#join}}` helpers are commonly used and place variable names as plain text between section tags.

2. **A multi-pass approach** (section 7) can handle all common patterns with high reliability.

3. **Set-delimiter syntax `{{=...=}}`** is the only true hard blocker — it makes the template unparseable by fixed-pattern regex. It should be detected and flagged rather than silently producing wrong results. Fortunately, it is rarely used in practice.

4. **Required/optional determination** is well-suited to regex-based section nesting analysis for the common patterns (self-guarding sections like `{{#var}}...{{var}}...{{/var}}`), but **nested sections with different names** create ambiguity (conditionally required parameters).

5. **The programmatic Phase 1 extraction described in the design doc is sound** for the vast majority of real-world search templates. The main gaps to address are: toJson/join helper content extraction and set-delimiter detection.
