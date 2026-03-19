# OpenSearch Search Template Examples

A comprehensive collection of diverse search templates demonstrating real-world use cases
and the full breadth of Mustache syntax supported in OpenSearch.

## Mustache Features Reference

| Feature | Syntax | Description |
|---------|--------|-------------|
| Variable substitution | `{{var}}` | Replace with parameter value |
| Raw JSON injection | `{{{var}}}` | Insert without escaping (useful for raw JSON) |
| Conditional section | `{{#flag}}...{{/flag}}` | Render block if flag is truthy/non-empty |
| Inverted section | `{{^flag}}...{{/flag}}` | Render block if flag is falsy/missing |
| Default values | `{{var}}{{^var}}default{{/var}}` | Use default when var is not provided |
| JSON conversion | `{{#toJson}}var{{/toJson}}` | Convert arrays/objects to JSON |
| Join arrays | `{{#join}}var{{/join}}` | Join array as comma-separated string |
| Custom join delimiter | `{{#join delimiter='\|\|'}}var{{/join delimiter='\|\|'}}` | Join with custom separator |
| URL encoding | `{{#url}}var{{/url}}` | URL-encode a value |

---

## 1. Simple Full-Text Match

**Use case:** Basic product search by keyword.

**Mustache features:** Simple variable substitution, inverted section for default field.

```mustache
{
  "query": {
    "match": {
      "{{^field}}title{{/field}}{{field}}": {
        "query": "{{query_string}}",
        "operator": "{{^operator}}or{{/operator}}{{operator}}"
      }
    }
  }
}
```

---

## 2. Term Query with Exact Match

**Use case:** Look up a product by its exact SKU or status code.

**Mustache features:** Simple variable substitution.

```mustache
{
  "query": {
    "term": {
      "{{field}}": {
        "value": "{{value}}"
      }
    }
  }
}
```

---

## 3. Bool Query with Must/Should/Filter

**Use case:** E-commerce product search with category filter, keyword matching, and optional brand boost.

**Mustache features:** Conditional sections for optional clauses, `{{#toJson}}` for arrays.

```mustache
{
  "query": {
    "bool": {
      "must": [
        {
          "match": {
            "title": "{{query_string}}"
          }
        }
      ],
      "filter": [
        {{#category}}
        {
          "term": {
            "category.keyword": "{{category}}"
          }
        }
        {{/category}}
        {{#price_min}}
        ,{
          "range": {
            "price": {
              "gte": {{price_min}},
              "lte": {{price_max}}
            }
          }
        }
        {{/price_min}}
      ],
      "should": [
        {{#brand}}
        {
          "term": {
            "brand.keyword": {
              "value": "{{brand}}",
              "boost": 2.0
            }
          }
        }
        {{/brand}}
      ],
      "minimum_should_match": 0
    }
  }
}
```

---

## 4. Multi-Match with Configurable Type

**Use case:** Search across multiple fields (title, description, tags) with selectable matching strategy.

**Mustache features:** `{{#toJson}}` for field arrays, inverted section for default type.

```mustache
{
  "query": {
    "multi_match": {
      "query": "{{query_string}}",
      "fields": {{#toJson}}fields{{/toJson}},
      "type": "{{^type}}best_fields{{/type}}{{type}}",
      "tie_breaker": {{^tie_breaker}}0.3{{/tie_breaker}}{{tie_breaker}}
    }
  }
}
```

---

## 5. Cross-Fields Multi-Match for People Search

**Use case:** Search for people by name across first_name, last_name, and full_name fields.

**Mustache features:** Conditional sections for optional filters, inverted sections for defaults.

```mustache
{
  "query": {
    "bool": {
      "must": [
        {
          "multi_match": {
            "query": "{{name_query}}",
            "fields": ["first_name^2", "last_name^2", "full_name^3", "email"],
            "type": "cross_fields",
            "operator": "and"
          }
        }
      ]
      {{#department}}
      ,"filter": [
        {
          "term": {
            "department.keyword": "{{department}}"
          }
        }
      ]
      {{/department}}
    }
  },
  "_source": ["first_name", "last_name", "email", "department", "title", "phone"],
  "size": {{^size}}10{{/size}}{{size}}
}
```

---

## 6. Date Range Query for Log Search

**Use case:** Observability/log search within a time window with severity filter.

**Mustache features:** Conditional sections, inverted sections for defaults, multiple optional filters.

```mustache
{
  "query": {
    "bool": {
      "must": [
        {
          "range": {
            "@timestamp": {
              "gte": "{{start_time}}",
              "lte": "{{^end_time}}now{{/end_time}}{{end_time}}",
              "format": "{{^date_format}}strict_date_optional_time{{/date_format}}{{date_format}}"
            }
          }
        }
        {{#message}}
        ,{
          "match_phrase": {
            "message": "{{message}}"
          }
        }
        {{/message}}
      ]
      {{#severity}}
      ,"filter": [
        {
          "terms": {
            "severity.keyword": {{#toJson}}severity{{/toJson}}
          }
        }
      ]
      {{/severity}}
      {{#exclude_service}}
      ,"must_not": [
        {
          "term": {
            "service.name.keyword": "{{exclude_service}}"
          }
        }
      ]
      {{/exclude_service}}
    }
  },
  "sort": [
    { "@timestamp": { "order": "desc" } }
  ],
  "size": {{^size}}50{{/size}}{{size}}
}
```

---

## 7. Geo Distance Search for Nearby Stores

**Use case:** Find stores/restaurants within a radius of a given location.

**Mustache features:** Variable substitution, inverted sections for defaults, conditional sort.

```mustache
{
  "query": {
    "bool": {
      "must": [
        {
          "match_all": {}
        }
      ],
      "filter": [
        {
          "geo_distance": {
            "distance": "{{^distance}}10km{{/distance}}{{distance}}",
            "location": {
              "lat": {{lat}},
              "lon": {{lon}}
            }
          }
        }
        {{#store_type}}
        ,{
          "term": {
            "store_type.keyword": "{{store_type}}"
          }
        }
        {{/store_type}}
        {{#is_open}}
        ,{
          "term": {
            "is_open": true
          }
        }
        {{/is_open}}
      ]
    }
  },
  "sort": [
    {
      "_geo_distance": {
        "location": {
          "lat": {{lat}},
          "lon": {{lon}}
        },
        "order": "asc",
        "unit": "km",
        "distance_type": "arc"
      }
    }
  ],
  "size": {{^size}}20{{/size}}{{size}}
}
```

---

## 8. Geo Bounding Box Query

**Use case:** Map-based search to find all listings visible within the current viewport.

**Mustache features:** Numeric variable substitution, conditional filters.

```mustache
{
  "query": {
    "bool": {
      "filter": [
        {
          "geo_bounding_box": {
            "location": {
              "top_left": {
                "lat": {{top_lat}},
                "lon": {{left_lon}}
              },
              "bottom_right": {
                "lat": {{bottom_lat}},
                "lon": {{right_lon}}
              }
            }
          }
        }
        {{#listing_type}}
        ,{
          "term": {
            "listing_type.keyword": "{{listing_type}}"
          }
        }
        {{/listing_type}}
        {{#min_price}}
        ,{
          "range": {
            "price": {
              "gte": {{min_price}}
              {{#max_price}},"lte": {{max_price}}{{/max_price}}
            }
          }
        }
        {{/min_price}}
      ]
    }
  },
  "size": {{^size}}100{{/size}}{{size}}
}
```

---

## 9. Nested Query for Products with Nested Attributes

**Use case:** Search products with nested variant attributes (size, color, availability).

**Mustache features:** Conditional sections for optional nested filters, `{{#toJson}}` for nested objects.

```mustache
{
  "query": {
    "bool": {
      "must": [
        {
          "match": {
            "product_name": "{{query_string}}"
          }
        }
      ],
      "filter": [
        {
          "nested": {
            "path": "variants",
            "query": {
              "bool": {
                "must": [
                  {{#color}}
                  {
                    "term": {
                      "variants.color.keyword": "{{color}}"
                    }
                  }
                  {{/color}}
                  {{#size}}
                  {{#color}},{{/color}}
                  {
                    "term": {
                      "variants.size.keyword": "{{size}}"
                    }
                  }
                  {{/size}}
                ],
                "filter": [
                  {
                    "term": {
                      "variants.in_stock": true
                    }
                  }
                ]
              }
            }
          }
        }
      ]
    }
  }
}
```

---

## 10. Aggregations with Terms and Date Histogram

**Use case:** Analytics dashboard query for log counts by service and time bucket.

**Mustache features:** Inverted sections for defaults, conditional sub-aggregations.

```mustache
{
  "size": 0,
  "query": {
    "bool": {
      "filter": [
        {
          "range": {
            "@timestamp": {
              "gte": "{{start_time}}",
              "lte": "{{^end_time}}now{{/end_time}}{{end_time}}"
            }
          }
        }
        {{#service_name}}
        ,{
          "term": {
            "service.name.keyword": "{{service_name}}"
          }
        }
        {{/service_name}}
      ]
    }
  },
  "aggs": {
    "by_service": {
      "terms": {
        "field": "service.name.keyword",
        "size": {{^agg_size}}10{{/agg_size}}{{agg_size}}
      },
      "aggs": {
        "over_time": {
          "date_histogram": {
            "field": "@timestamp",
            "fixed_interval": "{{^interval}}1h{{/interval}}{{interval}}"
          }
          {{#include_error_rate}}
          ,"aggs": {
            "errors": {
              "filter": {
                "term": {
                  "severity.keyword": "ERROR"
                }
              }
            }
          }
          {{/include_error_rate}}
        }
      }
    }
  }
}
```

---

## 11. Histogram Aggregation for Price Distribution

**Use case:** E-commerce price distribution analysis with optional brand filter.

**Mustache features:** Inverted sections for defaults, conditional filters and sub-aggregations.

```mustache
{
  "size": 0,
  "query": {
    "bool": {
      "filter": [
        {
          "term": {
            "category.keyword": "{{category}}"
          }
        }
        {{#brand}}
        ,{
          "term": {
            "brand.keyword": "{{brand}}"
          }
        }
        {{/brand}}
      ]
    }
  },
  "aggs": {
    "price_distribution": {
      "histogram": {
        "field": "price",
        "interval": {{^price_interval}}50{{/price_interval}}{{price_interval}},
        "min_doc_count": 1
      },
      "aggs": {
        "avg_rating": {
          "avg": {
            "field": "rating"
          }
        }
      }
    },
    "price_stats": {
      "stats": {
        "field": "price"
      }
    }
  }
}
```

---

## 12. Highlighting with Custom Tags

**Use case:** Content/document search with highlighted snippets for display.

**Mustache features:** Inverted sections for defaults, conditional sections, `{{#toJson}}` for field lists.

```mustache
{
  "query": {
    "multi_match": {
      "query": "{{query_string}}",
      "fields": ["title^3", "body", "summary^2"],
      "type": "best_fields"
    }
  },
  "highlight": {
    "pre_tags": ["{{^pre_tag}}<em>{{/pre_tag}}{{pre_tag}}"],
    "post_tags": ["{{^post_tag}}</em>{{/post_tag}}{{post_tag}}"],
    "fields": {
      "title": {
        "number_of_fragments": 0
      },
      "body": {
        "fragment_size": {{^fragment_size}}150{{/fragment_size}}{{fragment_size}},
        "number_of_fragments": {{^num_fragments}}3{{/num_fragments}}{{num_fragments}}
      },
      "summary": {
        "number_of_fragments": 0
      }
    }
  },
  "_source": ["title", "summary", "author", "published_date", "url"],
  "size": {{^size}}10{{/size}}{{size}}
}
```

---

## 13. Source Filtering and Sorting

**Use case:** Leaderboard/ranking query returning specific fields with multi-field sort.

**Mustache features:** `{{#toJson}}` for source field arrays, conditional sort direction.

```mustache
{
  "query": {
    "bool": {
      "filter": [
        {
          "term": {
            "game_id.keyword": "{{game_id}}"
          }
        }
        {{#season}}
        ,{
          "term": {
            "season": {{season}}
          }
        }
        {{/season}}
      ]
    }
  },
  "_source": {{#toJson}}fields{{/toJson}},
  "sort": [
    { "{{^sort_field}}score{{/sort_field}}{{sort_field}}": { "order": "{{^sort_order}}desc{{/sort_order}}{{sort_order}}" } },
    { "player_name.keyword": { "order": "asc" } }
  ],
  "from": {{^from}}0{{/from}}{{from}},
  "size": {{^size}}25{{/size}}{{size}}
}
```

---

## 14. Pagination with search_after

**Use case:** Deep pagination for infinite scroll, using `search_after` for efficient cursor-based paging.

**Mustache features:** Raw JSON injection with triple braces `{{{...}}}`, conditional `search_after`.

```mustache
{
  "query": {
    "bool": {
      "must": [
        {
          "match": {
            "content": "{{query_string}}"
          }
        }
      ]
      {{#category}}
      ,"filter": [
        {
          "term": {
            "category.keyword": "{{category}}"
          }
        }
      ]
      {{/category}}
    }
  },
  "sort": [
    { "_score": "desc" },
    { "_id": "asc" }
  ],
  {{#search_after}}
  "search_after": {{{search_after}}},
  {{/search_after}}
  "size": {{^size}}20{{/size}}{{size}}
}
```

---

## 15. Function Score Query

**Use case:** E-commerce product ranking blending relevance with popularity, recency, and proximity boosts.

**Mustache features:** Conditional function blocks, inverted sections for default weights.

```mustache
{
  "query": {
    "function_score": {
      "query": {
        "multi_match": {
          "query": "{{query_string}}",
          "fields": ["title^3", "description", "brand^2"]
        }
      },
      "functions": [
        {
          "field_value_factor": {
            "field": "sales_count",
            "factor": {{^popularity_factor}}1.2{{/popularity_factor}}{{popularity_factor}},
            "modifier": "log1p",
            "missing": 1
          },
          "weight": {{^popularity_weight}}2{{/popularity_weight}}{{popularity_weight}}
        },
        {
          "gauss": {
            "created_at": {
              "origin": "now",
              "scale": "{{^recency_scale}}30d{{/recency_scale}}{{recency_scale}}",
              "decay": 0.5
            }
          },
          "weight": {{^recency_weight}}1.5{{/recency_weight}}{{recency_weight}}
        }
        {{#lat}}
        ,{
          "gauss": {
            "location": {
              "origin": {
                "lat": {{lat}},
                "lon": {{lon}}
              },
              "scale": "{{^geo_scale}}5km{{/geo_scale}}{{geo_scale}}",
              "decay": 0.5
            }
          },
          "weight": {{^geo_weight}}3{{/geo_weight}}{{geo_weight}}
        }
        {{/lat}}
      ],
      "score_mode": "{{^score_mode}}sum{{/score_mode}}{{score_mode}}",
      "boost_mode": "{{^boost_mode}}multiply{{/boost_mode}}{{boost_mode}}"
    }
  },
  "size": {{^size}}20{{/size}}{{size}}
}
```

---

## 16. Script Score Query

**Use case:** Custom scoring with a script that combines text relevance with business logic.

**Mustache features:** Conditional script parameters, inverted sections for defaults.

```mustache
{
  "query": {
    "script_score": {
      "query": {
        "bool": {
          "must": [
            {
              "match": {
                "title": "{{query_string}}"
              }
            }
          ],
          "filter": [
            {
              "term": {
                "status.keyword": "{{^status}}active{{/status}}{{status}}"
              }
            }
          ]
        }
      },
      "script": {
        "source": "double score = _score; if (doc['is_promoted'].value == true) { score *= params.promo_boost; } score += Math.log1p(doc['view_count'].value) * params.views_weight; return score;",
        "params": {
          "promo_boost": {{^promo_boost}}2.0{{/promo_boost}}{{promo_boost}},
          "views_weight": {{^views_weight}}0.5{{/views_weight}}{{views_weight}}
        }
      }
    }
  },
  "size": {{^size}}10{{/size}}{{size}}
}
```

---

## 17. Script Fields

**Use case:** Compute derived fields at query time (e.g., discounted price, distance label).

**Mustache features:** Variable substitution in script params, conditional script fields.

```mustache
{
  "query": {
    "bool": {
      "filter": [
        {
          "term": {
            "category.keyword": "{{category}}"
          }
        }
      ]
    }
  },
  "script_fields": {
    "discounted_price": {
      "script": {
        "source": "doc['price'].value * (1 - params.discount_rate)",
        "params": {
          "discount_rate": {{^discount_rate}}0.0{{/discount_rate}}{{discount_rate}}
        }
      }
    }
    {{#include_margin}}
    ,"profit_margin": {
      "script": {
        "source": "doc['price'].value - doc['cost'].value",
        "params": {}
      }
    }
    {{/include_margin}}
  },
  "_source": ["product_name", "price", "category"],
  "size": {{^size}}20{{/size}}{{size}}
}
```

---

## 18. Fuzzy Matching

**Use case:** Typo-tolerant product search with configurable fuzziness.

**Mustache features:** Inverted sections for default fuzziness and other parameters.

```mustache
{
  "query": {
    "bool": {
      "must": [
        {
          "match": {
            "product_name": {
              "query": "{{query_string}}",
              "fuzziness": "{{^fuzziness}}AUTO{{/fuzziness}}{{fuzziness}}",
              "prefix_length": {{^prefix_length}}2{{/prefix_length}}{{prefix_length}},
              "max_expansions": {{^max_expansions}}50{{/max_expansions}}{{max_expansions}}
            }
          }
        }
      ]
      {{#brand}}
      ,"filter": [
        {
          "term": {
            "brand.keyword": "{{brand}}"
          }
        }
      ]
      {{/brand}}
    }
  },
  "suggest": {
    "product_suggest": {
      "text": "{{query_string}}",
      "term": {
        "field": "product_name",
        "suggest_mode": "{{^suggest_mode}}popular{{/suggest_mode}}{{suggest_mode}}"
      }
    }
  },
  "size": {{^size}}10{{/size}}{{size}}
}
```

---

## 19. Wildcard, Prefix, and Regexp Queries

**Use case:** Flexible pattern matching for log field values or identifiers.

**Mustache features:** Conditional sections to select query type at runtime.

```mustache
{
  "query": {
    "bool": {
      "must": [
        {{#wildcard_pattern}}
        {
          "wildcard": {
            "{{^field}}hostname.keyword{{/field}}{{field}}": {
              "value": "{{wildcard_pattern}}",
              "case_insensitive": true
            }
          }
        }
        {{/wildcard_pattern}}
        {{#prefix_value}}
        {
          "prefix": {
            "{{^field}}hostname.keyword{{/field}}{{field}}": {
              "value": "{{prefix_value}}"
            }
          }
        }
        {{/prefix_value}}
        {{#regexp_pattern}}
        {
          "regexp": {
            "{{^field}}hostname.keyword{{/field}}{{field}}": {
              "value": "{{regexp_pattern}}",
              "flags": "{{^regexp_flags}}ALL{{/regexp_flags}}{{regexp_flags}}"
            }
          }
        }
        {{/regexp_pattern}}
      ],
      "filter": [
        {
          "range": {
            "@timestamp": {
              "gte": "{{^start_time}}now-1h{{/start_time}}{{start_time}}"
            }
          }
        }
      ]
    }
  },
  "size": {{^size}}50{{/size}}{{size}}
}
```

---

## 20. Field Collapsing

**Use case:** De-duplicate search results by grouping on a field (e.g., show best result per seller).

**Mustache features:** Conditional inner hits, inverted sections for defaults.

```mustache
{
  "query": {
    "match": {
      "product_name": "{{query_string}}"
    }
  },
  "collapse": {
    "field": "{{^collapse_field}}seller_id.keyword{{/collapse_field}}{{collapse_field}}",
    "inner_hits": {
      "name": "alternate_options",
      "size": {{^inner_hits_size}}3{{/inner_hits_size}}{{inner_hits_size}},
      "sort": [
        { "price": "asc" }
      ]
    }
  },
  "sort": [
    { "_score": "desc" }
  ],
  "from": {{^from}}0{{/from}}{{from}},
  "size": {{^size}}10{{/size}}{{size}}
}
```

---

## 21. Completion Suggester

**Use case:** Autocomplete/type-ahead for search boxes.

**Mustache features:** Conditional fuzzy settings, `{{#toJson}}` for context categories.

```mustache
{
  "suggest": {
    "product_suggest": {
      "prefix": "{{prefix}}",
      "completion": {
        "field": "suggest",
        "size": {{^size}}5{{/size}}{{size}},
        {{#fuzzy}}
        "fuzzy": {
          "fuzziness": "{{^fuzziness}}AUTO{{/fuzziness}}{{fuzziness}}"
        },
        {{/fuzzy}}
        "contexts": {
          {{#categories}}
          "category": {{#toJson}}categories{{/toJson}}
          {{/categories}}
          {{^categories}}
          "category": []
          {{/categories}}
        }
      }
    }
  },
  "_source": ["product_name", "category", "image_url"]
}
```

---

## 22. Phrase Suggester

**Use case:** "Did you mean?" suggestions for misspelled search queries.

**Mustache features:** Inverted sections for default configuration values.

```mustache
{
  "query": {
    "match": {
      "content": {
        "query": "{{query_string}}",
        "operator": "or"
      }
    }
  },
  "suggest": {
    "did_you_mean": {
      "text": "{{query_string}}",
      "phrase": {
        "field": "content.trigram",
        "size": {{^suggestion_count}}3{{/suggestion_count}}{{suggestion_count}},
        "gram_size": {{^gram_size}}3{{/gram_size}}{{gram_size}},
        "direct_generator": [
          {
            "field": "content.trigram",
            "suggest_mode": "always",
            "min_word_length": {{^min_word_length}}3{{/min_word_length}}{{min_word_length}}
          }
        ],
        "highlight": {
          "pre_tag": "<em>",
          "post_tag": "</em>"
        }
      }
    }
  },
  "size": {{^size}}10{{/size}}{{size}}
}
```

---

## 23. toJson for Dynamic Filter Lists

**Use case:** Accept an arbitrary list of filters as terms queries, useful for faceted search refinement.

**Mustache features:** `{{#toJson}}` for arrays, conditional blocks for each facet.

```mustache
{
  "query": {
    "bool": {
      "must": [
        {
          "match": {
            "title": "{{query_string}}"
          }
        }
      ],
      "filter": [
        {{#brands}}
        {
          "terms": {
            "brand.keyword": {{#toJson}}brands{{/toJson}}
          }
        },
        {{/brands}}
        {{#colors}}
        {
          "terms": {
            "color.keyword": {{#toJson}}colors{{/toJson}}
          }
        },
        {{/colors}}
        {{#sizes}}
        {
          "terms": {
            "size.keyword": {{#toJson}}sizes{{/toJson}}
          }
        },
        {{/sizes}}
        {
          "range": {
            "price": {
              "gte": {{^price_min}}0{{/price_min}}{{price_min}},
              "lte": {{^price_max}}999999{{/price_max}}{{price_max}}
            }
          }
        }
      ]
    }
  },
  "aggs": {
    "brand_facets": {
      "terms": { "field": "brand.keyword", "size": 20 }
    },
    "color_facets": {
      "terms": { "field": "color.keyword", "size": 20 }
    },
    "size_facets": {
      "terms": { "field": "size.keyword", "size": 20 }
    }
  },
  "size": {{^size}}20{{/size}}{{size}}
}
```

---

## 24. Join for Comma-Separated Field Lists

**Use case:** Dynamically select which fields to search across using `{{#join}}`.

**Mustache features:** `{{#join}}` to convert array to comma-separated string.

```mustache
{
  "query": {
    "query_string": {
      "query": "{{query_string}}",
      "fields": ["{{#join}}search_fields{{/join}}"],
      "default_operator": "{{^default_operator}}AND{{/default_operator}}{{default_operator}}",
      "analyze_wildcard": true
    }
  },
  "sort": [
    { "{{^sort_field}}_score{{/sort_field}}{{sort_field}}": "{{^sort_order}}desc{{/sort_order}}{{sort_order}}" }
  ],
  "from": {{^from}}0{{/from}}{{from}},
  "size": {{^size}}10{{/size}}{{size}}
}
```

---

## 25. Raw JSON Injection with Triple Braces

**Use case:** Pass in a complete custom query clause or sort definition as raw JSON.

**Mustache features:** Triple braces `{{{...}}}` for unescaped raw JSON injection.

```mustache
{
  "query": {
    "bool": {
      "must": [
        {{{custom_query}}}
      ]
      {{#filters}}
      ,"filter": [
        {{{filters}}}
      ]
      {{/filters}}
    }
  }
  {{#sort}}
  ,"sort": {{{sort}}}
  {{/sort}}
  {{^sort}}
  ,"sort": [
    { "_score": "desc" }
  ]
  {{/sort}}
  {{#aggs}}
  ,"aggs": {{{aggs}}}
  {{/aggs}}
  ,"from": {{^from}}0{{/from}}{{from}},
  "size": {{^size}}10{{/size}}{{size}}
}
```

---

## 26. Deeply Nested Conditionals

**Use case:** A highly flexible e-commerce search template where every clause is optional.

**Mustache features:** Multiple levels of nested `{{#flag}}` conditionals, `{{^flag}}` defaults, `{{#toJson}}`.

```mustache
{
  "query": {
    "bool": {
      {{#query_string}}
      "must": [
        {
          "multi_match": {
            "query": "{{query_string}}",
            "fields": ["title^3", "description", "tags^2"],
            "type": "best_fields",
            "fuzziness": "{{^fuzziness}}AUTO{{/fuzziness}}{{fuzziness}}"
          }
        }
      ],
      {{/query_string}}
      {{^query_string}}
      "must": [
        { "match_all": {} }
      ],
      {{/query_string}}
      "filter": [
        {{#in_stock_only}}
        {
          "term": { "in_stock": true }
        },
        {{/in_stock_only}}
        {{#category}}
        {
          "term": { "category.keyword": "{{category}}" }
        },
        {{/category}}
        {{#price_min}}
        {
          "range": {
            "price": {
              "gte": {{price_min}}
              {{#price_max}}
              ,"lte": {{price_max}}
              {{/price_max}}
            }
          }
        },
        {{/price_min}}
        {{#rating_min}}
        {
          "range": {
            "rating": {
              "gte": {{rating_min}}
            }
          }
        },
        {{/rating_min}}
        {{#tags}}
        {
          "terms": {
            "tags.keyword": {{#toJson}}tags{{/toJson}}
          }
        },
        {{/tags}}
        {
          "range": {
            "created_at": {
              "gte": "{{^since}}now-1y{{/since}}{{since}}"
            }
          }
        }
      ]
      {{#exclude_ids}}
      ,"must_not": [
        {
          "ids": {
            "values": {{#toJson}}exclude_ids{{/toJson}}
          }
        }
      ]
      {{/exclude_ids}}
    }
  },
  {{#enable_aggs}}
  "aggs": {
    "categories": {
      "terms": { "field": "category.keyword", "size": 20 }
    },
    "price_ranges": {
      "range": {
        "field": "price",
        "ranges": [
          { "to": 25 },
          { "from": 25, "to": 50 },
          { "from": 50, "to": 100 },
          { "from": 100, "to": 200 },
          { "from": 200 }
        ]
      }
    },
    "avg_rating": {
      "avg": { "field": "rating" }
    }
  },
  {{/enable_aggs}}
  "sort": [
    {{#sort_by_price}}
    { "price": { "order": "{{^sort_order}}asc{{/sort_order}}{{sort_order}}" } }
    {{/sort_by_price}}
    {{#sort_by_rating}}
    { "rating": { "order": "desc" } }
    {{/sort_by_rating}}
    {{^sort_by_price}}
    {{^sort_by_rating}}
    { "_score": { "order": "desc" } }
    {{/sort_by_rating}}
    {{/sort_by_price}}
  ],
  "from": {{^from}}0{{/from}}{{from}},
  "size": {{^size}}20{{/size}}{{size}}
}
```

---

## 27. Complex Log/Observability Template

**Use case:** Unified log and trace search with aggregations for an observability dashboard.

**Mustache features:** Multiple conditional sections, `{{#toJson}}` for dynamic lists, inverted defaults, raw JSON via triple braces.

```mustache
{
  "query": {
    "bool": {
      "must": [
        {
          "range": {
            "@timestamp": {
              "gte": "{{start_time}}",
              "lte": "{{^end_time}}now{{/end_time}}{{end_time}}"
            }
          }
        }
        {{#query_string}}
        ,{
          "query_string": {
            "query": "{{query_string}}",
            "default_field": "message",
            "analyze_wildcard": true
          }
        }
        {{/query_string}}
      ],
      "filter": [
        {{#services}}
        {
          "terms": {
            "service.name.keyword": {{#toJson}}services{{/toJson}}
          }
        },
        {{/services}}
        {{#log_levels}}
        {
          "terms": {
            "log.level.keyword": {{#toJson}}log_levels{{/toJson}}
          }
        },
        {{/log_levels}}
        {{#trace_id}}
        {
          "term": {
            "trace.id.keyword": "{{trace_id}}"
          }
        },
        {{/trace_id}}
        {{#host}}
        {
          "wildcard": {
            "host.name.keyword": "{{host}}"
          }
        },
        {{/host}}
        {{#container_id}}
        {
          "prefix": {
            "container.id.keyword": "{{container_id}}"
          }
        },
        {{/container_id}}
        {
          "exists": { "field": "@timestamp" }
        }
      ]
      {{#exclude_messages}}
      ,"must_not": [
        {{#exclude_messages}}
        {
          "match_phrase": {
            "message": "{{.}}"
          }
        }
        {{/exclude_messages}}
      ]
      {{/exclude_messages}}
    }
  },
  {{#enable_aggs}}
  "aggs": {
    "log_level_counts": {
      "terms": { "field": "log.level.keyword" }
    },
    "timeline": {
      "date_histogram": {
        "field": "@timestamp",
        "fixed_interval": "{{^interval}}5m{{/interval}}{{interval}}"
      },
      "aggs": {
        "by_level": {
          "terms": { "field": "log.level.keyword" }
        }
      }
    },
    "top_services": {
      "terms": {
        "field": "service.name.keyword",
        "size": 10
      }
    }
  },
  {{/enable_aggs}}
  "sort": [
    { "@timestamp": { "order": "{{^sort_order}}desc{{/sort_order}}{{sort_order}}" } }
  ],
  "highlight": {
    "fields": {
      "message": {
        "fragment_size": 200,
        "number_of_fragments": 3
      }
    }
  },
  "from": {{^from}}0{{/from}}{{from}},
  "size": {{^size}}50{{/size}}{{size}}
}
```

---

## 28. E-Commerce Product Listing with Nested Aggregations

**Use case:** Full product listing page with nested variant aggregations and price statistics.

**Mustache features:** Nested conditional sections, `{{#toJson}}`, inverted defaults, aggregations with sub-aggregations.

```mustache
{
  "query": {
    "bool": {
      "must": [
        {{#query_string}}
        {
          "multi_match": {
            "query": "{{query_string}}",
            "fields": ["product_name^3", "description", "brand^2", "tags"],
            "type": "phrase_prefix"
          }
        }
        {{/query_string}}
        {{^query_string}}
        { "match_all": {} }
        {{/query_string}}
      ],
      "filter": [
        {
          "term": { "is_active": true }
        }
        {{#category_path}}
        ,{
          "term": { "category_path.keyword": "{{category_path}}" }
        }
        {{/category_path}}
        {{#brands}}
        ,{
          "terms": { "brand.keyword": {{#toJson}}brands{{/toJson}} }
        }
        {{/brands}}
      ]
    }
  },
  "aggs": {
    "nested_variants": {
      "nested": { "path": "variants" },
      "aggs": {
        "available_colors": {
          "terms": { "field": "variants.color.keyword", "size": 30 }
        },
        "available_sizes": {
          "terms": { "field": "variants.size.keyword", "size": 20 }
        },
        "price_stats": {
          "stats": { "field": "variants.price" }
        }
      }
    },
    "brand_counts": {
      "terms": { "field": "brand.keyword", "size": 50 }
    },
    "rating_histogram": {
      "histogram": { "field": "rating", "interval": 1, "min_doc_count": 0 }
    }
  },
  "sort": [
    {{#sort_field}}
    { "{{sort_field}}": { "order": "{{^sort_order}}asc{{/sort_order}}{{sort_order}}" } }
    {{/sort_field}}
    {{^sort_field}}
    { "_score": "desc" }
    {{/sort_field}}
  ],
  "from": {{^from}}0{{/from}}{{from}},
  "size": {{^size}}24{{/size}}{{size}}
}
```

---

## 29. Geo-Aware Restaurant Search with Multiple Features

**Use case:** A restaurant finder combining text search, geo distance, cuisine filters, rating thresholds, open hours, and geo-distance sorting.

**Mustache features:** Geo queries, function_score with geo decay, `{{#toJson}}`, conditional blocks, inverted defaults.

```mustache
{
  "query": {
    "function_score": {
      "query": {
        "bool": {
          "must": [
            {{#query_string}}
            {
              "multi_match": {
                "query": "{{query_string}}",
                "fields": ["name^4", "cuisine^2", "description", "menu_items"],
                "type": "best_fields",
                "fuzziness": "AUTO"
              }
            }
            {{/query_string}}
            {{^query_string}}
            { "match_all": {} }
            {{/query_string}}
          ],
          "filter": [
            {
              "geo_distance": {
                "distance": "{{^radius}}5km{{/radius}}{{radius}}",
                "location": {
                  "lat": {{lat}},
                  "lon": {{lon}}
                }
              }
            }
            {{#cuisines}}
            ,{
              "terms": { "cuisine.keyword": {{#toJson}}cuisines{{/toJson}} }
            }
            {{/cuisines}}
            {{#min_rating}}
            ,{
              "range": { "rating": { "gte": {{min_rating}} } }
            }
            {{/min_rating}}
            {{#price_level}}
            ,{
              "term": { "price_level": {{price_level}} }
            }
            {{/price_level}}
            {{#open_now}}
            ,{
              "term": { "is_open_now": true }
            }
            {{/open_now}}
          ]
        }
      },
      "functions": [
        {
          "gauss": {
            "location": {
              "origin": { "lat": {{lat}}, "lon": {{lon}} },
              "scale": "{{^geo_scale}}2km{{/geo_scale}}{{geo_scale}}",
              "decay": 0.5
            }
          },
          "weight": 3
        },
        {
          "field_value_factor": {
            "field": "rating",
            "factor": 1.5,
            "modifier": "square",
            "missing": 3
          },
          "weight": 2
        }
      ],
      "score_mode": "sum",
      "boost_mode": "multiply"
    }
  },
  "sort": [
    { "_score": "desc" },
    {
      "_geo_distance": {
        "location": { "lat": {{lat}}, "lon": {{lon}} },
        "order": "asc",
        "unit": "km"
      }
    }
  ],
  "_source": ["name", "cuisine", "rating", "price_level", "address", "location", "phone", "hours", "image_url"],
  "size": {{^size}}20{{/size}}{{size}}
}
```

---

## 30. Comprehensive Document/Knowledge Base Search

**Use case:** Enterprise knowledge base search with access control, highlighting, suggestions, and analytics aggregations.

**Mustache features:** Nearly all features combined -- `{{#toJson}}`, `{{{raw_json}}}`, `{{#join}}`, conditionals, inverted sections, deeply nested logic.

```mustache
{
  "query": {
    "bool": {
      "must": [
        {
          "multi_match": {
            "query": "{{query_string}}",
            "fields": ["title^5", "content^2", "summary^3", "tags^2", "author_name"],
            "type": "{{^match_type}}most_fields{{/match_type}}{{match_type}}",
            "fuzziness": "{{^fuzziness}}AUTO{{/fuzziness}}{{fuzziness}}",
            "operator": "{{^operator}}or{{/operator}}{{operator}}"
          }
        }
      ],
      "filter": [
        {
          "terms": {
            "access_roles.keyword": {{#toJson}}user_roles{{/toJson}}
          }
        }
        {{#doc_types}}
        ,{
          "terms": {
            "doc_type.keyword": {{#toJson}}doc_types{{/toJson}}
          }
        }
        {{/doc_types}}
        {{#date_from}}
        ,{
          "range": {
            "updated_at": {
              "gte": "{{date_from}}"
              {{#date_to}}
              ,"lte": "{{date_to}}"
              {{/date_to}}
            }
          }
        }
        {{/date_from}}
        {{#language}}
        ,{
          "term": {
            "language.keyword": "{{language}}"
          }
        }
        {{/language}}
        {{#team_ids}}
        ,{
          "terms": {
            "team_id.keyword": {{#toJson}}team_ids{{/toJson}}
          }
        }
        {{/team_ids}}
      ]
      {{#exclude_ids}}
      ,"must_not": [
        {
          "ids": {
            "values": {{#toJson}}exclude_ids{{/toJson}}
          }
        }
      ]
      {{/exclude_ids}}
      {{#boost_recent}}
      ,"should": [
        {
          "range": {
            "updated_at": {
              "gte": "now-30d",
              "boost": 2.0
            }
          }
        }
      ]
      {{/boost_recent}}
    }
  },
  "highlight": {
    "pre_tags": ["{{^pre_tag}}<mark>{{/pre_tag}}{{pre_tag}}"],
    "post_tags": ["{{^post_tag}}</mark>{{/post_tag}}{{post_tag}}"],
    "fields": {
      "title": { "number_of_fragments": 0 },
      "content": {
        "fragment_size": {{^fragment_size}}200{{/fragment_size}}{{fragment_size}},
        "number_of_fragments": {{^num_fragments}}3{{/num_fragments}}{{num_fragments}}
      },
      "summary": { "number_of_fragments": 0 }
    }
  },
  "suggest": {
    "text": "{{query_string}}",
    "title_suggest": {
      "phrase": {
        "field": "title.trigram",
        "size": 3,
        "gram_size": 3,
        "highlight": {
          "pre_tag": "<em>",
          "post_tag": "</em>"
        }
      }
    }
  },
  {{#enable_aggs}}
  "aggs": {
    "by_type": {
      "terms": { "field": "doc_type.keyword", "size": 10 }
    },
    "by_team": {
      "terms": { "field": "team_name.keyword", "size": 20 }
    },
    "by_author": {
      "terms": { "field": "author_name.keyword", "size": 10 }
    },
    "over_time": {
      "date_histogram": {
        "field": "updated_at",
        "calendar_interval": "{{^calendar_interval}}month{{/calendar_interval}}{{calendar_interval}}"
      }
    },
    "by_language": {
      "terms": { "field": "language.keyword" }
    }
  },
  {{/enable_aggs}}
  {{#collapse_by_doc}}
  "collapse": {
    "field": "doc_group_id.keyword",
    "inner_hits": {
      "name": "versions",
      "size": 2,
      "sort": [{ "updated_at": "desc" }]
    }
  },
  {{/collapse_by_doc}}
  "_source": {{#toJson}}source_fields{{/toJson}},
  "sort": [
    {{#sort_by_date}}
    { "updated_at": { "order": "desc" } }
    {{/sort_by_date}}
    {{^sort_by_date}}
    { "_score": { "order": "desc" } }
    {{/sort_by_date}}
    ,{ "title.keyword": { "order": "asc" } }
  ],
  {{#search_after}}
  "search_after": {{{search_after}}},
  {{/search_after}}
  "from": {{^from}}0{{/from}}{{from}},
  "size": {{^size}}10{{/size}}{{size}}
}
```
