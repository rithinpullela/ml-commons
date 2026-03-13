# Parameter Auto-Extraction Test Results

**Date:** 2026-03-12 13:00:47

**Summary:** 40 passed, 0 failed, 0 errors out of 40 tests

---

## Summary Table

| # | Template | Status | Params | Notes |
|---|----------|--------|--------|-------|
| | `t01_simple_match` | PASS | 2 |  |
| | `t02_inverted_defaults` | PASS | 3 |  |
| | `t03_tojson_array` | PASS | 3 |  |
| | `t04_multi_match_fields` | PASS | 4 |  |
| | `t05_self_guarding_sections` | PASS | 6 |  |
| | `t06_boolean_guard` | PASS | 5 |  |
| | `t07_source_sort_defaults` | PASS | 5 |  |
| | `t08_agg_boolean_toggle` | PASS | 4 |  |
| | `t09_highlight_boost` | PASS | 4 |  |
| | `t10_kitchen_sink` | PASS | 6 |  |
| | `t11_nested_path` | PASS | 4 |  |
| | `t12_function_score` | PASS | 9 |  |
| | `t13_geo_distance` | PASS | 6 |  |
| | `t14_multi_agg_pipeline` | PASS | 7 |  |
| | `t15_knn_hybrid` | PASS | 9 |  |
| | `t16_percolate` | PASS | 5 |  |
| | `t17_terms_lookup` | PASS | 7 |  |
| | `t18_script_score` | PASS | 4 |  |
| | `t19_collapse_cardinality` | PASS | 6 |  |
| | `t20_massive_ecommerce` | PASS | 15 |  |
| | `t21_join_delimiter` | PASS | 0 | Expected error: Mustache library cannot match start tag 'join delimiter=...' with end tag 'join'. OpenSearch CustomMustacheFactory handles this differently. Expected to error. |
| | `t22_join_bare` | PASS | 1 |  |
| | `t23_url_helper` | PASS | 1 |  |
| | `t24_no_variables` | PASS | 0 |  |
| | `t25_dot_notation` | PASS | 1 |  |
| | `t26_same_var_two_contexts` | PASS | 1 |  |
| | `t27_var_as_json_key` | PASS | 2 |  |
| | `t28_triple_stache` | PASS | 1 | May produce 0 params if {{{var}}} uses an unhandled Code type |
| | `t29_dot_iterator` | PASS | 1 | tag_list only has {{.}} inside, so isSectionControllerOnly stays true -> boolean. Ideally array. |
| | `t30_deep_nesting` | PASS | 4 |  |
| | `t31_duplicate_inverted` | PASS | 2 | collectInvertedSections iterates all NotIterableCode, last put wins -> default 20 |
| | `t32_special_var_names` | PASS | 3 |  |
| | `t33_only_helpers` | PASS | 3 |  |
| | `t34_section_guarded_tojson` | PASS | 2 | ids seen as section controller first, then toJson sets isArrayType=true. Required because toJson discovery sets appearsAtRootScope but not appearsInsideOwnSection. |
| | `t35_fallback_variable` | PASS | 2 | primary is self-guarding -> optional. fallback is inside the inverted section which is a child of IterableCode(primary) at depth+1, so appearsAtRootScope is false -> required false. |
| | `t36_comment_tags` | PASS | 2 |  |
| | `t37_inverted_only` | PASS | 1 | size only in inverted section. collectInvertedSections finds it and walkCodes creates a ParamInfo as section controller -> boolean with default. |
| | `t38_mixed_helpers` | PASS | 4 |  |
| | `t39_empty_inverted_default` | PASS | 2 | Inverted section is empty -> extractDefaultValue returns null -> no default field in output, but hasInvertedDefault still true -> required false |
| | `t40_repeated_var_many_places` | PASS | 2 |  |

---

## Detailed Results

### t01_simple_match: Basic match query, two bare variables at root scope

**Status:** PASS

**Extracted params:**
```json
{
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
```

---

### t02_inverted_defaults: Inverted sections providing default values for from/size

**Status:** PASS

**Extracted params:**
```json
{
  "query_text": {
    "type": "string",
    "description": "Value for the 'title' field (match)",
    "required": true
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
```

---

### t03_tojson_array: toJson helper for array detection + section guard for optional filter

**Status:** PASS

**Extracted params:**
```json
{
  "query_text": {
    "type": "string",
    "description": "Value for the 'title' field (match)",
    "required": true
  },
  "tags": {
    "type": "array",
    "description": "Value for 'tags' (array)",
    "required": false
  },
  "size": {
    "type": "number",
    "description": "Value for the 'size' field",
    "required": false,
    "default": "10"
  }
}
```

---

### t04_multi_match_fields: multi_match with toJson fields array and string default for match_type

**Status:** PASS

**Extracted params:**
```json
{
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
    "description": "Value for the 'type' field",
    "required": false,
    "default": "best_fields"
  },
  "size": {
    "type": "number",
    "description": "Value for the 'size' field",
    "required": false,
    "default": "10"
  }
}
```

---

### t05_self_guarding_sections: Self-guarding sections: {{#category}}...{{category}}...{{/category}} should be optional

**Status:** PASS

**Extracted params:**
```json
{
  "query_text": {
    "type": "string",
    "description": "Value for the 'title' field (match)",
    "required": true
  },
  "category": {
    "type": "string",
    "description": "Value for the 'category' field (term)",
    "required": false
  },
  "brand": {
    "type": "string",
    "description": "Value for the 'brand' field (term)",
    "required": false
  },
  "min_rating": {
    "type": "number",
    "description": "Value for the 'gte' field (range)",
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
    "default": "20"
  }
}
```

---

### t06_boolean_guard: Boolean guard controlling a nested block with its own variables

**Status:** PASS

**Extracted params:**
```json
{
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
    "type": "number",
    "description": "Value for the 'gte' field (range)",
    "required": false
  },
  "max_price": {
    "type": "number",
    "description": "Value for the 'lte' field",
    "required": false
  },
  "size": {
    "type": "number",
    "description": "Value for the 'size' field",
    "required": false,
    "default": "10"
  }
}
```

---

### t07_source_sort_defaults: Source filtering with toJson, sort with string defaults

**Status:** PASS

**Extracted params:**
```json
{
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
    "description": "Value for the 'order' field",
    "required": false,
    "default": "desc"
  },
  "size": {
    "type": "number",
    "description": "Value for the 'size' field",
    "required": false,
    "default": "10"
  }
}
```

---

### t08_agg_boolean_toggle: Aggregation with boolean toggle for sub-aggregation

**Status:** PASS

**Extracted params:**
```json
{
  "query_text": {
    "type": "string",
    "description": "Value for the 'title' field (match)",
    "required": true
  },
  "agg_field": {
    "type": "string",
    "description": "Value for the 'field' field (terms)",
    "required": true
  },
  "agg_size": {
    "type": "number",
    "description": "Value for the 'size' field",
    "required": false,
    "default": "10"
  },
  "include_stats": {
    "type": "boolean",
    "description": "Flag to enable/disable the 'include_stats' clause",
    "required": false
  }
}
```

---

### t09_highlight_boost: Highlight query with numeric boost default and minimum_should_match

**Status:** PASS

**Extracted params:**
```json
{
  "query_text": {
    "type": "string",
    "description": "Value for the 'query' field (match)",
    "required": true
  },
  "title_boost": {
    "type": "number",
    "description": "Value for the 'boost' field",
    "required": false,
    "default": "2"
  },
  "min_match": {
    "type": "number",
    "description": "Value for the 'minimum_should_match' field",
    "required": false,
    "default": "1"
  },
  "size": {
    "type": "number",
    "description": "Value for the 'size' field",
    "required": false,
    "default": "10"
  }
}
```

---

### t10_kitchen_sink: Kitchen sink: 3x toJson arrays, inverted defaults, section guards

**Status:** PASS

**Extracted params:**
```json
{
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
    "required": false
  },
  "exclude_ids": {
    "type": "array",
    "description": "Value for 'exclude_ids' (array)",
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
    "default": "25"
  }
}
```

---

### t11_nested_path: Nested query with inner_hits and path variable

**Status:** PASS

**Extracted params:**
```json
{
  "nested_path": {
    "type": "string",
    "description": "Value for the 'path' field",
    "required": true
  },
  "query_text": {
    "type": "string",
    "description": "Value for 'query_text'",
    "required": true
  },
  "inner_size": {
    "type": "number",
    "description": "Value for the 'size' field",
    "required": false,
    "default": "3"
  },
  "size": {
    "type": "number",
    "description": "Value for the 'size' field",
    "required": false,
    "default": "10"
  }
}
```

---

### t12_function_score: Function score with decay, self-guarding boost_mode and score_mode

**Status:** PASS

**Extracted params:**
```json
{
  "query_text": {
    "type": "string",
    "description": "Value for the 'title' field (match)",
    "required": true
  },
  "decay_field": {
    "type": "string",
    "description": "Value for 'decay_field'",
    "required": true
  },
  "origin": {
    "type": "string",
    "description": "Value for the 'origin' field",
    "required": true
  },
  "scale": {
    "type": "string",
    "description": "Value for the 'scale' field",
    "required": true
  },
  "offset": {
    "type": "string",
    "description": "Value for the 'offset' field",
    "required": false,
    "default": "0"
  },
  "decay": {
    "type": "number",
    "description": "Value for the 'decay' field",
    "required": false,
    "default": "0.5"
  },
  "boost_mode": {
    "type": "string",
    "description": "Value for the 'boost_mode' field",
    "required": false,
    "default": "multiply"
  },
  "score_mode": {
    "type": "string",
    "description": "Value for the 'score_mode' field",
    "required": false,
    "default": "multiply"
  },
  "size": {
    "type": "number",
    "description": "Value for the 'size' field",
    "required": false,
    "default": "10"
  }
}
```

---

### t13_geo_distance: Geo distance filter with self-guarding distance and unit defaults

**Status:** PASS

**Extracted params:**
```json
{
  "query_text": {
    "type": "string",
    "description": "Value for the 'title' field (match)",
    "required": true
  },
  "distance": {
    "type": "string",
    "description": "Value for the 'distance' field (filter)",
    "required": false,
    "default": "10"
  },
  "unit": {
    "type": "string",
    "description": "Value for 'unit'",
    "required": false,
    "default": "km"
  },
  "lat": {
    "type": "number",
    "description": "Value for the 'lat' field",
    "required": true
  },
  "lon": {
    "type": "number",
    "description": "Value for the 'lon' field",
    "required": true
  },
  "size": {
    "type": "number",
    "description": "Value for the 'size' field",
    "required": false,
    "default": "10"
  }
}
```

---

### t14_multi_agg_pipeline: Multiple aggs with a pipeline agg and boolean toggles

**Status:** PASS

**Extracted params:**
```json
{
  "category": {
    "type": "string",
    "description": "Value for the 'category' field (match)",
    "required": true
  },
  "date_field": {
    "type": "string",
    "description": "Value for the 'field' field",
    "required": true
  },
  "interval": {
    "type": "string",
    "description": "Value for the 'calendar_interval' field",
    "required": false,
    "default": "month"
  },
  "revenue_field": {
    "type": "string",
    "description": "Value for the 'field' field",
    "required": true
  },
  "include_avg": {
    "type": "boolean",
    "description": "Flag to enable/disable the 'include_avg' clause",
    "required": false
  },
  "price_field": {
    "type": "string",
    "description": "Value for the 'field' field",
    "required": false
  },
  "include_cumulative": {
    "type": "boolean",
    "description": "Flag to enable/disable the 'include_cumulative' clause",
    "required": false
  }
}
```

---

### t15_knn_hybrid: k-NN hybrid search with optional text query and rescore

**Status:** PASS

**Extracted params:**
```json
{
  "vector_field": {
    "type": "string",
    "description": "Value for 'vector_field'",
    "required": true
  },
  "query_vector": {
    "type": "array",
    "description": "Value for 'query_vector' (array)",
    "required": true
  },
  "k": {
    "type": "number",
    "description": "Value for the 'k' field",
    "required": false,
    "default": "10"
  },
  "text_query": {
    "type": "string",
    "description": "Value for 'text_query'",
    "required": false
  },
  "text_field": {
    "type": "string",
    "description": "Value for 'text_field'",
    "required": false
  },
  "rescore": {
    "type": "boolean",
    "description": "Flag to enable/disable the 'rescore' clause",
    "required": false
  },
  "query_weight": {
    "type": "number",
    "description": "Value for the 'query_weight' field",
    "required": false,
    "default": "0.7"
  },
  "rescore_weight": {
    "type": "number",
    "description": "Value for the 'rescore_query_weight' field",
    "required": false,
    "default": "1.2"
  },
  "size": {
    "type": "number",
    "description": "Value for the 'size' field",
    "required": false,
    "default": "10"
  }
}
```

---

### t16_percolate: Percolate query with dynamic document fields

**Status:** PASS

**Extracted params:**
```json
{
  "doc_title": {
    "type": "string",
    "description": "Value for the 'title' field",
    "required": true
  },
  "doc_body": {
    "type": "string",
    "description": "Value for the 'body' field",
    "required": true
  },
  "doc_price": {
    "type": "number",
    "description": "Value for the 'price' field",
    "required": true
  },
  "doc_tags": {
    "type": "array",
    "description": "Value for 'doc_tags' (array)",
    "required": true
  },
  "size": {
    "type": "number",
    "description": "Value for the 'size' field",
    "required": false,
    "default": "10"
  }
}
```

---

### t17_terms_lookup: Terms lookup from another index with self-guarding routing

**Status:** PASS

**Extracted params:**
```json
{
  "query_text": {
    "type": "string",
    "description": "Value for the 'title' field (match)",
    "required": true
  },
  "filter_field": {
    "type": "string",
    "description": "Value for 'filter_field'",
    "required": true
  },
  "lookup_index": {
    "type": "string",
    "description": "Value for the 'index' field",
    "required": true
  },
  "lookup_id": {
    "type": "string",
    "description": "Value for the 'id' field",
    "required": true
  },
  "lookup_path": {
    "type": "string",
    "description": "Value for the 'path' field",
    "required": true
  },
  "routing": {
    "type": "string",
    "description": "Value for the 'routing' field",
    "required": false
  },
  "size": {
    "type": "number",
    "description": "Value for the 'size' field",
    "required": false,
    "default": "10"
  }
}
```

---

### t18_script_score: Script score with inline painless script and params

**Status:** PASS

**Extracted params:**
```json
{
  "query_text": {
    "type": "string",
    "description": "Value for the 'title' field (match)",
    "required": true
  },
  "weight": {
    "type": "number",
    "description": "Value for the 'weight' field",
    "required": false,
    "default": "1.0"
  },
  "min_score": {
    "type": "number",
    "description": "Value for the 'min_score' field",
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
```

---

### t19_collapse_cardinality: Field collapsing with inner_hits and cardinality agg

**Status:** PASS

**Extracted params:**
```json
{
  "query_text": {
    "type": "string",
    "description": "Value for the 'title' field (match)",
    "required": true
  },
  "collapse_field": {
    "type": "string",
    "description": "Value for the 'field' field",
    "required": true
  },
  "inner_size": {
    "type": "number",
    "description": "Value for the 'size' field",
    "required": false,
    "default": "3"
  },
  "inner_sort": {
    "type": "string",
    "description": "Value for 'inner_sort'",
    "required": false,
    "default": "_score"
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
```

---

### t20_massive_ecommerce: Large e-commerce search: text query, filters, facets, sort, pagination, highlights

**Status:** PASS

**Extracted params:**
```json
{
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
  "match_type": {
    "type": "string",
    "description": "Value for the 'type' field",
    "required": false,
    "default": "cross_fields"
  },
  "category": {
    "type": "string",
    "description": "Value for the 'category.keyword' field (term)",
    "required": false
  },
  "brand": {
    "type": "string",
    "description": "Value for the 'brand.keyword' field (term)",
    "required": false
  },
  "price_filter": {
    "type": "boolean",
    "description": "Flag to enable/disable the 'price_filter' clause",
    "required": false
  },
  "min_price": {
    "type": "number",
    "description": "Value for the 'gte' field (range)",
    "required": false
  },
  "max_price": {
    "type": "number",
    "description": "Value for the 'lte' field",
    "required": false
  },
  "in_stock": {
    "type": "boolean",
    "description": "Flag to enable/disable the 'in_stock' clause",
    "required": false
  },
  "exclude_ids": {
    "type": "array",
    "description": "Value for 'exclude_ids' (array)",
    "required": false
  },
  "facet_size": {
    "type": "number",
    "description": "Value for the 'size' field (terms)",
    "required": false,
    "default": "20"
  },
  "sort_field": {
    "type": "string",
    "description": "Value for 'sort_field'",
    "required": false,
    "default": "_score"
  },
  "sort_order": {
    "type": "string",
    "description": "Value for the 'order' field",
    "required": false,
    "default": "desc"
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
    "default": "20"
  }
}
```

---

### t21_join_delimiter: join helper with explicit delimiter syntax (KNOWN LIMITATION: DefaultMustacheFactory mismatched tags)

**Status:** PASS

**Note:** Expected error: Mustache library cannot match start tag 'join delimiter=...' with end tag 'join'. OpenSearch CustomMustacheFactory handles this differently. Expected to error.

**Extracted params:**
```json
{}
```

---

### t22_join_bare: Bare join helper without delimiter argument

**Status:** PASS

**Extracted params:**
```json
{
  "tag_list": {
    "type": "array",
    "description": "Value for 'tag_list' (array)",
    "required": true
  }
}
```

---

### t23_url_helper: url helper — transparent pass-through, inner variable should be discovered

**Status:** PASS

**Extracted params:**
```json
{
  "query_text": {
    "type": "string",
    "description": "Value for 'query_text'",
    "required": true
  }
}
```

---

### t24_no_variables: Static template with no Mustache variables — should extract zero params

**Status:** PASS

**Extracted params:**
```json
{}
```

---

### t25_dot_notation: Dot-notation variables collapse to root name

**Status:** PASS

**Extracted params:**
```json
{
  "user": {
    "type": "string",
    "description": "Value for the 'name' field (match)",
    "required": true
  }
}
```

---

### t26_same_var_two_contexts: Same variable in quoted (string) and unquoted (number) context — first-seen wins

**Status:** PASS

**Extracted params:**
```json
{
  "threshold": {
    "type": "string",
    "description": "Value for the 'title' field (match)",
    "required": true
  }
}
```

---

### t27_var_as_json_key: Variable used as a JSON object key (field name position)

**Status:** PASS

**Extracted params:**
```json
{
  "field_name": {
    "type": "string",
    "description": "Value for 'field_name'",
    "required": true
  },
  "field_value": {
    "type": "string",
    "description": "Value for 'field_value'",
    "required": true
  }
}
```

---

### t28_triple_stache: Triple-stache {{{var}}} for unescaped output — may or may not be recognized

**Status:** PASS

**Note:** May produce 0 params if {{{var}}} uses an unhandled Code type

**Extracted params:**
```json
{
  "raw_query": {
    "type": "string",
    "description": "Value for the 'title' field (match)",
    "required": true
  }
}
```

---

### t29_dot_iterator: Implicit iterator {{.}} inside section — section becomes boolean (known limitation)

**Status:** PASS

**Note:** tag_list only has {{.}} inside, so isSectionControllerOnly stays true -> boolean. Ideally array.

**Extracted params:**
```json
{
  "tag_list": {
    "type": "boolean",
    "description": "Flag to enable/disable the 'tag_list' clause",
    "required": false
  }
}
```

---

### t30_deep_nesting: 3 levels of nesting — depth counter and required/optional at deep scope

**Status:** PASS

**Extracted params:**
```json
{
  "query_text": {
    "type": "string",
    "description": "Value for the 'title' field (match)",
    "required": true
  },
  "filters": {
    "type": "boolean",
    "description": "Flag to enable/disable the 'filters' clause",
    "required": false
  },
  "price_range": {
    "type": "boolean",
    "description": "Flag to enable/disable the 'price_range' clause",
    "required": false
  },
  "min": {
    "type": "number",
    "description": "Value for the 'gte' field (range)",
    "required": false,
    "default": "0"
  }
}
```

---

### t31_duplicate_inverted: Same variable with two inverted sections with different defaults — last wins

**Status:** PASS

**Note:** collectInvertedSections iterates all NotIterableCode, last put wins -> default 20

**Extracted params:**
```json
{
  "query_text": {
    "type": "string",
    "description": "Value for the 'title' field (match)",
    "required": true
  },
  "size": {
    "type": "number",
    "description": "Value for the 'size' field",
    "required": false,
    "default": "20"
  }
}
```

---

### t32_special_var_names: Variable names with underscores and numbers

**Status:** PASS

**Extracted params:**
```json
{
  "field_1": {
    "type": "string",
    "description": "Value for 'field_1'",
    "required": true
  },
  "_query": {
    "type": "string",
    "description": "Value for '_query'",
    "required": true
  },
  "result_2_count": {
    "type": "number",
    "description": "Value for the 'size' field",
    "required": true
  }
}
```

---

### t33_only_helpers: Template with only toJson helpers and self-guarding sections, no bare {{var}}

**Status:** PASS

**Extracted params:**
```json
{
  "tags": {
    "type": "array",
    "description": "Value for 'tags' (array)",
    "required": true
  },
  "fields": {
    "type": "array",
    "description": "Value for 'fields' (array)",
    "required": true
  },
  "size": {
    "type": "number",
    "description": "Value for the 'size' field",
    "required": false,
    "default": "5"
  }
}
```

---

### t34_section_guarded_tojson: Section guard wrapping toJson — {{#ids}}...{{#toJson}}ids{{/toJson}}...{{/ids}}

**Status:** PASS

**Note:** ids seen as section controller first, then toJson sets isArrayType=true. Required because toJson discovery sets appearsAtRootScope but not appearsInsideOwnSection.

**Extracted params:**
```json
{
  "query_text": {
    "type": "string",
    "description": "Value for the 'title' field (match)",
    "required": true
  },
  "ids": {
    "type": "array",
    "description": "Value for 'ids' (array)",
    "required": false
  }
}
```

---

### t35_fallback_variable: Inverted section with variable inside (fallback pattern) instead of literal

**Status:** PASS

**Note:** primary is self-guarding -> optional. fallback is inside the inverted section which is a child of IterableCode(primary) at depth+1, so appearsAtRootScope is false -> required false.

**Extracted params:**
```json
{
  "primary": {
    "type": "string",
    "description": "Value for the 'title' field (match)",
    "required": false
  },
  "fallback": {
    "type": "string",
    "description": "Value for 'fallback'",
    "required": false
  }
}
```

---

### t36_comment_tags: Mustache comment tags {{! comment }} should be ignored

**Status:** PASS

**Extracted params:**
```json
{
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
```

---

### t37_inverted_only: Variable appears ONLY in inverted section — no ParamInfo created, default lost

**Status:** PASS

**Note:** size only in inverted section. collectInvertedSections finds it and walkCodes creates a ParamInfo as section controller -> boolean with default.

**Extracted params:**
```json
{
  "size": {
    "type": "boolean",
    "description": "Flag to enable/disable the 'size' clause",
    "required": false,
    "default": "10"
  }
}
```

---

### t38_mixed_helpers: Multiple helper types in one template: toJson + join + url

**Status:** PASS

**Extracted params:**
```json
{
  "query_text": {
    "type": "string",
    "description": "Value for 'query_text'",
    "required": true
  },
  "tags": {
    "type": "array",
    "description": "Value for 'tags' (array)",
    "required": true
  },
  "cat_list": {
    "type": "array",
    "description": "Value for 'cat_list' (array)",
    "required": true
  },
  "size": {
    "type": "number",
    "description": "Value for the 'size' field",
    "required": false,
    "default": "10"
  }
}
```

---

### t39_empty_inverted_default: Inverted section with empty content — default should be null

**Status:** PASS

**Note:** Inverted section is empty -> extractDefaultValue returns null -> no default field in output, but hasInvertedDefault still true -> required false

**Extracted params:**
```json
{
  "query_text": {
    "type": "string",
    "description": "Value for the 'title' field (match)",
    "required": true
  },
  "size": {
    "type": "number",
    "description": "Value for the 'size' field",
    "required": false
  }
}
```

---

### t40_repeated_var_many_places: Same variable used 4+ times across different query clauses

**Status:** PASS

**Extracted params:**
```json
{
  "q": {
    "type": "string",
    "description": "Value for the 'title' field (match)",
    "required": true
  },
  "size": {
    "type": "number",
    "description": "Value for the 'size' field",
    "required": false,
    "default": "10"
  }
}
```

---

