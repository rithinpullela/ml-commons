# Parameter Auto-Generation Test Results

Tested on OpenSearch 3.6.0-SNAPSHOT with ml-commons plugin (feature/custom-tools branch).

---

## Test Environment

- Cluster: `integTest` (single node, localhost:9200)
- OpenSearch: 3.6.0-SNAPSHOT
- ml-commons: feature/custom-tools branch
- Mode 2 Model: Bedrock Claude Sonnet (`us.anthropic.claude-sonnet-4-20250514-v1:0`) via Converse API
- Creation response includes `tool_id` + `params`
- Default values extracted from inverted sections

---

## Mode 1 & Mode 2 Tests: All 10 Templates

### Test 1: Simple — Basic Variables Only

**Template (`t1_simple`):**

```mustache
{
  "query": {
    "match": {
      "title": "{{query_text}}"
    }
  },
  "size": {{result_size}}
}
```

**Mode 1 Response (auto extraction):**

```json
{
  "tool_id": "0KG_Bp0BXkQCQSaY3g_T",
  "params": {
    "query_text": {
      "type": "string",
      "description": "Value for the 'title' field (match)",
      "required": true
    },
    "result_size": {
      "type": "number",
      "description": "Value for the 'size' field",
      "required": true
    }
  }
}
```

**Mode 2 Response (LLM-enhanced):**

```json
{
  "tool_id": "2qHABp0BXkQCQSaYDw-9",
  "params": {
    "query_text": {
      "type": "string",
      "description": "The text to search for in the document title field using match query",
      "required": true
    },
    "result_size": {
      "type": "number",
      "description": "The maximum number of search results to return in the response",
      "required": true
    }
  }
}
```

**Verdict:** Both params required (no defaults). `result_size` correctly typed as `number`. Mode 2 provides human-quality descriptions.

---

### Test 2: Inverted Section Defaults

**Template (`t2_inverted`):**

```mustache
{
  "query": {
    "match": {
      "title": "{{query_text}}"
    }
  },
  "from": {{#from}}{{from}}{{/from}}{{^from}}0{{/from}},
  "size": {{#size}}{{size}}{{/size}}{{^size}}10{{/size}}
}
```

| Param | Type | Required | Default | Mode 1 Description | Mode 2 Description |
|---|---|---|---|---|---|
| `query_text` | string | true | — | Value for the 'title' field (match) | The text to search for within document titles using a match query |
| `from` | number | false | 0 | Value for the 'from' field | The starting position (offset) for pagination, specifying how many results to skip before returning matches |
| `size` | number | false | 10 | Value for the 'size' field | The maximum number of search results to return in the response |

**Verdict:** `from` and `size` correctly marked `required: false` with defaults extracted from inverted sections.

---

### Test 3: Section Guard + toJson Array

**Template (`t3_section_tojson`):**

```mustache
{
  "query": {
    "bool": {
      "must": [
        { "match": { "title": "{{query_text}}" } }
      ]
      {{#tags}},
      "filter": [
        { "terms": { "tags": {{#toJson}}tags{{/toJson}} } }
      ]
      {{/tags}}
    }
  },
  "size": {{#size}}{{size}}{{/size}}{{^size}}10{{/size}}
}
```

| Param | Type | Required | Default | Mode 1 Description | Mode 2 Description |
|---|---|---|---|---|---|
| `query_text` | string | true | — | Value for the 'title' field (match) | The search term to match against document titles using full-text search |
| `tags` | **array** | false | — | Value for 'tags' (array) | An array of tag values to filter results by, requiring documents to have at least one of the specified tags |
| `size` | number | false | 10 | Value for the 'size' field | The maximum number of search results to return in the response |

**Verdict:** `tags` correctly detected as `array` from `{{#toJson}}tags{{/toJson}}`. This is a key win over regex — the variable name is plain text inside a section, invisible to regex.

---

### Test 4: Multi-Match with Fields Array + Default Type

**Template (`t4_multi_match`):**

```mustache
{
  "query": {
    "multi_match": {
      "query": "{{query_text}}",
      "fields": {{#toJson}}fields{{/toJson}},
      "type": "{{#match_type}}{{match_type}}{{/match_type}}{{^match_type}}best_fields{{/match_type}}"
    }
  },
  "size": {{#size}}{{size}}{{/size}}{{^size}}10{{/size}}
}
```

| Param | Type | Required | Default | Mode 1 Description | Mode 2 Description |
|---|---|---|---|---|---|
| `query_text` | string | true | — | Value for the 'query' field | The search text or phrase to match against the specified fields in the documents |
| `fields` | **array** | true | — | Value for 'fields' (array) | The array of field names to search across when executing the multi-match query |
| `match_type` | string | false | best_fields | Value for the 'type' field | The multi-match query type that determines how the query is executed across multiple fields (defaults to 'best_fields') |
| `size` | number | false | 10 | Value for the 'size' field | The maximum number of search results to return in the response (defaults to 10) |

**Verdict:** `fields` correctly detected as `array`. `match_type` optional with default `"best_fields"`. String defaults work for non-numeric values.

---

### Test 5: Complex Bool — Multiple Optional Clauses

**Template (`t5_complex_bool`):**

```mustache
{
  "query": {
    "bool": {
      "must": [
        { "match": { "title": "{{query_text}}" } }
      ]
      {{#category}},
      "filter": [
        { "term": { "category": "{{category}}" } }
      ]
      {{/category}}
      {{#brand}},
      "must": [
        { "term": { "brand": "{{brand}}" } }
      ]
      {{/brand}}
      {{#min_rating}},
      "filter": [
        { "range": { "rating": { "gte": {{min_rating}} } } }
      ]
      {{/min_rating}}
    }
  },
  "from": {{#from}}{{from}}{{/from}}{{^from}}0{{/from}},
  "size": {{#size}}{{size}}{{/size}}{{^size}}20{{/size}}
}
```

| Param | Type | Required | Default | Mode 1 Description | Mode 2 Description |
|---|---|---|---|---|---|
| `query_text` | string | true | — | Value for the 'title' field (match) | The search text to match against product titles in the search results |
| `category` | string | **false** | — | Value for the 'category' field (term) | Filter results to only include products from a specific category |
| `brand` | string | **false** | — | Value for the 'brand' field (term) | Filter results to only include products from a specific brand |
| `min_rating` | number | **false** | — | Value for the 'gte' field (range) | Filter results to only include products with a rating equal to or greater than this value |
| `from` | number | false | 0 | Value for the 'from' field | The starting position for pagination, indicating how many results to skip before returning matches |
| `size` | number | false | 20 | Value for the 'size' field | The maximum number of search results to return in the response |

**Verdict:** 6 params extracted. `category`, `brand`, `min_rating` correctly `required: false` (self-guarding sections — the section disappears when the param is absent). This is an improvement over the previous results which incorrectly marked them as required. The LLM descriptions add domain context ("products", "rating").

---

### Test 6: Range with Boolean Guard

**Template (`t6_range`):**

```mustache
{
  "query": {
    "bool": {
      "must": [
        { "match": { "title": "{{query_text}}" } }
      ]
      {{#price_filter}},
      "filter": [
        {
          "range": {
            "price": {
              "gte": {{min_price}},
              "lte": {{max_price}}
            }
          }
        }
      ]
      {{/price_filter}}
    }
  },
  "size": {{#size}}{{size}}{{/size}}{{^size}}10{{/size}}
}
```

| Param | Type | Required | Default | Mode 1 Description | Mode 2 Description |
|---|---|---|---|---|---|
| `query_text` | string | true | — | Value for the 'title' field (match) | The search term used to match against document titles in the search query |
| `price_filter` | **boolean** | false | — | Flag to enable/disable the 'price_filter' clause | Controls whether to apply price range filtering to the search results |
| `min_price` | number | false | — | Value for the 'gte' field (range) | The minimum price threshold when price filtering is enabled |
| `max_price` | number | false | — | Value for the 'lte' field | The maximum price threshold when price filtering is enabled |
| `size` | number | false | 10 | Value for the 'size' field | The maximum number of search results to return (defaults to 10 if not specified) |

**Verdict:** `price_filter` correctly detected as `boolean` (section controller with no `{{price_filter}}` value usage inside). `min_price` and `max_price` correctly optional (nested inside the `price_filter` section). The LLM descriptions add semantic context: "minimum price threshold when price filtering is enabled".

---

### Test 7: Source Filtering + Sort with Defaults

**Template (`t7_source_sort`):**

```mustache
{
  "query": {
    "match": {
      "title": "{{query_text}}"
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
  "size": {{#size}}{{size}}{{/size}}{{^size}}10{{/size}}
}
```

| Param | Type | Required | Default | Mode 1 Description | Mode 2 Description |
|---|---|---|---|---|---|
| `query_text` | string | true | — | Value for the 'title' field (match) | The text to search for in the document title field using match query |
| `source_fields` | **array** | true | — | Value for 'source_fields' (array) | List of document fields to include in the search results response |
| `sort_field` | string | false | _score | Value for 'sort_field' | The document field to sort the search results by (defaults to relevance score) |
| `sort_order` | string | false | desc | Value for the 'order' field | The sorting direction for results, either 'asc' for ascending or 'desc' for descending |
| `size` | number | false | 10 | Value for the 'size' field | The maximum number of search results to return in the response |

**Verdict:** `source_fields` correctly detected as `array` (via toJson). Sort defaults extracted perfectly: `sort_field` defaults to `"_score"`, `sort_order` defaults to `"desc"`. Shows non-numeric string defaults work correctly. The LLM adds semantic clarity: "defaults to relevance score", "'asc' for ascending or 'desc' for descending".

---

### Test 8: Aggregation with Boolean Toggle

**Template (`t8_aggs`):**

```mustache
{
  "size": 0,
  "query": {
    "match": {
      "title": "{{query_text}}"
    }
  },
  "aggs": {
    "by_category": {
      "terms": {
        "field": "{{agg_field}}",
        "size": {{#agg_size}}{{agg_size}}{{/agg_size}}{{^agg_size}}10{{/agg_size}}
      }
      {{#include_stats}},
      "aggs": {
        "price_stats": {
          "stats": { "field": "price" }
        }
      }
      {{/include_stats}}
    }
  }
}
```

| Param | Type | Required | Default | Mode 1 Description | Mode 2 Description |
|---|---|---|---|---|---|
| `query_text` | string | true | — | Value for the 'title' field (match) | The search text to match against document titles using a full-text match query |
| `agg_field` | string | true | — | Value for the 'field' field (terms) | The field name to group documents by in the terms aggregation (e.g., "category", "brand", "status") |
| `agg_size` | number | false | 10 | Value for the 'size' field | The maximum number of buckets to return in the terms aggregation, defaults to 10 if not specified |
| `include_stats` | **boolean** | false | — | Flag to enable/disable the 'include_stats' clause | Whether to include statistical calculations (min, max, avg, sum, count) on the price field for each aggregation bucket |

**Verdict:** `include_stats` correctly detected as `boolean` — section controller with no value reference inside, just static JSON. The LLM description is excellent: "statistical calculations (min, max, avg, sum, count) on the price field" — it understands the `stats` aggregation semantics.

---

### Test 9: Highlight with Boost Default

**Template (`t9_highlight`):**

```mustache
{
  "query": {
    "bool": {
      "should": [
        {
          "match": {
            "title": {
              "query": "{{query_text}}",
              "boost": {{#title_boost}}{{title_boost}}{{/title_boost}}{{^title_boost}}2{{/title_boost}}
            }
          }
        },
        { "match": { "description": "{{query_text}}" } }
      ],
      "minimum_should_match": {{#min_match}}{{min_match}}{{/min_match}}{{^min_match}}1{{/min_match}}
    }
  },
  "highlight": {
    "fields": { "title": {}, "description": {} }
  },
  "size": {{#size}}{{size}}{{/size}}{{^size}}10{{/size}}
}
```

| Param | Type | Required | Default | Mode 1 Description | Mode 2 Description |
|---|---|---|---|---|---|
| `query_text` | string | true | — | Value for the 'query' field (match) | The search term or phrase to match against both title and description fields |
| `title_boost` | number | false | 2 | Value for the 'boost' field | The relevance multiplier applied to title matches to increase their importance in scoring |
| `min_match` | number | false | 1 | Value for the 'minimum_should_match' field | The minimum number of should clauses that must match for a document to be considered a hit |
| `size` | number | false | 10 | Value for the 'size' field | The maximum number of search results to return in the response |

**Verdict:** `title_boost` correctly optional with default `"2"`. `min_match` defaults to `"1"`. `query_text` deduplicated despite appearing in two `match` clauses. The LLM descriptions add domain knowledge: "relevance multiplier", "should clauses that must match".

---

### Test 10: Kitchen Sink — Multiple toJson Arrays + Inverted Defaults

**Template (`t10_kitchen_sink`):**

```mustache
{
  "query": {
    "bool": {
      "must": [
        {
          "multi_match": {
            "query": "{{query_text}}",
            "fields": {{#toJson}}search_fields{{/toJson}}
          }
        }
      ]
      {{#categories}},
      "filter": [
        { "terms": { "category": {{#toJson}}categories{{/toJson}} } }
      ]
      {{/categories}}
      {{#exclude_ids}},
      "must_not": [
        { "ids": { "values": {{#toJson}}exclude_ids{{/toJson}} } }
      ]
      {{/exclude_ids}}
    }
  },
  "from": {{#from}}{{from}}{{/from}}{{^from}}0{{/from}},
  "size": {{#size}}{{size}}{{/size}}{{^size}}25{{/size}}
}
```

| Param | Type | Required | Default | Mode 1 Description | Mode 2 Description |
|---|---|---|---|---|---|
| `query_text` | string | true | — | Value for the 'query' field (must) | The search text or keywords to match against the specified search fields |
| `search_fields` | **array** | true | — | Value for 'search_fields' (array) | Array of field names to search within for the query text using multi-match query |
| `categories` | **array** | false | — | Value for 'categories' (array) | Array of category values to filter results by, restricting matches to documents in these categories |
| `exclude_ids` | **array** | false | — | Value for 'exclude_ids' (array) | Array of document IDs to exclude from the search results |
| `from` | number | false | 0 | Value for the 'from' field | Starting position for pagination, indicating how many results to skip from the beginning |
| `size` | number | false | 25 | Value for the 'size' field | Maximum number of search results to return in the response |

**Verdict:** 6 params extracted from the most complex template. Three `toJson` arrays all correctly detected. `categories` and `exclude_ids` correctly `required: false` (self-guarding sections). The LLM descriptions provide actionable context: "Array of document IDs to exclude", "Array of category values to filter results by".

---

## Mode 3 Test: Manual Params

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

**Result:** Params stored exactly as-is. No auto-extraction. Template existence validated. User's custom types (`text`, `integer`) and descriptions preserved verbatim.

---

## Validation Tests

| Test | Input | Expected Error | Got |
|------|-------|---------------|-----|
| Mutual exclusion | `params` + `model_id` both set | 400 | `"Cannot specify both 'params' and 'model_id'..."` |
| Missing llm_interface | `model_id` without `llm_interface` | 400 | `"'llm_interface' is required when 'model_id' is provided for LLM enrichment"` |
| Non-existent template | No params, bad template name | 400 | `"Search template '...' not found"` |
| Duplicate name | Name already exists | 400 | `"A custom tool with name '...' already exists"` |

---

## Summary Table: All 10 Templates

| # | Template | Params | Arrays | Booleans | Defaults | Required | Optional |
|---|----------|:---:|:---:|:---:|:---:|:---:|:---:|
| 1 | Simple match | 2 | 0 | 0 | 0 | 2 | 0 |
| 2 | Inverted defaults | 3 | 0 | 0 | 2 (`from`=0, `size`=10) | 1 | 2 |
| 3 | Section + toJson | 3 | 1 (`tags`) | 0 | 1 (`size`=10) | 1 | 2 |
| 4 | Multi-match + fields array | 4 | 1 (`fields`) | 0 | 2 (`match_type`=best_fields, `size`=10) | 2 | 2 |
| 5 | Complex bool | 6 | 0 | 0 | 2 (`from`=0, `size`=20) | 1 | 5 |
| 6 | Range + boolean guard | 5 | 0 | 1 (`price_filter`) | 1 (`size`=10) | 1 | 4 |
| 7 | Source + sort defaults | 5 | 1 (`source_fields`) | 0 | 3 (`sort_field`=_score, `sort_order`=desc, `size`=10) | 2 | 3 |
| 8 | Aggregation + boolean toggle | 4 | 0 | 1 (`include_stats`) | 1 (`agg_size`=10) | 2 | 2 |
| 9 | Highlight + boost default | 4 | 0 | 0 | 3 (`title_boost`=2, `min_match`=1, `size`=10) | 1 | 3 |
| 10 | Kitchen sink (3x toJson) | 6 | 3 (`search_fields`, `categories`, `exclude_ids`) | 0 | 2 (`from`=0, `size`=25) | 2 | 4 |

**Totals across 10 templates:** 42 params extracted, 6 arrays, 2 booleans, 17 defaults captured.

---

## Mode 1 vs. Mode 2 Description Quality Comparison

The following highlights show the qualitative difference between programmatic (Mode 1) and LLM-enhanced (Mode 2) descriptions across all 10 templates:

| Param | Mode 1 (Heuristic) | Mode 2 (LLM) |
|---|---|---|
| `result_size` | Value for the 'size' field | The maximum number of search results to return in the response |
| `from` | Value for the 'from' field | The starting position (offset) for pagination, specifying how many results to skip |
| `tags` | Value for 'tags' (array) | An array of tag values to filter results by, requiring documents to have at least one of the specified tags |
| `match_type` | Value for the 'type' field | The multi-match query type that determines how the query is executed across multiple fields |
| `price_filter` | Flag to enable/disable the 'price_filter' clause | Controls whether to apply price range filtering to the search results |
| `min_price` | Value for the 'gte' field (range) | The minimum price threshold when price filtering is enabled |
| `source_fields` | Value for 'source_fields' (array) | List of document fields to include in the search results response |
| `sort_field` | Value for 'sort_field' | The document field to sort the search results by (defaults to relevance score) |
| `include_stats` | Flag to enable/disable the 'include_stats' clause | Whether to include statistical calculations (min, max, avg, sum, count) on the price field |
| `title_boost` | Value for the 'boost' field | The relevance multiplier applied to title matches to increase their importance in scoring |
| `exclude_ids` | Value for 'exclude_ids' (array) | Array of document IDs to exclude from the search results |

**Key observations:**
- Mode 1 descriptions are functional but mechanical — they reference field names and query types from the DSL
- Mode 2 descriptions are semantic and actionable — they explain what the parameter *does*, not just where it appears
- Types and required/optional stay identical between modes — the LLM only enhances descriptions
- Defaults are preserved identically — the AST extraction handles all structural properties

---

## Feature Highlights

1. **DSL-aware descriptions**: When a variable appears near a recognizable DSL field (e.g., `"title"` in a `"match"` clause), the description includes context like `"Value for the 'title' field (match)"`.

2. **Array detection via toJson**: The `{{#toJson}}varname{{/toJson}}` helper pattern is correctly parsed from the AST. The variable name appears as plain text (WriteCode) inside the section — invisible to regex-based extraction.

3. **Default value extraction**: Inverted sections (`{{^param}}default{{/param}}`) yield both `required: false` and the actual `default` value string. Works for numeric defaults (`"0"`, `"10"`, `"2"`) and string defaults (`"best_fields"`, `"_score"`, `"desc"`).

4. **Boolean detection**: Section controllers that have no value reference inside (like `{{#include_stats}}...static content...{{/include_stats}}`) are correctly typed as `boolean`.

5. **Numeric type inference**: Variables in unquoted positions (`"size":{{var}}`, `"gte":{{var}}`) are typed as `number`. Variables in quoted positions (`"title":"{{var}}"`) are typed as `string`.

6. **Self-guarding section detection**: Parameters like `category` in `{{#category}}...{{category}}...{{/category}}` are correctly identified as optional — the section disappears when the param is absent.

7. **LLM enrichment via forced tool call**: Mode 2 uses function calling with `tool_choice: required` to get structured descriptions. The LLM is constrained to return exactly one description per parameter, eliminating hallucinated extras.

---

## Improvements Over Previous Results

| Area | Previous | Current |
|---|---|---|
| Numeric type inference | `size`, `from`, `min_price` typed as `string` | Now correctly typed as `number` |
| Self-guarding sections | `category`, `brand`, `min_rating` incorrectly `required: true` | Now correctly `required: false` |
| Mode 2 (LLM) | Not implemented (placeholder) | Fully working with Bedrock Converse via forced tool call |
| Description quality | Heuristic only | Both heuristic (Mode 1) and LLM-enhanced (Mode 2) available |
