# Edge Case Test Templates for Parameter Auto-Extraction

Proposed edge case templates to stress-test `MustacheTemplateAnalyzer`. Each case targets a specific behavior in the AST walker that the existing 10 templates do not cover.

Reference implementation: `ml-algorithms/src/main/java/org/opensearch/ml/engine/tools/MustacheTemplateAnalyzer.java`

---

## e1: Triple Braces (Unescaped Output)

**What it tests:** `{{{var}}}` produces a `ValueCode` with `encoded=false`. The AST walker should extract it identically to `{{var}}`. This is the syntax for injecting raw JSON without HTML escaping.

**Template (`e1_triple_braces`):**

```mustache
{
  "query": {{{raw_query}}},
  "size": {{size}}
}
```

**Expected params:**

| Param | Type | Required | Default |
|---|---|---|---|
| `raw_query` | string | true | -- |
| `size` | number | true | -- |

**Why this matters:** The design doc calls out triple braces as a regex-breaking pattern. The AST should handle it transparently, but none of the existing 10 templates use triple braces. We need to confirm `ValueCode` with `encoded=false` is walked the same as `encoded=true`. The type for `raw_query` is interesting: the preceding text ends with `:` (after `"query":`), so `inferType` will see the `:` ending and return `number`. However, semantically it is a JSON object. This test reveals a type inference weakness -- the heuristic cannot distinguish `"query": {{{obj}}}` from `"size": {{num}}`.

---

## e2: Variable in Both Quoted and Unquoted Positions

**What it tests:** A variable that appears first in a quoted position (`"field": "{{var}}"`) and then in an unquoted position (`"boost": {{var}}`). The `handleValueCode` method only captures `precedingText` on the first encounter (due to the `info.precedingText == null` guard), so the type is determined by whichever occurrence the AST walker visits first.

**Template (`e2_quoted_and_unquoted`):**

```mustache
{
  "query": {
    "match": {
      "title": {
        "query": "{{value}}",
        "boost": {{value}}
      }
    }
  }
}
```

**Expected params:**

| Param | Type | Required | Default |
|---|---|---|---|
| `value` | string | true | -- |

**Why this matters:** The same variable name appears in two incompatible type contexts. Because the walker visits nodes in AST order (top-to-bottom, left-to-right), and `precedingText` is set only once, the type depends on which usage the AST visits first. If the quoted usage comes first, type is `string`; if unquoted comes first, type is `number`. This is an inherent limitation of "first occurrence wins" heuristics and should be documented behavior.

---

## e3: Deeply Nested Sections (3+ Levels)

**What it tests:** Variables at scope depth 3+, where the inner variable is not a root parameter but a deeply nested field. Tests that `depth` tracking is correct through multiple recursion levels and that `appearsAtRootScope` stays `false` for deeply nested variables.

**Template (`e3_deep_nesting`):**

```mustache
{
  "query": {
    "bool": {
      "must": [
        { "match": { "title": "{{query_text}}" } }
      ]
      {{#filters}},
      "filter": [
        {{#price_range}}
        {
          "range": {
            "price": {
              {{#min_price}}
              "gte": {{min_price}}
              {{/min_price}}
            }
          }
        }
        {{/price_range}}
      ]
      {{/filters}}
    }
  }
}
```

**Expected params:**

| Param | Type | Required | Default |
|---|---|---|---|
| `query_text` | string | true | -- |
| `filters` | boolean | false | -- |
| `price_range` | boolean | false | -- |
| `min_price` | number | false | -- |

**Why this matters:** `min_price` is at scope depth 3 (inside `filters` > `price_range` > `min_price`). It appears inside its own section, so `appearsInsideOwnSection` is true and it should be optional. `filters` and `price_range` are section controllers with no direct value usage (only nested sections inside them), so they should be `boolean`. This tests that the depth counter increments correctly through 3 levels of `IterableCode` recursion.

---

## e4: Variable as Both Section Controller AND Value in Different Section

**What it tests:** A variable `status` used as a section controller in one place (`{{#status}}...{{/status}}`) and as a plain value (`{{status}}`) inside a different section (`{{#filters}}`). The `appearsInsideOwnSection` flag should be set (from the first usage), but `appearsAtRootScope` might also be relevant depending on how sections interact.

**Template (`e4_section_and_value`):**

```mustache
{
  "query": {
    "bool": {
      "must": [
        { "match": { "title": "{{query_text}}" } }
      ]
      {{#status}},
      "filter": [
        { "term": { "status": "{{status}}" } }
      ]
      {{/status}}
      {{#sort_by}},
      "sort": [
        { "{{status}}": "asc" }
      ]
      {{/sort_by}}
    }
  }
}
```

**Expected params:**

| Param | Type | Required | Default |
|---|---|---|---|
| `query_text` | string | true | -- |
| `status` | string | false | -- |
| `sort_by` | boolean | false | -- |

**Why this matters:** `status` appears as a section controller at depth 0, as a `ValueCode` at depth 1 inside its own section (self-guarding), AND as a `ValueCode` at depth 1 inside the `sort_by` section. The `appearsInsideOwnSection` flag will be true from the self-guarding usage. But the second usage inside `sort_by` is NOT inside its own section -- it is inside `sort_by`'s section at depth 1. Since `appearsInsideOwnSection` is already true, `isRequired` returns false. This tests that cross-section value references do not accidentally flip requiredness.

---

## e5: Empty/Minimal Template (Single Variable)

**What it tests:** The absolute minimum template -- a single variable with no surrounding DSL context. Tests that `precedingText` may be minimal or empty, and that the system handles templates with no JSON structure gracefully.

**Template (`e5_single_var`):**

```mustache
{{query}}
```

**Expected params:**

| Param | Type | Required | Default |
|---|---|---|---|
| `query` | string | true | -- |

**Why this matters:** No preceding DSL context means `precedingText` is either `null` or empty. The `inferType` method should fall through to the default `"string"` return. The `generateDescription` method should fall through to the generic `"Value for 'query'"`. This is the degenerate case -- if it fails, something is fundamentally wrong with the walker.

---

## e6: Template with ONLY Section Controllers (No Direct Value References)

**What it tests:** A template where every parameter is a section controller and none appear as `{{var}}` values. Every `ParamInfo` will have `isSectionControllerOnly = true`, so all should be typed as `boolean` and marked optional.

**Template (`e6_sections_only`):**

```mustache
{
  "query": {
    "bool": {
      "must": [
        { "match_all": {} }
      ]
      {{#include_active}},
      "filter": [
        { "term": { "active": true } }
      ]
      {{/include_active}}
      {{#include_recent}},
      "filter": [
        { "range": { "created_at": { "gte": "now-7d" } } }
      ]
      {{/include_recent}}
      {{#exclude_deleted}},
      "must_not": [
        { "term": { "deleted": true } }
      ]
      {{/exclude_deleted}}
    }
  }
}
```

**Expected params:**

| Param | Type | Required | Default |
|---|---|---|---|
| `include_active` | boolean | false | -- |
| `include_recent` | boolean | false | -- |
| `exclude_deleted` | boolean | false | -- |

**Why this matters:** All three sections contain only static text (`WriteCode` nodes). None of the section names appear as `{{var}}` inside or outside their sections, so `isSectionControllerOnly` stays `true` for all of them. This is the pure boolean-guard pattern. The template has zero required parameters, which is a valid but unusual outcome.

---

## e7: `{{#join}}` Helper

**What it tests:** The `join` helper, which is handled identically to `toJson` in the code but uses a different helper name. Also tests the `join delimiter='...'` pattern that the `JOIN_DELIMITER_PATTERN` regex is designed to match.

**Template (`e7_join_helper`):**

```mustache
{
  "query": {
    "match": {
      "emails": "{{#join}}email_list{{/join}}"
    }
  },
  "sort": [
    { "{{#join delimiter=','}}sort_fields{{/join}}": "asc" }
  ]
}
```

**Expected params:**

| Param | Type | Required | Default |
|---|---|---|---|
| `email_list` | array | true | -- |
| `sort_fields` | array | true | -- |

**Why this matters:** Two things to verify: (1) plain `{{#join}}` is recognized as a helper and the inner variable name is extracted correctly, and (2) `{{#join delimiter=','}}` matches the `JOIN_DELIMITER_PATTERN` regex (`(?i)^join\\s+delimiter='.*'$`). Note the template uses `delimiter=','` (no space after `=`). The regex expects `delimiter='...'` with single quotes. If the actual template uses a different quoting style, the regex could fail to match and the code would treat it as a regular section controller instead.

---

## e8: Multiple toJson Calls on the Same Variable

**What it tests:** The same variable name referenced by `toJson` in two different places. The `ParamInfo` should be created on the first encounter and the second `toJson` call should merge into the same entry. `isArrayType` should be `true` after either call.

**Template (`e8_duplicate_tojson`):**

```mustache
{
  "query": {
    "bool": {
      "must": [
        { "terms": { "tags": {{#toJson}}tags{{/toJson}} } }
      ],
      "should": [
        { "terms": { "preferred_tags": {{#toJson}}tags{{/toJson}} } }
      ]
    }
  }
}
```

**Expected params:**

| Param | Type | Required | Default |
|---|---|---|---|
| `tags` | array | true | -- |

**Why this matters:** Deduplication of the same variable referenced multiple times through helpers. Both `toJson` calls reference `tags` -- the `computeIfAbsent` in `handleHelperFunction` should return the existing `ParamInfo` on the second call. The param should appear exactly once in the output, not duplicated. Also tests that `appearsAtRootScope` is set to `true` (both calls are at depth 0).

---

## e9: Dot Notation Variables (`{{user.name}}`)

**What it tests:** Variables with dot notation like `{{user.name}}` and `{{user.email}}`. The code splits on `.` and takes `[0]` as the root parameter name. Both should map to the root param `user`.

**Template (`e9_dot_notation`):**

```mustache
{
  "query": {
    "bool": {
      "must": [
        { "match": { "author.name": "{{user.name}}" } },
        { "match": { "author.email": "{{user.email}}" } }
      ]
    }
  },
  "size": {{result.count}}
}
```

**Expected params:**

| Param | Type | Required | Default |
|---|---|---|---|
| `user` | string | true | -- |
| `result` | number | true | -- |

**Why this matters:** Three dot-notation variables collapse to two root params. `user.name` and `user.email` both map to `user`. `result.count` maps to `result`. The type of `user` is determined by the `precedingText` of whichever dot-notation occurrence is visited first (`user.name` in quoted context -> `string`). The type of `result` comes from its preceding text (after `"size":` -> `number`). This also raises a question: is it correct to treat `user` as a single parameter when `user.name` and `user.email` suggest it is an object with fields? The current design collapses to the root, but this may lose information.

---

## e10: Inverted Section Without a Regular Section (Standalone Default)

**What it tests:** An inverted section `{{^var}}default{{/var}}` without a corresponding `{{var}}` or `{{#var}}` anywhere in the template. The `collectInvertedSections` pass will find the default, but `walkCodes` may or may not create a `ParamInfo` entry for this variable depending on how `NotIterableCode` is handled.

**Template (`e10_inverted_only`):**

```mustache
{
  "query": {
    "match": {
      "title": "{{query_text}}"
    }
  },
  "size": {{^size}}10{{/size}}
}
```

**Expected params:**

| Param | Type | Required | Default |
|---|---|---|---|
| `query_text` | string | true | -- |
| `size` | ?? | false | 10 |

**Why this matters:** This is a tricky case. The inverted section `{{^size}}10{{/size}}` creates a `NotIterableCode` node for `size`, and `collectInvertedSections` will record the default `"10"`. However, `walkCodes` processes `NotIterableCode` by recursing into its children but does NOT create a `ParamInfo` for the section name itself. The children of the inverted section are just `WriteCode("10")`, which is static text. So `size` will have a default recorded in `invertedSectionDefaults`, but there may be no `ParamInfo` entry for it in `params` -- in which case the loop in `analyze()` that merges defaults will find `info == null` and skip it. **This means `size` could be silently dropped from the output.** This is a potential bug: a standalone inverted section provides a default but the parameter itself is never registered. In real OpenSearch usage, `{{^size}}10{{/size}}` alone does not produce any output when `size` IS provided -- you need `{{size}}{{^size}}10{{/size}}` for the value to actually render. So the template is arguably malformed, but the analyzer should still report `size` as a discovered parameter.

---

## e11: Section Containing Only Static Text (Pure Boolean Guard)

**What it tests:** A section `{{#flag}}...{{/flag}}` where the body contains only `WriteCode` (literal JSON text) with no variable references. This is the boolean detection pattern. Verifies `isSectionControllerOnly` remains `true`.

**Template (`e11_boolean_static`):**

```mustache
{
  "query": {
    "match": { "title": "{{query_text}}" }
  }
  {{#include_highlights}},
  "highlight": {
    "fields": {
      "title": { "fragment_size": 150 },
      "body": { "fragment_size": 200 }
    }
  }
  {{/include_highlights}}
  {{#include_explain}},
  "explain": true
  {{/include_explain}}
}
```

**Expected params:**

| Param | Type | Required | Default |
|---|---|---|---|
| `query_text` | string | true | -- |
| `include_highlights` | boolean | false | -- |
| `include_explain` | boolean | false | -- |

**Why this matters:** Two boolean guards with complex static content. `include_highlights` contains a multi-level JSON structure but no variable references. `include_explain` contains just `"explain": true`. Both should keep `isSectionControllerOnly = true`. Tests that the recursive walk through static `WriteCode` children does not accidentally set any flags.

---

## e12: Parameter in Both Root Scope AND Inside a Section

**What it tests:** A variable that appears both at root scope (`depth == 0`) and inside another variable's section. This is different from the self-guarding pattern -- here `limit` is used at root scope AND inside `{{#filters}}`.

**Template (`e12_root_and_nested`):**

```mustache
{
  "query": {
    "match": { "title": "{{query_text}}" }
  },
  "size": {{limit}}
  {{#filters}},
  "post_filter": {
    "range": {
      "count": { "lte": {{limit}} }
    }
  }
  {{/filters}}
}
```

**Expected params:**

| Param | Type | Required | Default |
|---|---|---|---|
| `query_text` | string | true | -- |
| `limit` | number | true | -- |
| `filters` | boolean | false | -- |

**Why this matters:** `limit` appears at depth 0 (root scope) and at depth 1 (inside `filters`). The first appearance at root sets `appearsAtRootScope = true`. The second appearance inside `filters` sets `parentSection = "filters"` which does not equal `rootName = "limit"`, so `appearsInsideOwnSection` stays false. Since `appearsAtRootScope` is true and there is no inverted default, `isRequired` returns true. This is correct -- the template always outputs `"size": {{limit}}` regardless of `filters`. Tests that root-scope detection overrides nested-scope appearances.

---

## e13: Template with No Variables At All

**What it tests:** A valid Mustache template that contains zero variable references -- only static JSON. The analyzer should return an empty params map.

**Template (`e13_no_variables`):**

```mustache
{
  "query": {
    "match_all": {}
  },
  "size": 10,
  "sort": [{ "_score": "desc" }]
}
```

**Expected params:**

| Param | Type | Required | Default |
|---|---|---|---|
| *(none)* | -- | -- | -- |

**Why this matters:** The degenerate case of no variables. The entire template is a single `WriteCode` node. `walkCodes` should iterate over the codes, see only `WriteCode`, skip them all, and return an empty map. This validates that the system handles static templates gracefully and does not crash on empty iteration.

---

## e14: `{{&var}}` Ampersand-Prefixed Unescaped Variable

**What it tests:** `{{&var}}` is Mustache's alternative syntax for unescaped output (equivalent to `{{{var}}}`). The mustache.java parser should produce a `ValueCode` with the name `var` (not `&var`). Confirms the ampersand prefix does not leak into the parameter name.

**Template (`e14_ampersand_var`):**

```mustache
{
  "query": {{&raw_query}},
  "aggs": {{&raw_aggs}},
  "size": {{size}}
}
```

**Expected params:**

| Param | Type | Required | Default |
|---|---|---|---|
| `raw_query` | number | true | -- |
| `raw_aggs` | number | true | -- |
| `size` | number | true | -- |

**Why this matters:** Verifies that the mustache.java parser strips the `&` prefix before creating the `ValueCode` node. If it does NOT strip it, the parameter name would be `&raw_query` which is incorrect. Also note that all three variables are in unquoted positions (preceded by `:` or `,`), so all get type `number` -- another instance where the type heuristic is misleading (raw_query/raw_aggs are JSON objects, not numbers). This is the same heuristic limitation surfaced in e1.

---

## e15: Large Template with Many Parameters

**What it tests:** A realistic production-scale template with 10+ parameters across multiple patterns (values, sections, toJson, inverted defaults, booleans). Stress-tests that all parameter tracking structures handle many entries correctly and that deduplication works across a large AST.

**Template (`e15_large_template`):**

```mustache
{
  "query": {
    "bool": {
      "must": [
        {
          "multi_match": {
            "query": "{{query_text}}",
            "fields": {{#toJson}}search_fields{{/toJson}},
            "type": "{{#match_type}}{{match_type}}{{/match_type}}{{^match_type}}best_fields{{/match_type}}"
          }
        }
      ]
      {{#category}},
      "filter": [
        { "term": { "category": "{{category}}" } }
      ]
      {{/category}}
      {{#tags}},
      "filter": [
        { "terms": { "tags": {{#toJson}}tags{{/toJson}} } }
      ]
      {{/tags}}
      {{#date_from}},
      "filter": [
        { "range": { "created_at": { "gte": "{{date_from}}" } } }
      ]
      {{/date_from}}
      {{#date_to}},
      "filter": [
        { "range": { "created_at": { "lte": "{{date_to}}" } } }
      ]
      {{/date_to}}
      {{#author}},
      "filter": [
        { "term": { "author.keyword": "{{author}}" } }
      ]
      {{/author}}
      {{#exclude_ids}},
      "must_not": [
        { "ids": { "values": {{#toJson}}exclude_ids{{/toJson}} } }
      ]
      {{/exclude_ids}}
      {{#include_drafts}},
      "should": [
        { "term": { "status": "draft" } }
      ]
      {{/include_drafts}}
    }
  },
  "_source": {{#toJson}}source_fields{{/toJson}},
  "sort": [
    {
      "{{#sort_field}}{{sort_field}}{{/sort_field}}{{^sort_field}}_score{{/sort_field}}": {
        "order": "{{#sort_order}}{{sort_order}}{{/sort_order}}{{^sort_order}}desc{{/sort_order}}"
      }
    }
  ],
  "from": {{#from}}{{from}}{{/from}}{{^from}}0{{/from}},
  "size": {{#size}}{{size}}{{/size}}{{^size}}25{{/size}}
  {{#include_aggs}},
  "aggs": {
    "by_category": {
      "terms": { "field": "category", "size": 10 }
    }
  }
  {{/include_aggs}}
}
```

**Expected params:**

| Param | Type | Required | Default |
|---|---|---|---|
| `query_text` | string | true | -- |
| `search_fields` | array | true | -- |
| `match_type` | string | false | best_fields |
| `category` | string | false | -- |
| `tags` | array | false | -- |
| `date_from` | string | false | -- |
| `date_to` | string | false | -- |
| `author` | string | false | -- |
| `exclude_ids` | array | false | -- |
| `include_drafts` | boolean | false | -- |
| `source_fields` | array | true | -- |
| `sort_field` | string | false | _score |
| `sort_order` | string | false | desc |
| `from` | number | false | 0 |
| `size` | number | false | 25 |
| `include_aggs` | boolean | false | -- |

**Why this matters:** 16 parameters across all pattern types: 3 required values, 5 self-guarding sections, 4 arrays (2 required at root, 2 inside sections), 2 booleans, 4 inverted defaults. This is the scale a real production template might reach. Tests that `LinkedHashMap` ordering is preserved, that `computeIfAbsent` handles many entries, and that the inverted-section default merging loop works across all parameters.

---

## e16: Inverted Section with Non-Trivial Default Content

**What it tests:** An inverted section where the default value contains JSON structure, not just a simple scalar. The `extractDefaultValue` method concatenates all `WriteCode` children -- what happens when the default is multi-part or contains special characters?

**Template (`e16_complex_default`):**

```mustache
{
  "query": {
    "match": { "title": "{{query_text}}" }
  },
  "sort": {{#sort}}{{sort}}{{/sort}}{{^sort}}[{"_score":"desc"}]{{/sort}}
}
```

**Expected params:**

| Param | Type | Required | Default |
|---|---|---|---|
| `query_text` | string | true | -- |
| `sort` | string | false | [{"_score":"desc"}] |

**Why this matters:** The default value is a JSON array string `[{"_score":"desc"}]`. The `extractDefaultValue` method concatenates `WriteCode` texts and trims whitespace. This tests that complex default values with brackets, quotes, and colons are captured verbatim. The default value string may look odd, but it is correct -- it represents the literal text that would be output when `sort` is absent.

---

## e17: `{{#url}}` Helper Wrapping a Variable

**What it tests:** The `url` helper is treated as transparent in the code -- it recurses into children at the same scope depth. A variable inside `{{#url}}{{var}}{{/url}}` should be extracted as a normal root-scope variable.

**Template (`e17_url_helper`):**

```mustache
{
  "query": {
    "term": {
      "url": "{{#url}}{{search_url}}{{/url}}"
    }
  },
  "size": {{size}}
}
```

**Expected params:**

| Param | Type | Required | Default |
|---|---|---|---|
| `search_url` | string | true | -- |
| `size` | number | true | -- |

**Why this matters:** The `url` helper is the only helper that does NOT extract a variable name from its section name -- instead, it recurses into its children. The inner `{{search_url}}` is a `ValueCode` that should be found during recursion. The `depth` should remain unchanged (url is transparent), so `search_url` appears at the same scope as if it were `{{search_url}}` directly. Tests the `"url".equals(lowerName)` branch in `handleHelperFunction`.

---

## e18: Implicit Iterator `{{.}}` Inside a Section

**What it tests:** The `{{.}}` implicit iterator inside a loop section. The walker explicitly skips `"."` in `handleValueCode`. This tests that `{{.}}` does not produce a parameter named `.` and that the section controller itself is still captured.

**Template (`e18_implicit_iterator`):**

```mustache
{
  "query": {
    "terms": {
      "status": [
        {{#statuses}}
        "{{.}}"
        {{/statuses}}
      ]
    }
  }
}
```

**Expected params:**

| Param | Type | Required | Default |
|---|---|---|---|
| `statuses` | string | false | -- |

**Why this matters:** `statuses` is a section controller. Inside it, `{{.}}` is the implicit iterator (current element in the list). The walker should skip `{{.}}` (the `".".equals(name)` check in `handleValueCode`) and NOT create a parameter named `.`. `statuses` itself is discovered as a section controller. Since no `{{statuses}}` value reference exists, `isSectionControllerOnly` stays `true`, giving it type `boolean`. However, this is semantically wrong -- `statuses` is actually an array that is iterated. The AST cannot distinguish "conditional section" from "iteration section" since both use `IterableCode`. This is a known limitation: without runtime type info, the analyzer types it as `boolean` when it is really an array. Note the type expectation is listed as `string` above but the actual behavior will be `boolean` due to `isSectionControllerOnly`. This is a case where the type heuristic is incorrect.

**Corrected expected (actual behavior):**

| Param | Type | Required | Default |
|---|---|---|---|
| `statuses` | boolean | false | -- |

---

## e19: Variable Name Collision Between Section Controller and toJson

**What it tests:** A variable that appears as BOTH a section controller (`{{#items}}...{{/items}}`) and inside a `toJson` helper (`{{#toJson}}items{{/toJson}}`). The `toJson` usage sets `isArrayType = true` and `isSectionControllerOnly = false`. This tests that the array type from the helper overrides the boolean type from the section controller.

**Template (`e19_section_and_tojson`):**

```mustache
{
  "query": {
    "bool": {
      "must": [
        { "match": { "title": "{{query_text}}" } }
      ]
      {{#items}},
      "filter": [
        { "terms": { "item_ids": {{#toJson}}items{{/toJson}} } }
      ]
      {{/items}}
    }
  }
}
```

**Expected params:**

| Param | Type | Required | Default |
|---|---|---|---|
| `query_text` | string | true | -- |
| `items` | array | false | -- |

**Why this matters:** `items` is discovered first as an `IterableCode` section controller (setting `isSectionControllerOnly = true`), then inside the section, `{{#toJson}}items{{/toJson}}` calls `handleHelperFunction` which sets `isArrayType = true` and `isSectionControllerOnly = false`. The `inferType` method checks `isArrayType` first, so the final type is `array` (correct). `appearsInsideOwnSection` is set to `true` (the toJson is inside the `items` section with `parentSection = "items"`), so `isRequired` returns false. This is an important interaction between the section-controller path and the helper-function path.

---

## e20: Whitespace and Formatting Variations

**What it tests:** Variables with whitespace in Mustache tags (`{{ var }}` with spaces), which is valid Mustache syntax. The mustache.java parser should trim the variable name. Also tests that templates with unusual formatting (newlines inside tags, etc.) are handled.

**Template (`e20_whitespace`):**

```mustache
{
  "query": {
    "match": {
      "title": "{{ query_text }}"
    }
  },
  "size": {{ size }}
}
```

**Expected params:**

| Param | Type | Required | Default |
|---|---|---|---|
| `query_text` | string | true | -- |
| `size` | number | true | -- |

**Why this matters:** Mustache allows whitespace inside tags. The mustache.java parser should trim the name, so `{{ query_text }}` produces a `ValueCode` with name `"query_text"` (not `" query_text "`). If the parser does NOT trim, the parameter name would include leading/trailing spaces, which would be a subtle bug causing param name mismatches at runtime. This is a parser-level concern, not a walker concern, but it is important to verify end-to-end.

---

## Summary Table

| Edge Case | Key Pattern | Params | Potential Issue |
|---|---|---|---|
| e1 | `{{{var}}}` triple braces | 2 | Type heuristic: unquoted raw JSON typed as `number` |
| e2 | Quoted + unquoted same var | 1 | First-occurrence-wins type inference |
| e3 | 3-level nesting | 4 | Depth counter correctness |
| e4 | Section controller + value in other section | 3 | Cross-section value references |
| e5 | Single variable only | 1 | Minimal template, no DSL context |
| e6 | All boolean section controllers | 3 | Zero required params |
| e7 | `{{#join}}` and `{{#join delimiter=','}}` | 2 | JOIN_DELIMITER_PATTERN regex |
| e8 | Same var in two toJson calls | 1 | Deduplication through helpers |
| e9 | `{{user.name}}` dot notation | 2 | Root name extraction, info loss |
| e10 | `{{^var}}` only (no `{{var}}`) | 1-2 | Potential silent param drop (bug?) |
| e11 | Boolean sections with static JSON | 3 | `isSectionControllerOnly` stays true |
| e12 | Root scope + nested scope same var | 3 | Root-scope detection overrides nesting |
| e13 | No variables at all | 0 | Empty map return |
| e14 | `{{&var}}` ampersand prefix | 3 | Ampersand stripping by parser |
| e15 | 16-param production template | 16 | Scale and ordering |
| e16 | Complex JSON default value | 2 | Multi-character default extraction |
| e17 | `{{#url}}{{var}}{{/url}}` | 2 | Transparent url helper |
| e18 | `{{.}}` implicit iterator | 1 | Dot filtering; boolean vs array ambiguity |
| e19 | Section controller + toJson same var | 2 | Array type overrides boolean |
| e20 | `{{ var }}` whitespace in tags | 2 | Parser name trimming |

---

## Notes on Potential Bugs Found

1. **e10 (Inverted section only):** A standalone `{{^var}}default{{/var}}` without any `{{var}}` or `{{#var}}` may result in the parameter being silently dropped. The `collectInvertedSections` pass records the default, but no `ParamInfo` is created during `walkCodes` because `NotIterableCode` only recurses into children and does not register the section name as a parameter. The merge loop in `analyze()` checks `params.get(entry.getKey())` which returns `null`, so the default is discarded. **Recommendation:** Either register a `ParamInfo` for `NotIterableCode` section names, or treat standalone inverted sections as a template authoring error and document the limitation.

2. **e18 (Implicit iterator / array vs boolean):** Section controllers used for iteration (`{{#items}}{{.}}{{/items}}`) are indistinguishable from conditional guards at the AST level. Both produce `IterableCode`. The current heuristic types them as `boolean`, which is incorrect for iteration patterns. **Recommendation:** If a section contains `{{.}}` (implicit iterator), the section controller is likely an array, not a boolean. Adding a check for `{{.}}` presence inside a section could improve type inference.

3. **e1/e14 (Unescaped variables in object positions):** Variables injecting raw JSON objects (`"query": {{{raw_query}}}` or `"query": {{&raw_query}}`) are typed as `number` because the preceding text ends with `:`. The heuristic cannot distinguish `:` followed by a JSON object from `:` followed by a number. **Recommendation:** Consider an `object` or `json` type for triple-brace/ampersand variables, or document this as a known limitation.
