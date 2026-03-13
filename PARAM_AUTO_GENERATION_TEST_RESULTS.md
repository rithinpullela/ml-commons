# Parameter Auto-Generation Test Results

Tested on OpenSearch 3.6.0-SNAPSHOT with ml-commons plugin (feature/custom-tools branch).

---

## Test Environment

- Cluster: `integTest` (single node, localhost:9200)
- OpenSearch: 3.6.0-SNAPSHOT
- ml-commons: feature/custom-tools branch (commit 0e9bdca1b)
- Creation response now includes `tool_id` + `params`
- Default values extracted from inverted sections

---

## Tier 1 Tests: Auto-Extraction (10 templates, no params provided)

### Test 1: Simple — Basic Variables Only

**Template (`t1_simple`):**
```
{"query":{"match":{"title":"{{query_text}}"}},"size":{{result_size}}}
```

**Response:**
```json
{
  "tool_id": "kSIi4JwBpgw9UL7C3wO0",
  "params": {
    "query_text": {
      "type": "string",
      "description": "Value for the 'title' field (match)",
      "required": true
    },
    "result_size": {
      "type": "string",
      "description": "Value for 'result_size'",
      "required": true
    }
  }
}
```

**Verdict:** `query_text` correctly gets DSL-aware description from context (`title` field, `match` query). Both params required since no defaults.

---

### Test 2: Inverted Section Defaults

**Template (`t2_inverted`):**
```
{"query":{"match":{"title":"{{query_text}}"}},"from":{{#from}}{{from}}{{/from}}{{^from}}0{{/from}},"size":{{#size}}{{size}}{{/size}}{{^size}}10{{/size}}}
```

**Response:**
```json
{
  "params": {
    "query_text": {
      "type": "string",
      "description": "Value for the 'title' field (match)",
      "required": true
    },
    "from": {
      "type": "string",
      "description": "Value for 'from'",
      "required": false,
      "default": "0"
    },
    "size": {
      "type": "string",
      "description": "Value for 'size'",
      "required": false,
      "default": "10"
    }
  }
}
```

**Verdict:** `from` and `size` correctly marked `required: false` with `default` values extracted from inverted sections. Default values `"0"` and `"10"` match the template literals.

---

### Test 3: Section Guard + toJson Array

**Template (`t3_section_tojson`):**
```
{"query":{"bool":{"must":[{"match":{"title":"{{query_text}}"}}]{{#tags}},"filter":[{"terms":{"tags":{{#toJson}}tags{{/toJson}}}}]{{/tags}}}},"size":{{#size}}{{size}}{{/size}}{{^size}}10{{/size}}}
```

**Response:**
```json
{
  "params": {
    "query_text": {
      "type": "string",
      "description": "Value for the 'title' field (match)",
      "required": true
    },
    "tags": {
      "type": "array",
      "description": "Value for 'tags' (array)",
      "required": true
    },
    "size": {
      "type": "string",
      "description": "Value for 'size'",
      "required": false,
      "default": "10"
    }
  }
}
```

**Verdict:** `tags` correctly detected as `array` from the `{{#toJson}}tags{{/toJson}}` helper. This is a key win over regex — the variable name is plain text inside a section, not a `{{tag}}`.

---

### Test 4: Multi-Match with Fields Array + Default Type

**Template (`t4_multi_match`):**
```
{"query":{"multi_match":{"query":"{{query_text}}","fields":{{#toJson}}fields{{/toJson}},"type":"{{#match_type}}{{match_type}}{{/match_type}}{{^match_type}}best_fields{{/match_type}}"}},"size":{{#size}}{{size}}{{/size}}{{^size}}10{{/size}}}
```

**Response:**
```json
{
  "params": {
    "query_text": {
      "type": "string",
      "description": "Value for the 'query' field",
      "required": true
    },
    "fields": {
      "type": "array",
      "description": "Value for 'fields' (array)",
      "required": true
    },
    "match_type": {
      "type": "string",
      "description": "Value for 'match_type'",
      "required": false,
      "default": "best_fields"
    },
    "size": {
      "type": "string",
      "description": "Value for 'size'",
      "required": false,
      "default": "10"
    }
  }
}
```

**Verdict:** `fields` correctly detected as `array`. `match_type` correctly optional with default `"best_fields"`. String defaults extracted from inverted sections work for non-numeric values too.

---

### Test 5: Complex Bool — Multiple Optional Clauses

**Template (`t5_complex_bool`):**
```
{"query":{"bool":{"must":[{"match":{"title":"{{query_text}}"}}]
  {{#category}},"filter":[{"term":{"category":"{{category}}"}}]{{/category}}
  {{#brand}},"must":[{"term":{"brand":"{{brand}}"}}]{{/brand}}
  {{#min_rating}},"filter":[{"range":{"rating":{"gte":{{min_rating}}}}}]{{/min_rating}}
}},"from":{{#from}}{{from}}{{/from}}{{^from}}0{{/from}},"size":{{#size}}{{size}}{{/size}}{{^size}}20{{/size}}}
```

**Response:**
```json
{
  "params": {
    "query_text": {
      "type": "string",
      "description": "Value for the 'title' field (match)",
      "required": true
    },
    "category": {
      "type": "string",
      "description": "Value for the 'category' field (term)",
      "required": true
    },
    "brand": {
      "type": "string",
      "description": "Value for the 'brand' field (term)",
      "required": true
    },
    "min_rating": {
      "type": "string",
      "description": "Value for the 'gte' field (range)",
      "required": true
    },
    "from": {
      "type": "string",
      "description": "Value for 'from'",
      "required": false,
      "default": "0"
    },
    "size": {
      "type": "string",
      "description": "Value for 'size'",
      "required": false,
      "default": "20"
    }
  }
}
```

**Verdict:** 6 params extracted. `from` and `size` optional with defaults `"0"` and `"20"`. `category`, `brand`, `min_rating` are self-guarding sections — marked required (known Tier 1 limitation, see notes below). `min_rating` description correctly identifies `range` query context.

---

### Test 6: Range with Boolean Guard

**Template (`t6_range`):**
```
{"query":{"bool":{"must":[{"match":{"title":"{{query_text}}"}}]
  {{#price_filter}},"filter":[{"range":{"price":{"gte":{{min_price}},"lte":{{max_price}}}}}]{{/price_filter}}
}},"size":{{#size}}{{size}}{{/size}}{{^size}}10{{/size}}}
```

**Response:**
```json
{
  "params": {
    "query_text": {
      "type": "string",
      "description": "Value for the 'title' field (match)",
      "required": true
    },
    "price_filter": {
      "type": "boolean",
      "description": "Flag to enable/disable the 'price_filter' clause",
      "required": false
    },
    "min_price": {
      "type": "string",
      "description": "Value for the 'gte' field (range)",
      "required": false
    },
    "max_price": {
      "type": "string",
      "description": "Value for 'max_price'",
      "required": false
    },
    "size": {
      "type": "string",
      "description": "Value for 'size'",
      "required": false,
      "default": "10"
    }
  }
}
```

**Verdict:** `price_filter` correctly detected as `boolean` (section controller only — no `{{price_filter}}` value reference inside). `min_price` and `max_price` correctly optional (nested inside `price_filter` section, not at root scope). Shows the analyzer handles the boolean-guard pattern well.

---

### Test 7: Source Filtering + Sort with Defaults

**Template (`t7_source_sort`):**
```
{"query":{"match":{"title":"{{query_text}}"}},"_source":{{#toJson}}source_fields{{/toJson}},
 "sort":[{"{{#sort_field}}{{sort_field}}{{/sort_field}}{{^sort_field}}_score{{/sort_field}}":
  {"order":"{{#sort_order}}{{sort_order}}{{/sort_order}}{{^sort_order}}desc{{/sort_order}}"}}],
 "size":{{#size}}{{size}}{{/size}}{{^size}}10{{/size}}}
```

**Response:**
```json
{
  "params": {
    "query_text": {
      "type": "string",
      "description": "Value for the 'title' field (match)",
      "required": true
    },
    "source_fields": {
      "type": "array",
      "description": "Value for 'source_fields' (array)",
      "required": true
    },
    "sort_field": {
      "type": "string",
      "description": "Value for 'sort_field'",
      "required": false,
      "default": "_score"
    },
    "sort_order": {
      "type": "string",
      "description": "Value for 'sort_order'",
      "required": false,
      "default": "desc"
    },
    "size": {
      "type": "string",
      "description": "Value for 'size'",
      "required": false,
      "default": "10"
    }
  }
}
```

**Verdict:** `source_fields` correctly detected as `array` (via toJson). Sort defaults extracted perfectly: `sort_field` defaults to `"_score"`, `sort_order` defaults to `"desc"`. Shows non-numeric string defaults work correctly.

---

### Test 8: Aggregation with Boolean Toggle

**Template (`t8_aggs`):**
```
{"size":0,"query":{"match":{"title":"{{query_text}}"}},
 "aggs":{"by_category":{"terms":{"field":"{{agg_field}}",
  "size":{{#agg_size}}{{agg_size}}{{/agg_size}}{{^agg_size}}10{{/agg_size}}}
  {{#include_stats}},"aggs":{"price_stats":{"stats":{"field":"price"}}}{{/include_stats}}}}}
```

**Response:**
```json
{
  "params": {
    "query_text": {
      "type": "string",
      "description": "Value for the 'title' field (match)",
      "required": true
    },
    "agg_field": {
      "type": "string",
      "description": "Value for 'agg_field'",
      "required": true
    },
    "agg_size": {
      "type": "string",
      "description": "Value for 'agg_size'",
      "required": false,
      "default": "10"
    },
    "include_stats": {
      "type": "boolean",
      "description": "Flag to enable/disable the 'include_stats' clause",
      "required": false
    }
  }
}
```

**Verdict:** `include_stats` correctly detected as `boolean` — it's a section controller with no value reference inside, just static JSON content. It controls whether the `price_stats` sub-aggregation is included. `agg_size` has the correct default.

---

### Test 9: Highlight with Boost Default

**Template (`t9_highlight`):**
```
{"query":{"bool":{"should":[
  {"match":{"title":{"query":"{{query_text}}","boost":{{#title_boost}}{{title_boost}}{{/title_boost}}{{^title_boost}}2{{/title_boost}}}}},
  {"match":{"description":"{{query_text}}"}}],
  "minimum_should_match":{{#min_match}}{{min_match}}{{/min_match}}{{^min_match}}1{{/min_match}}}},
 "highlight":{"fields":{"title":{},"description":{}}},
 "size":{{#size}}{{size}}{{/size}}{{^size}}10{{/size}}}
```

**Response:**
```json
{
  "params": {
    "query_text": {
      "type": "string",
      "description": "Value for the 'query' field (match)",
      "required": true
    },
    "title_boost": {
      "type": "string",
      "description": "Value for 'title_boost'",
      "required": false,
      "default": "2"
    },
    "min_match": {
      "type": "string",
      "description": "Value for 'min_match'",
      "required": false,
      "default": "1"
    },
    "size": {
      "type": "string",
      "description": "Value for 'size'",
      "required": false,
      "default": "10"
    }
  }
}
```

**Verdict:** `title_boost` correctly optional with default `"2"`. `min_match` defaults to `"1"`. `query_text` is the only required param — correctly identified since it's at root scope and used in multiple places (title match + description match). The analyzer correctly deduplicates it.

---

### Test 10: Kitchen Sink — Multiple toJson Arrays + Inverted Defaults

**Template (`t10_kitchen_sink`):**
```
{"query":{"bool":{"must":[{"multi_match":{"query":"{{query_text}}","fields":{{#toJson}}search_fields{{/toJson}}}}]
  {{#categories}},"filter":[{"terms":{"category":{{#toJson}}categories{{/toJson}}}}]{{/categories}}
  {{#exclude_ids}},"must_not":[{"ids":{"values":{{#toJson}}exclude_ids{{/toJson}}}}]{{/exclude_ids}}}},
 "from":{{#from}}{{from}}{{/from}}{{^from}}0{{/from}},
 "size":{{#size}}{{size}}{{/size}}{{^size}}25{{/size}}}
```

**Response:**
```json
{
  "params": {
    "query_text": {
      "type": "string",
      "description": "Value for the 'query' field (must)",
      "required": true
    },
    "search_fields": {
      "type": "array",
      "description": "Value for 'search_fields' (array)",
      "required": true
    },
    "categories": {
      "type": "array",
      "description": "Value for 'categories' (array)",
      "required": true
    },
    "exclude_ids": {
      "type": "array",
      "description": "Value for 'exclude_ids' (array)",
      "required": true
    },
    "from": {
      "type": "string",
      "description": "Value for 'from'",
      "required": false,
      "default": "0"
    },
    "size": {
      "type": "string",
      "description": "Value for 'size'",
      "required": false,
      "default": "25"
    }
  }
}
```

**Verdict:** 6 params extracted from the most complex template. Three `toJson` arrays all correctly detected (`search_fields`, `categories`, `exclude_ids`). Pagination defaults correct. The template uses `multi_match` inside a `bool.must`, `terms` inside `filter`, and `ids` inside `must_not` — all correctly parsed from the AST.

---

## Tier 3 Test: Manual Params

**Request (same template as Test 1, but with user-provided params):**
```json
POST /_plugins/_ml/tools/_create
{
  "name": "ManualParamsTest",
  "description": "Manual param definitions",
  "type": "search_template",
  "search_template_name": "t1_simple",
  "params": {
    "query_text": { "type": "text", "description": "My custom description", "required": true },
    "result_size": { "type": "integer", "description": "Max results", "required": false }
  }
}
```

**Result:** Params stored exactly as-is. No auto-extraction. Template existence validated.

---

## Tier 2 Test: model_id Placeholder

**Request:**
```json
POST /_plugins/_ml/tools/_create
{
  "name": "ModelIdTest",
  "description": "Tier 2 placeholder",
  "type": "search_template",
  "search_template_name": "t3_section_tojson",
  "model_id": "some-model-id"
}
```

**Result:** Tool created. AST-extracted params used (same as Tier 1). `model_id` stored in document. LLM enrichment not yet implemented (TODO).

---

## Validation Tests

| Test | Input | Expected Error | Got |
|------|-------|---------------|-----|
| Mutual exclusion | `params` + `model_id` both set | 400 | `"Cannot specify both 'params' and 'model_id'..."` |
| Non-existent template | No params, bad template name | 400 | `"Search template '...' not found"` |
| Duplicate name | Name already exists | 400 | `"A custom tool with name '...' already exists"` |
| Malformed template | Template with nested section syntax error | 400 | `"Failed to analyze search template '...': Failed to parse Mustache template: ..."` |

---

## Summary Table: All 10 Templates

| # | Template | Params Found | Arrays | Booleans | Defaults | Required | Optional |
|---|----------|:---:|:---:|:---:|:---:|:---:|:---:|
| 1 | Simple match | 2 | 0 | 0 | 0 | 2 | 0 |
| 2 | Inverted defaults | 3 | 0 | 0 | 2 (`from`=0, `size`=10) | 1 | 2 |
| 3 | Section + toJson | 3 | 1 (`tags`) | 0 | 1 (`size`=10) | 2 | 1 |
| 4 | Multi-match + fields array | 4 | 1 (`fields`) | 0 | 2 (`match_type`=best_fields, `size`=10) | 2 | 2 |
| 5 | Complex bool | 6 | 0 | 0 | 2 (`from`=0, `size`=20) | 4 | 2 |
| 6 | Range + boolean guard | 5 | 0 | 1 (`price_filter`) | 1 (`size`=10) | 1 | 4 |
| 7 | Source + sort defaults | 5 | 1 (`source_fields`) | 0 | 3 (`sort_field`=_score, `sort_order`=desc, `size`=10) | 2 | 3 |
| 8 | Aggregation + boolean toggle | 4 | 0 | 1 (`include_stats`) | 1 (`agg_size`=10) | 2 | 2 |
| 9 | Highlight + boost default | 4 | 0 | 0 | 3 (`title_boost`=2, `min_match`=1, `size`=10) | 1 | 3 |
| 10 | Kitchen sink (3x toJson) | 6 | 3 (`search_fields`, `categories`, `exclude_ids`) | 0 | 2 (`from`=0, `size`=25) | 4 | 2 |

**Totals across 10 templates:** 42 params extracted, 6 arrays, 2 booleans, 17 defaults captured.

---

## Feature Highlights

1. **DSL-aware descriptions**: When a variable appears near a recognizable DSL field (e.g., `"title"` in a `"match"` clause), the description includes context like `"Value for the 'title' field (match)"`.

2. **Array detection via toJson**: The `{{#toJson}}varname{{/toJson}}` helper pattern is correctly parsed from the AST. The variable name appears as plain text (WriteCode) inside the section — invisible to regex-based extraction.

3. **Default value extraction**: Inverted sections (`{{^param}}default{{/param}}`) yield both `required: false` and the actual `default` value string. Works for numeric defaults (`"0"`, `"10"`, `"2"`) and string defaults (`"best_fields"`, `"_score"`, `"desc"`).

4. **Boolean detection**: Section controllers that have no value reference inside (like `{{#include_stats}}...static content...{{/include_stats}}`) are correctly typed as `boolean` with description `"Flag to enable/disable the '...' clause"`.

5. **Params in response**: The create response now includes the full `params` map alongside the `tool_id`, so users can immediately see what was auto-generated without a separate GET call.

---

## Known Limitations (Tier 1 Heuristic)

1. **Self-guarding sections marked required**: Parameters like `category` in `{{#category}}...{{category}}...{{/category}}` are marked `required: true` because the section is at root scope. Structurally they're optional (the section disappears when empty), but without an inverted section default, the heuristic can't distinguish this from a truly required param. Tier 2 (LLM) would correctly identify these as optional.

2. **Numeric type not inferred**: `size`, `from`, `min_price` etc. are typed as `string` instead of `integer`. The preceding WriteCode text doesn't always end with the exact pattern needed for the numeric context regex. The default values (e.g., `"10"`) provide a hint that these are numeric, but the type field stays `string`.

3. **Nested section syntax**: Templates with certain nested conditional patterns (e.g., `{{#a}}...{{#b}}...{{/b}}{{/a}}` with overlapping close tags in certain configurations) can cause Mustache parse errors. The workaround is to use a boolean guard section (Test 6 pattern).

4. **Description granularity**: Generic params (`size`, `from`, `offset`) get fallback descriptions like `"Value for 'size'"`. Tier 2 (LLM) would produce better descriptions like `"Maximum number of results to return"`.
