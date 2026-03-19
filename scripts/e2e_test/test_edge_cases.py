#!/usr/bin/env python3
"""
End-to-end tests for edge case and real-world search templates against the
parameter auto-extraction API (Mode 1), with optional Mode 2 (LLM-enhanced)
for a subset.

Tests:
  - 20 edge case templates (e1-e20) with full expected-param validation
  - 12 real-world templates with param-name and structural validation

Usage:
  # Mode 1 only (no model needed):
  python3 test_edge_cases.py

  # Include Mode 2 for a subset:
  python3 test_edge_cases.py --model-id <model_id> --llm-interface bedrock/converse/claude

  # With auto model registration (requires AWS env vars):
  python3 test_edge_cases.py --register-model

Prerequisites:
  - OpenSearch cluster running on localhost:9200
  - For Mode 2: AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, AWS_SESSION_TOKEN env vars
"""

import argparse
import json
import os
import sys
import time
import urllib.request
import urllib.error

BASE_URL = os.environ.get("OPENSEARCH_URL", "http://localhost:9200")
HEADERS = {"Content-Type": "application/json"}

# ==========================================================================
# Edge Case Templates (e1 - e20)
# ==========================================================================

EDGE_TEMPLATES = {
    "e1_triple_braces": {
        "lang": "mustache",
        "source": '{"query":{{{raw_query}}},"size":{{size}}}'
    },
    "e2_quoted_and_unquoted": {
        "lang": "mustache",
        "source": '{"query":{"match":{"title":{"query":"{{value}}","boost":{{value}}}}}}'
    },
    "e3_deep_nesting": {
        "lang": "mustache",
        "source": '{"query":{"bool":{"must":[{"match":{"title":"{{query_text}}"}}]{{#filters}},"filter":[{{#price_range}}{"range":{"price":{{{#min_price}}"gte":{{min_price}}{{/min_price}}}}}{{/price_range}}]{{/filters}}}}}'
    },
    "e4_section_and_value": {
        "lang": "mustache",
        "source": '{"query":{"bool":{"must":[{"match":{"title":"{{query_text}}"}}]{{#status}},"filter":[{"term":{"status":"{{status}}"}}]{{/status}}{{#sort_by}},"sort":[{"{{status}}":"asc"}]{{/sort_by}}}}}'
    },
    "e5_single_var": {
        "lang": "mustache",
        "source": '{{query}}'
    },
    "e6_sections_only": {
        "lang": "mustache",
        "source": '{"query":{"bool":{"must":[{"match_all":{}}]{{#include_active}},"filter":[{"term":{"active":true}}]{{/include_active}}{{#include_recent}},"filter":[{"range":{"created_at":{"gte":"now-7d"}}}]{{/include_recent}}{{#exclude_deleted}},"must_not":[{"term":{"deleted":true}}]{{/exclude_deleted}}}}}'
    },
    "e7_join_helper": {
        "lang": "mustache",
        "source": '{"query":{"match":{"emails":"{{#join}}email_list{{/join}}"}},"sort":[{"{{#join delimiter=\',\'}}sort_fields{{/join delimiter=\',\'}}":"asc"}]}'
    },
    "e8_duplicate_tojson": {
        "lang": "mustache",
        "source": '{"query":{"bool":{"must":[{"terms":{"tags":{{#toJson}}tags{{/toJson}}}}],"should":[{"terms":{"preferred_tags":{{#toJson}}tags{{/toJson}}}}]}}}'
    },
    "e9_dot_notation": {
        "lang": "mustache",
        "source": '{"query":{"bool":{"must":[{"match":{"author.name":"{{user.name}}"}},{"match":{"author.email":"{{user.email}}"}}]}},"size":{{result.count}}}'
    },
    "e10_inverted_only": {
        "lang": "mustache",
        "source": '{"query":{"match":{"title":"{{query_text}}"}},"size":{{^size}}10{{/size}}}'
    },
    "e11_boolean_static": {
        "lang": "mustache",
        "source": '{"query":{"match":{"title":"{{query_text}}"}}{{#include_highlights}},"highlight":{"fields":{"title":{"fragment_size":150},"body":{"fragment_size":200}}}{{/include_highlights}}{{#include_explain}},"explain":true{{/include_explain}}}'
    },
    "e12_root_and_nested": {
        "lang": "mustache",
        "source": '{"query":{"match":{"title":"{{query_text}}"}},"size":{{limit}}{{#filters}},"post_filter":{"range":{"count":{"lte":{{limit}}}}}{{/filters}}}'
    },
    "e13_no_variables": {
        "lang": "mustache",
        "source": '{"query":{"match_all":{}},"size":10,"sort":[{"_score":"desc"}]}'
    },
    "e14_ampersand_var": {
        "lang": "mustache",
        "source": '{"query":{{&raw_query}},"aggs":{{&raw_aggs}},"size":{{size}}}'
    },
    "e15_large_template": {
        "lang": "mustache",
        "source": '{"query":{"bool":{"must":[{"multi_match":{"query":"{{query_text}}","fields":{{#toJson}}search_fields{{/toJson}},"type":"{{#match_type}}{{match_type}}{{/match_type}}{{^match_type}}best_fields{{/match_type}}"}}]{{#category}},"filter":[{"term":{"category":"{{category}}"}}]{{/category}}{{#tags}},"filter":[{"terms":{"tags":{{#toJson}}tags{{/toJson}}}}]{{/tags}}{{#date_from}},"filter":[{"range":{"created_at":{"gte":"{{date_from}}"}}}]{{/date_from}}{{#date_to}},"filter":[{"range":{"created_at":{"lte":"{{date_to}}"}}}]{{/date_to}}{{#author}},"filter":[{"term":{"author.keyword":"{{author}}"}}]{{/author}}{{#exclude_ids}},"must_not":[{"ids":{"values":{{#toJson}}exclude_ids{{/toJson}}}}]{{/exclude_ids}}{{#include_drafts}},"should":[{"term":{"status":"draft"}}]{{/include_drafts}}}},"_source":{{#toJson}}source_fields{{/toJson}},"sort":[{"{{#sort_field}}{{sort_field}}{{/sort_field}}{{^sort_field}}_score{{/sort_field}}":{"order":"{{#sort_order}}{{sort_order}}{{/sort_order}}{{^sort_order}}desc{{/sort_order}}"}}],"from":{{#from}}{{from}}{{/from}}{{^from}}0{{/from}},"size":{{#size}}{{size}}{{/size}}{{^size}}25{{/size}}}'
    },
    "e16_complex_default": {
        "lang": "mustache",
        "source": '{"query":{"match":{"title":"{{query_text}}"}},"sort":{{#sort}}{{sort}}{{/sort}}{{^sort}}[{"_score":"desc"}]{{/sort}}}'
    },
    "e17_url_helper": {
        "lang": "mustache",
        "source": '{"query":{"term":{"url":"{{#url}}{{search_url}}{{/url}}"}},"size":{{size}}}'
    },
    "e18_implicit_iterator": {
        "lang": "mustache",
        "source": '{"query":{"terms":{"status":[{{#statuses}}"{{.}}"{{/statuses}}]}}}'
    },
    "e19_section_and_tojson": {
        "lang": "mustache",
        "source": '{"query":{"bool":{"must":[{"match":{"title":"{{query_text}}"}}]{{#items}},"filter":[{"terms":{"item_ids":{{#toJson}}items{{/toJson}}}}]{{/items}}}}}'
    },
    "e20_whitespace": {
        "lang": "mustache",
        "source": '{"query":{"match":{"title":"{{ query_text }}"}},"size":{{ size }}}'
    },
}

# ==========================================================================
# Expected Mode 1 results for edge case templates
# ==========================================================================

EDGE_EXPECTED = {
    "e1_triple_braces": {
        "raw_query": {"type": "number", "required": True,  "default": None},
        "size":      {"type": "number", "required": True,  "default": None},
    },
    "e2_quoted_and_unquoted": {
        "value": {"type": "string", "required": True, "default": None},
    },
    "e3_deep_nesting": {
        "query_text":  {"type": "string",  "required": True,  "default": None},
        "filters":     {"type": "boolean", "required": False, "default": None},
        "price_range": {"type": "boolean", "required": False, "default": None},
        "min_price":   {"type": "number",  "required": False, "default": None},
    },
    "e4_section_and_value": {
        "query_text": {"type": "string",  "required": True,  "default": None},
        "status":     {"type": "string",  "required": False, "default": None},
        "sort_by":    {"type": "boolean", "required": False, "default": None},
    },
    "e5_single_var": {
        "query": {"type": "string", "required": True, "default": None},
    },
    "e6_sections_only": {
        "include_active":  {"type": "boolean", "required": False, "default": None},
        "include_recent":  {"type": "boolean", "required": False, "default": None},
        "exclude_deleted": {"type": "boolean", "required": False, "default": None},
    },
    "e7_join_helper": {
        "email_list":  {"type": "array", "required": True, "default": None},
        "sort_fields": {"type": "array", "required": True, "default": None},
    },
    "e8_duplicate_tojson": {
        "tags": {"type": "array", "required": True, "default": None},
    },
    "e9_dot_notation": {
        "user":   {"type": "string", "required": True, "default": None},
        "result": {"type": "number", "required": True, "default": None},
    },
    "e10_inverted_only": {
        "query_text": {"type": "string", "required": True, "default": None},
        # size may or may not appear -- standalone inverted section is a known edge case.
        # If it appears, it should have default "10". We test this softly below.
    },
    "e11_boolean_static": {
        "query_text":          {"type": "string",  "required": True,  "default": None},
        "include_highlights":  {"type": "boolean", "required": False, "default": None},
        "include_explain":     {"type": "boolean", "required": False, "default": None},
    },
    "e12_root_and_nested": {
        "query_text": {"type": "string",  "required": True,  "default": None},
        "limit":      {"type": "number",  "required": True,  "default": None},
        "filters":    {"type": "boolean", "required": False, "default": None},
    },
    "e13_no_variables": {
        # No parameters expected
    },
    "e14_ampersand_var": {
        "raw_query": {"type": "number", "required": True, "default": None},
        "raw_aggs":  {"type": "number", "required": True, "default": None},
        "size":      {"type": "number", "required": True, "default": None},
    },
    "e15_large_template": {
        "query_text":     {"type": "string",  "required": True,  "default": None},
        "search_fields":  {"type": "array",   "required": True,  "default": None},
        "match_type":     {"type": "string",  "required": False, "default": "best_fields"},
        "category":       {"type": "string",  "required": False, "default": None},
        "tags":           {"type": "array",   "required": False, "default": None},
        "date_from":      {"type": "string",  "required": False, "default": None},
        "date_to":        {"type": "string",  "required": False, "default": None},
        "author":         {"type": "string",  "required": False, "default": None},
        "exclude_ids":    {"type": "array",   "required": False, "default": None},
        "include_drafts": {"type": "boolean", "required": False, "default": None},
        "source_fields":  {"type": "array",   "required": True,  "default": None},
        "sort_field":     {"type": "string",  "required": False, "default": "_score"},
        "sort_order":     {"type": "string",  "required": False, "default": "desc"},
        "from":           {"type": "number",  "required": False, "default": "0"},
        "size":           {"type": "number",  "required": False, "default": "25"},
        "include_aggs":   {"type": "boolean", "required": False, "default": None},
    },
    "e16_complex_default": {
        "query_text": {"type": "string", "required": True,  "default": None},
        "sort":       {"type": "string", "required": False, "default": '[{"_score":"desc"}]'},
    },
    "e17_url_helper": {
        "search_url": {"type": "string", "required": True, "default": None},
        "size":       {"type": "number", "required": True, "default": None},
    },
    "e18_implicit_iterator": {
        # statuses is section-controller-only; AST cannot distinguish iteration
        # from conditional, so it will be typed boolean.
        "statuses": {"type": "boolean", "required": False, "default": None},
    },
    "e19_section_and_tojson": {
        "query_text": {"type": "string", "required": True,  "default": None},
        "items":      {"type": "array",  "required": False, "default": None},
    },
    "e20_whitespace": {
        "query_text": {"type": "string", "required": True, "default": None},
        "size":       {"type": "number", "required": True, "default": None},
    },
}

# ==========================================================================
# Real-World Templates (selected 12 diverse examples)
# ==========================================================================

REAL_WORLD_TEMPLATES = {
    "rw01_simple_match": {
        "lang": "mustache",
        "source": '{"query":{"match":{"{{^field}}title{{/field}}{{field}}":{"query":"{{query_string}}","operator":"{{^operator}}or{{/operator}}{{operator}}"}}}}'
    },
    "rw04_multi_match": {
        "lang": "mustache",
        "source": '{"query":{"multi_match":{"query":"{{query_string}}","fields":{{#toJson}}fields{{/toJson}},"type":"{{^type}}best_fields{{/type}}{{type}}","tie_breaker":{{^tie_breaker}}0.3{{/tie_breaker}}{{tie_breaker}}}}}'
    },
    "rw07_geo_distance": {
        "lang": "mustache",
        "source": '{"query":{"bool":{"must":[{"match_all":{}}],"filter":[{"geo_distance":{"distance":"{{^distance}}10km{{/distance}}{{distance}}","location":{"lat":{{lat}},"lon":{{lon}}}}}{{#store_type}},{"term":{"store_type.keyword":"{{store_type}}"}}{{/store_type}}{{#is_open}},{"term":{"is_open":true}}{{/is_open}}]}},"sort":[{"_geo_distance":{"location":{"lat":{{lat}},"lon":{{lon}}},"order":"asc","unit":"km","distance_type":"arc"}}],"size":{{^size}}20{{/size}}{{size}}}'
    },
    "rw10_aggs_date_histogram": {
        "lang": "mustache",
        "source": '{"size":0,"query":{"bool":{"filter":[{"range":{"@timestamp":{"gte":"{{start_time}}","lte":"{{^end_time}}now{{/end_time}}{{end_time}}"}}}{{#service_name}},{"term":{"service.name.keyword":"{{service_name}}"}}{{/service_name}}]}},"aggs":{"by_service":{"terms":{"field":"service.name.keyword","size":{{^agg_size}}10{{/agg_size}}{{agg_size}}},"aggs":{"over_time":{"date_histogram":{"field":"@timestamp","fixed_interval":"{{^interval}}1h{{/interval}}{{interval}}"}{{#include_error_rate}},"aggs":{"errors":{"filter":{"term":{"severity.keyword":"ERROR"}}}}{{/include_error_rate}}}}}}}'
    },
    "rw14_search_after": {
        "lang": "mustache",
        "source": '{"query":{"bool":{"must":[{"match":{"content":"{{query_string}}"}}]{{#category}},"filter":[{"term":{"category.keyword":"{{category}}"}}]{{/category}}}},"sort":[{"_score":"desc"},{"_id":"asc"}],{{#search_after}}"search_after":{{{search_after}}},{{/search_after}}"size":{{^size}}20{{/size}}{{size}}}'
    },
    "rw15_function_score": {
        "lang": "mustache",
        "source": '{"query":{"function_score":{"query":{"multi_match":{"query":"{{query_string}}","fields":["title^3","description","brand^2"]}},"functions":[{"field_value_factor":{"field":"sales_count","factor":{{^popularity_factor}}1.2{{/popularity_factor}}{{popularity_factor}},"modifier":"log1p","missing":1},"weight":{{^popularity_weight}}2{{/popularity_weight}}{{popularity_weight}}},{"gauss":{"created_at":{"origin":"now","scale":"{{^recency_scale}}30d{{/recency_scale}}{{recency_scale}}","decay":0.5}},"weight":{{^recency_weight}}1.5{{/recency_weight}}{{recency_weight}}}{{#lat}},{"gauss":{"location":{"origin":{"lat":{{lat}},"lon":{{lon}}},"scale":"{{^geo_scale}}5km{{/geo_scale}}{{geo_scale}}","decay":0.5}},"weight":{{^geo_weight}}3{{/geo_weight}}{{geo_weight}}}{{/lat}}],"score_mode":"{{^score_mode}}sum{{/score_mode}}{{score_mode}}","boost_mode":"{{^boost_mode}}multiply{{/boost_mode}}{{boost_mode}}"}},"size":{{^size}}20{{/size}}{{size}}}'
    },
    "rw20_field_collapsing": {
        "lang": "mustache",
        "source": '{"query":{"match":{"product_name":"{{query_string}}"}},"collapse":{"field":"{{^collapse_field}}seller_id.keyword{{/collapse_field}}{{collapse_field}}","inner_hits":{"name":"alternate_options","size":{{^inner_hits_size}}3{{/inner_hits_size}}{{inner_hits_size}},"sort":[{"price":"asc"}]}},"sort":[{"_score":"desc"}],"from":{{^from}}0{{/from}}{{from}},"size":{{^size}}10{{/size}}{{size}}}'
    },
    "rw21_completion_suggester": {
        "lang": "mustache",
        "source": '{"suggest":{"product_suggest":{"prefix":"{{prefix}}","completion":{"field":"suggest","size":{{^size}}5{{/size}}{{size}},{{#fuzzy}}"fuzzy":{"fuzziness":"{{^fuzziness}}AUTO{{/fuzziness}}{{fuzziness}}"},{{/fuzzy}}"contexts":{{{#categories}}"category":{{#toJson}}categories{{/toJson}}{{/categories}}{{^categories}}"category":[]{{/categories}}}}}},"_source":["product_name","category","image_url"]}'
    },
    "rw23_tojson_facets": {
        "lang": "mustache",
        "source": '{"query":{"bool":{"must":[{"match":{"title":"{{query_string}}"}}],"filter":[{{#brands}}{"terms":{"brand.keyword":{{#toJson}}brands{{/toJson}}}},{{/brands}}{{#colors}}{"terms":{"color.keyword":{{#toJson}}colors{{/toJson}}}},{{/colors}}{{#sizes}}{"terms":{"size.keyword":{{#toJson}}sizes{{/toJson}}}},{{/sizes}}{"range":{"price":{"gte":{{^price_min}}0{{/price_min}}{{price_min}},"lte":{{^price_max}}999999{{/price_max}}{{price_max}}}}}]}},"aggs":{"brand_facets":{"terms":{"field":"brand.keyword","size":20}},"color_facets":{"terms":{"field":"color.keyword","size":20}},"size_facets":{"terms":{"field":"size.keyword","size":20}}},"size":{{^size}}20{{/size}}{{size}}}'
    },
    "rw24_join_fields": {
        "lang": "mustache",
        "source": '{"query":{"query_string":{"query":"{{query_string}}","fields":["{{#join}}search_fields{{/join}}"],"default_operator":"{{^default_operator}}AND{{/default_operator}}{{default_operator}}","analyze_wildcard":true}},"sort":[{"{{^sort_field}}_score{{/sort_field}}{{sort_field}}":"{{^sort_order}}desc{{/sort_order}}{{sort_order}}"}],"from":{{^from}}0{{/from}}{{from}},"size":{{^size}}10{{/size}}{{size}}}'
    },
    "rw25_raw_json": {
        "lang": "mustache",
        "source": '{"query":{"bool":{"must":[{{{custom_query}}}]{{#filters}},"filter":[{{{filters}}}]{{/filters}}}}{{#sort}},"sort":{{{sort}}}{{/sort}}{{^sort}},"sort":[{"_score":"desc"}]{{/sort}}{{#aggs}},"aggs":{{{aggs}}}{{/aggs}},"from":{{^from}}0{{/from}}{{from}},"size":{{^size}}10{{/size}}{{size}}}'
    },
    "rw27_observability": {
        "lang": "mustache",
        "source": '{"query":{"bool":{"must":[{"range":{"@timestamp":{"gte":"{{start_time}}","lte":"{{^end_time}}now{{/end_time}}{{end_time}}"}}}{{#query_string}},{"query_string":{"query":"{{query_string}}","default_field":"message","analyze_wildcard":true}}{{/query_string}}],"filter":[{{#services}}{"terms":{"service.name.keyword":{{#toJson}}services{{/toJson}}}},{{/services}}{{#log_levels}}{"terms":{"log.level.keyword":{{#toJson}}log_levels{{/toJson}}}},{{/log_levels}}{{#trace_id}}{"term":{"trace.id.keyword":"{{trace_id}}"}},{{/trace_id}}{{#host}}{"wildcard":{"host.name.keyword":"{{host}}"}},{{/host}}{{#container_id}}{"prefix":{"container.id.keyword":"{{container_id}}"}},{{/container_id}}{"exists":{"field":"@timestamp"}}]{{#exclude_messages}},"must_not":[{{#exclude_messages}}{"match_phrase":{"message":"{{.}}"}}{{/exclude_messages}}]{{/exclude_messages}}}}{{#enable_aggs}},"aggs":{"log_level_counts":{"terms":{"field":"log.level.keyword"}},"timeline":{"date_histogram":{"field":"@timestamp","fixed_interval":"{{^interval}}5m{{/interval}}{{interval}}"},"aggs":{"by_level":{"terms":{"field":"log.level.keyword"}}}},"top_services":{"terms":{"field":"service.name.keyword","size":10}}}{{/enable_aggs}},"sort":[{"@timestamp":{"order":"{{^sort_order}}desc{{/sort_order}}{{sort_order}}"}}],"highlight":{"fields":{"message":{"fragment_size":200,"number_of_fragments":3}}},"from":{{^from}}0{{/from}}{{from}},"size":{{^size}}50{{/size}}{{size}}}'
    },
}

# Expected parameter NAMES for real-world templates (for structural validation).
# We verify these names are present, and each has type/description/required.
REAL_WORLD_EXPECTED_NAMES = {
    "rw01_simple_match": ["field", "query_string", "operator"],
    "rw04_multi_match": ["query_string", "fields", "type", "tie_breaker"],
    "rw07_geo_distance": ["distance", "lat", "lon", "store_type", "is_open", "size"],
    "rw10_aggs_date_histogram": ["start_time", "end_time", "service_name", "agg_size",
                                  "interval", "include_error_rate"],
    "rw14_search_after": ["query_string", "category", "search_after", "size"],
    "rw15_function_score": ["query_string", "popularity_factor", "popularity_weight",
                            "recency_scale", "recency_weight", "lat", "lon",
                            "geo_scale", "geo_weight", "score_mode", "boost_mode", "size"],
    "rw20_field_collapsing": ["query_string", "collapse_field", "inner_hits_size",
                              "from", "size"],
    "rw21_completion_suggester": ["prefix", "size", "fuzzy", "fuzziness", "categories"],
    "rw23_tojson_facets": ["query_string", "brands", "colors", "sizes",
                           "price_min", "price_max", "size"],
    "rw24_join_fields": ["query_string", "search_fields", "default_operator",
                         "sort_field", "sort_order", "from", "size"],
    "rw25_raw_json": ["custom_query", "filters", "sort", "aggs", "from", "size"],
    "rw27_observability": ["start_time", "end_time", "query_string", "services",
                           "log_levels", "trace_id", "host", "container_id",
                           "exclude_messages", "enable_aggs", "interval",
                           "sort_order", "from", "size"],
}

# Subset of templates to test with Mode 2 (LLM-enhanced descriptions)
MODE2_SUBSET = ["e3_deep_nesting", "e15_large_template", "rw15_function_score",
                "rw27_observability", "rw23_tojson_facets"]

# --------------------------------------------------------------------------
# Helpers
# --------------------------------------------------------------------------

passed = 0
failed = 0
created_tool_ids = []


def pp(obj):
    """Pretty-print JSON."""
    return json.dumps(obj, indent=2)


def api(method, path, body=None):
    url = f"{BASE_URL}{path}"
    data = json.dumps(body).encode("utf-8") if body else None
    req = urllib.request.Request(url, data=data, headers=HEADERS, method=method)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode("utf-8"))
        except Exception:
            return e.code, {"raw": str(e)}
    except Exception as e:
        return 0, {"raw": str(e)}


def cleanup_tool(tool_id):
    api("DELETE", f"/_plugins/_ml/tools/{tool_id}")


def cleanup_all():
    for tid in created_tool_ids:
        cleanup_tool(tid)


def record(success):
    global passed, failed
    if success:
        passed += 1
    else:
        failed += 1


def create_tool_request(name, template_name, model_id=None, llm_interface=None, params=None):
    """Build the request body (does not send it)."""
    body = {
        "name": name,
        "description": f"Test tool for {template_name}",
        "type": "search_template",
        "search_template_name": template_name,
    }
    if model_id:
        body["model_id"] = model_id
    if llm_interface:
        body["llm_interface"] = llm_interface
    if params:
        body["params"] = params
    return body


def send_create(body):
    status, resp = api("POST", "/_plugins/_ml/tools/_create", body)
    if status == 200 and "tool_id" in resp:
        created_tool_ids.append(resp["tool_id"])
    return status, resp


def format_template_source(source_str):
    """Try to pretty-print a JSON template source string."""
    try:
        return json.dumps(json.loads(source_str), indent=2)
    except Exception:
        return source_str


def check_params(actual_params, expected_params, mode_label):
    """Validate params and return list of error strings (empty = all good)."""
    errors = []
    for pname, expected in expected_params.items():
        if pname not in actual_params:
            errors.append(f"  MISSING param: {pname}")
            continue
        actual = actual_params[pname]
        if actual.get("type") != expected["type"]:
            errors.append(f"  {pname}: type expected '{expected['type']}', got '{actual.get('type')}'")
        if actual.get("required") != expected["required"]:
            errors.append(f"  {pname}: required expected {expected['required']}, got {actual.get('required')}")
        if expected["default"] is not None and actual.get("default") != expected["default"]:
            errors.append(f"  {pname}: default expected '{expected['default']}', got '{actual.get('default')}'")
        if not actual.get("description"):
            errors.append(f"  {pname}: missing description")

    extra = set(actual_params.keys()) - set(expected_params.keys())
    if extra:
        errors.append(f"  UNEXPECTED extra params: {extra}")
    return errors


def check_structural(actual_params, expected_names, template_name):
    """Validate that expected param names are present and each has type/description/required."""
    errors = []
    for pname in expected_names:
        if pname not in actual_params:
            errors.append(f"  MISSING param: {pname}")
            continue
        actual = actual_params[pname]
        if not actual.get("type"):
            errors.append(f"  {pname}: missing type")
        if not actual.get("description"):
            errors.append(f"  {pname}: missing description")
        if "required" not in actual:
            errors.append(f"  {pname}: missing required flag")
    return errors


# --------------------------------------------------------------------------
# Setup
# --------------------------------------------------------------------------

def setup_templates():
    all_templates = {}
    all_templates.update(EDGE_TEMPLATES)
    all_templates.update(REAL_WORLD_TEMPLATES)

    print("\n" + "=" * 70)
    print(f"  SETUP: Creating {len(all_templates)} search templates")
    print("=" * 70)
    for name, script in all_templates.items():
        status, resp = api("POST", f"/_scripts/{name}", {"script": script})
        ok = status == 200 and resp.get("acknowledged", False)
        record(ok)
        symbol = "OK" if ok else "FAILED"
        print(f"  {symbol}: {name}")


# --------------------------------------------------------------------------
# Edge Case Tests (Mode 1)
# --------------------------------------------------------------------------

def test_edge_cases_mode1():
    print("\n" + "=" * 70)
    print("  EDGE CASE TESTS: Mode 1 Auto Extraction (e1-e20)")
    print("=" * 70)

    for template_name in EDGE_TEMPLATES:
        expected_params = EDGE_EXPECTED.get(template_name, {})
        print(f"\n--- {template_name} ---\n")

        # Show the template
        source = EDGE_TEMPLATES[template_name]["source"]
        print(f"Template:\n{format_template_source(source)}\n")

        # Build and show request
        tool_name = f"EC_{template_name}_{int(time.time())}"
        req_body = create_tool_request(tool_name, template_name)
        print(f"Request:\n{pp(req_body)}\n")

        # Send and show response
        status, resp = send_create(req_body)
        print(f"Response (status {status}):\n{pp(resp)}\n")

        if status != 200 or "params" not in resp:
            record(False)
            print("RESULT: FAIL (bad response)\n")
            continue

        actual_params = resp["params"]

        # Special handling for e10: size may be silently dropped (known edge case)
        if template_name == "e10_inverted_only":
            errors = check_params(actual_params, expected_params, "Mode 1")
            # If size is present, validate its default
            if "size" in actual_params:
                p = actual_params["size"]
                if p.get("default") != "10":
                    errors.append(f"  size: default expected '10', got '{p.get('default')}'")
                if p.get("required") is True:
                    errors.append(f"  size: expected not required (has default)")
                # Remove the extra-params complaint about size
                errors = [e for e in errors if "UNEXPECTED" not in e or "size" not in e]
            # Either way, pass if other checks are clean
            if errors:
                record(False)
                print("RESULT: FAIL")
                for e in errors:
                    print(e)
            else:
                record(True)
                if "size" in actual_params:
                    print("RESULT: PASS (size param present with default)")
                else:
                    print("RESULT: PASS (size param absent -- known edge case: standalone inverted section)")
            continue

        # Special handling for e13: expect empty params
        if template_name == "e13_no_variables":
            if len(actual_params) == 0:
                record(True)
                print("RESULT: PASS (no params, as expected)")
            else:
                record(False)
                print(f"RESULT: FAIL (expected 0 params, got {len(actual_params)}: {list(actual_params.keys())})")
            continue

        # Standard validation
        errors = check_params(actual_params, expected_params, "Mode 1")
        if errors:
            record(False)
            print("RESULT: FAIL")
            for e in errors:
                print(e)
        else:
            record(True)
            print("RESULT: PASS")


# --------------------------------------------------------------------------
# Real-World Template Tests (Mode 1)
# --------------------------------------------------------------------------

def test_real_world_mode1():
    print("\n" + "=" * 70)
    print("  REAL-WORLD TEMPLATE TESTS: Mode 1 Auto Extraction")
    print("=" * 70)

    for template_name, expected_names in REAL_WORLD_EXPECTED_NAMES.items():
        print(f"\n--- {template_name} ---\n")

        source = REAL_WORLD_TEMPLATES[template_name]["source"]
        print(f"Template:\n{format_template_source(source)}\n")

        tool_name = f"RW_{template_name}_{int(time.time())}"
        req_body = create_tool_request(tool_name, template_name)
        print(f"Request:\n{pp(req_body)}\n")

        status, resp = send_create(req_body)
        print(f"Response (status {status}):\n{pp(resp)}\n")

        if status != 200 or "params" not in resp:
            record(False)
            print("RESULT: FAIL (bad response)\n")
            continue

        actual_params = resp["params"]

        # Structural validation: check expected names are present with type/desc/required
        errors = check_structural(actual_params, expected_names, template_name)

        if errors:
            record(False)
            print("RESULT: FAIL")
            for e in errors:
                print(e)
        else:
            record(True)
            print(f"RESULT: PASS ({len(actual_params)} params extracted, "
                  f"{len(expected_names)} expected names verified)")


# --------------------------------------------------------------------------
# Mode 2 Tests (LLM-Enhanced, subset only)
# --------------------------------------------------------------------------

def test_mode2_subset(model_id, llm_interface):
    print("\n" + "=" * 70)
    print("  MODE 2: LLM-Enhanced Descriptions (subset)")
    print("=" * 70)

    # Merge all templates into one dict for lookup
    all_templates = {}
    all_templates.update(EDGE_TEMPLATES)
    all_templates.update(REAL_WORLD_TEMPLATES)

    for template_name in MODE2_SUBSET:
        print(f"\n--- {template_name} ---\n")

        source = all_templates[template_name]["source"]
        print(f"Template:\n{format_template_source(source)}\n")

        tool_name = f"M2_{template_name}_{int(time.time())}"
        req_body = create_tool_request(tool_name, template_name,
                                       model_id=model_id, llm_interface=llm_interface)
        print(f"Request:\n{pp(req_body)}\n")

        status, resp = send_create(req_body)
        print(f"Response (status {status}):\n{pp(resp)}\n")

        if status != 200 or "params" not in resp:
            record(False)
            print("RESULT: FAIL (bad response)\n")
            continue

        actual_params = resp["params"]
        errors = []

        # For edge case templates with known expected params, validate types
        if template_name in EDGE_EXPECTED and EDGE_EXPECTED[template_name]:
            expected = EDGE_EXPECTED[template_name]
            for pname, exp in expected.items():
                if pname not in actual_params:
                    errors.append(f"  MISSING param: {pname}")
                    continue
                actual = actual_params[pname]
                if actual.get("type") != exp["type"]:
                    errors.append(f"  {pname}: type expected '{exp['type']}', got '{actual.get('type')}'")
                if actual.get("required") != exp["required"]:
                    errors.append(f"  {pname}: required expected {exp['required']}, got {actual.get('required')}")

        # For real-world templates, do structural validation
        if template_name in REAL_WORLD_EXPECTED_NAMES:
            expected_names = REAL_WORLD_EXPECTED_NAMES[template_name]
            errors.extend(check_structural(actual_params, expected_names, template_name))

        # Check that LLM descriptions are enriched (longer than heuristic defaults)
        for pname, pinfo in actual_params.items():
            desc = pinfo.get("description", "")
            if len(desc) <= 20:
                errors.append(f"  {pname}: description too short for LLM enrichment "
                              f"({len(desc)} chars): \"{desc}\"")

        if errors:
            record(False)
            print("RESULT: FAIL")
            for e in errors:
                print(e)
        else:
            record(True)
            print("RESULT: PASS")

        time.sleep(1)


# --------------------------------------------------------------------------
# Model Registration
# --------------------------------------------------------------------------

def register_and_deploy_model():
    print("\n" + "=" * 70)
    print("  SETUP: Register & Deploy Model")
    print("=" * 70)

    access_key = os.environ.get("AWS_ACCESS_KEY_ID", "")
    secret_key = os.environ.get("AWS_SECRET_ACCESS_KEY", "")
    session_token = os.environ.get("AWS_SESSION_TOKEN", "")

    if not access_key or not secret_key:
        print("  ERROR: AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY must be set")
        return None

    body = {
        "name": "Bedrock Sonnet - Edge Case Test",
        "function_name": "remote",
        "description": "Bedrock Claude for edge case param enrichment testing",
        "connector": {
            "name": "Amazon Bedrock Claude connector",
            "description": "Connector for Bedrock Converse",
            "version": "1",
            "protocol": "aws_sigv4",
            "parameters": {
                "region": "us-east-1",
                "service_name": "bedrock",
                "model": "us.anthropic.claude-sonnet-4-20250514-v1:0",
            },
            "credential": {
                "access_key": access_key,
                "secret_key": secret_key,
                "session_token": session_token,
            },
            "actions": [
                {
                    "action_type": "predict",
                    "method": "POST",
                    "url": "https://bedrock-runtime.us-east-1.amazonaws.com/model/${parameters.model}/converse",
                    "headers": {"content-type": "application/json"},
                    "request_body": '{"system":[{"text":"${parameters.system_prompt:-You are a helpful assistant}"}],"messages":[{"role":"user","content":[{"text":"${parameters.prompt}"}]}]${parameters.tool_configs:-}}',
                }
            ],
        },
    }

    print(f"\nRegistering model...")
    status, resp = api("POST", "/_plugins/_ml/models/_register", body)
    model_id = resp.get("model_id")
    if not model_id:
        print(f"  ERROR: Failed to register model:\n{pp(resp)}")
        return None
    print(f"  Model registered: {model_id}")

    print(f"  Deploying...")
    status, resp = api("POST", f"/_plugins/_ml/models/{model_id}/_deploy")
    deploy_status = resp.get("status", "UNKNOWN")

    if deploy_status != "COMPLETED":
        for i in range(10):
            time.sleep(2)
            _, task_resp = api("GET", f"/_plugins/_ml/tasks/{resp.get('task_id', '')}")
            if task_resp.get("state") == "COMPLETED":
                print(f"  Deploy completed after {(i + 1) * 2}s")
                break
        else:
            print("  WARNING: Deploy may not have completed")
    else:
        print(f"  Deploy completed immediately")

    return model_id


# --------------------------------------------------------------------------
# Main
# --------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        description="Test edge case and real-world templates for parameter auto-extraction"
    )
    parser.add_argument("--model-id", help="Model ID for Mode 2 tests")
    parser.add_argument("--llm-interface", default="bedrock/converse/claude",
                        help="LLM interface (default: bedrock/converse/claude)")
    parser.add_argument("--register-model", action="store_true",
                        help="Auto-register a Bedrock model (requires AWS env vars)")
    parser.add_argument("--skip-cleanup", action="store_true",
                        help="Don't delete created tools after tests")
    args = parser.parse_args()

    # Check cluster
    try:
        status, resp = api("GET", "/_cluster/health")
        if status != 200:
            print(f"ERROR: Cluster not healthy (status={status})")
            sys.exit(1)
        print(f"Cluster: {resp.get('cluster_name')} ({resp.get('status')})")
    except Exception as e:
        print(f"ERROR: Cannot reach cluster at {BASE_URL}: {e}")
        sys.exit(1)

    # 1. Setup: register all templates
    setup_templates()

    # 2. Run edge case tests (Mode 1)
    test_edge_cases_mode1()

    # 3. Run real-world template tests (Mode 1)
    test_real_world_mode1()

    # 4. If model available: run Mode 2 for a subset
    model_id = args.model_id
    if args.register_model and not model_id:
        model_id = register_and_deploy_model()

    if model_id:
        test_mode2_subset(model_id, args.llm_interface)
    else:
        print("\n" + "=" * 70)
        print("  MODE 2: SKIPPED (no --model-id or --register-model)")
        print("=" * 70)

    # 5. Cleanup
    if not args.skip_cleanup:
        print("\n" + "=" * 70)
        print("  CLEANUP")
        print("=" * 70)
        cleanup_all()
        print(f"  Deleted {len(created_tool_ids)} tools")

    # 6. Print summary
    print("\n" + "=" * 70)
    total = passed + failed
    print(f"  RESULTS: {passed}/{total} passed, {failed}/{total} failed")
    print("=" * 70)

    if failed > 0:
        sys.exit(1)
    print("\n  All tests passed!")


if __name__ == "__main__":
    main()
