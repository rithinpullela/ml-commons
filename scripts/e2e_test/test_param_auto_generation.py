#!/usr/bin/env python3
"""
End-to-end tests for Parameter Auto-Generation from Search Templates.

Tests all three modes across 10 templates:
  - Mode 1: Auto extraction (no LLM)
  - Mode 2: LLM-enhanced descriptions
  - Mode 3: Manual params

Usage:
  # Mode 1 + Mode 3 only (no model needed):
  python3 test_param_auto_generation.py

  # All modes including Mode 2 (requires deployed model):
  python3 test_param_auto_generation.py --model-id <model_id> --llm-interface bedrock/converse/claude

  # With auto model registration (requires AWS env vars):
  python3 test_param_auto_generation.py --register-model

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

# --------------------------------------------------------------------------
# All 10 test templates
# --------------------------------------------------------------------------

TEMPLATES = {
    "t1_simple": {
        "lang": "mustache",
        "source": '{"query":{"match":{"title":"{{query_text}}"}},"size":{{result_size}}}'
    },
    "t2_inverted": {
        "lang": "mustache",
        "source": '{"query":{"match":{"title":"{{query_text}}"}},"from":{{#from}}{{from}}{{/from}}{{^from}}0{{/from}},"size":{{#size}}{{size}}{{/size}}{{^size}}10{{/size}}}'
    },
    "t3_section_tojson": {
        "lang": "mustache",
        "source": '{"query":{"bool":{"must":[{"match":{"title":"{{query_text}}"}}]{{#tags}},"filter":[{"terms":{"tags":{{#toJson}}tags{{/toJson}}}}]{{/tags}}}},"size":{{#size}}{{size}}{{/size}}{{^size}}10{{/size}}}'
    },
    "t4_multi_match": {
        "lang": "mustache",
        "source": '{"query":{"multi_match":{"query":"{{query_text}}","fields":{{#toJson}}fields{{/toJson}},"type":"{{#match_type}}{{match_type}}{{/match_type}}{{^match_type}}best_fields{{/match_type}}"}},"size":{{#size}}{{size}}{{/size}}{{^size}}10{{/size}}}'
    },
    "t5_complex_bool": {
        "lang": "mustache",
        "source": '{"query":{"bool":{"must":[{"match":{"title":"{{query_text}}"}}]{{#category}},"filter":[{"term":{"category":"{{category}}"}}]{{/category}}{{#brand}},"must":[{"term":{"brand":"{{brand}}"}}]{{/brand}}{{#min_rating}},"filter":[{"range":{"rating":{"gte":{{min_rating}}}}}]{{/min_rating}}}},"from":{{#from}}{{from}}{{/from}}{{^from}}0{{/from}},"size":{{#size}}{{size}}{{/size}}{{^size}}20{{/size}}}'
    },
    "t6_range": {
        "lang": "mustache",
        "source": '{"query":{"bool":{"must":[{"match":{"title":"{{query_text}}"}}]{{#price_filter}},"filter":[{"range":{"price":{"gte":{{min_price}},"lte":{{max_price}}}}}]{{/price_filter}}}},"size":{{#size}}{{size}}{{/size}}{{^size}}10{{/size}}}'
    },
    "t7_source_sort": {
        "lang": "mustache",
        "source": '{"query":{"match":{"title":"{{query_text}}"}},"_source":{{#toJson}}source_fields{{/toJson}},"sort":[{"{{#sort_field}}{{sort_field}}{{/sort_field}}{{^sort_field}}_score{{/sort_field}}":{"order":"{{#sort_order}}{{sort_order}}{{/sort_order}}{{^sort_order}}desc{{/sort_order}}"}}],"size":{{#size}}{{size}}{{/size}}{{^size}}10{{/size}}}'
    },
    "t8_aggs": {
        "lang": "mustache",
        "source": '{"size":0,"query":{"match":{"title":"{{query_text}}"}},"aggs":{"by_category":{"terms":{"field":"{{agg_field}}","size":{{#agg_size}}{{agg_size}}{{/agg_size}}{{^agg_size}}10{{/agg_size}}}{{#include_stats}},"aggs":{"price_stats":{"stats":{"field":"price"}}}{{/include_stats}}}}}'
    },
    "t9_highlight": {
        "lang": "mustache",
        "source": '{"query":{"bool":{"should":[{"match":{"title":{"query":"{{query_text}}","boost":{{#title_boost}}{{title_boost}}{{/title_boost}}{{^title_boost}}2{{/title_boost}}}}},{"match":{"description":"{{query_text}}"}}],"minimum_should_match":{{#min_match}}{{min_match}}{{/min_match}}{{^min_match}}1{{/min_match}}}},"highlight":{"fields":{"title":{},"description":{}}},"size":{{#size}}{{size}}{{/size}}{{^size}}10{{/size}}}'
    },
    "t10_kitchen_sink": {
        "lang": "mustache",
        "source": '{"query":{"bool":{"must":[{"multi_match":{"query":"{{query_text}}","fields":{{#toJson}}search_fields{{/toJson}}}}]{{#categories}},"filter":[{"terms":{"category":{{#toJson}}categories{{/toJson}}}}]{{/categories}}{{#exclude_ids}},"must_not":[{"ids":{"values":{{#toJson}}exclude_ids{{/toJson}}}}]{{/exclude_ids}}}},"from":{{#from}}{{from}}{{/from}}{{^from}}0{{/from}},"size":{{#size}}{{size}}{{/size}}{{^size}}25{{/size}}}'
    },
}

# --------------------------------------------------------------------------
# Expected Mode 1 results for all 10 templates
# --------------------------------------------------------------------------

MODE1_EXPECTED = {
    "t1_simple": {
        "query_text":  {"type": "string", "required": True,  "default": None},
        "result_size": {"type": "number", "required": True,  "default": None},
    },
    "t2_inverted": {
        "query_text": {"type": "string", "required": True,  "default": None},
        "from":       {"type": "number", "required": False, "default": "0"},
        "size":       {"type": "number", "required": False, "default": "10"},
    },
    "t3_section_tojson": {
        "query_text": {"type": "string", "required": True,  "default": None},
        "tags":       {"type": "array",  "required": False, "default": None},
        "size":       {"type": "number", "required": False, "default": "10"},
    },
    "t4_multi_match": {
        "query_text": {"type": "string", "required": True,  "default": None},
        "fields":     {"type": "array",  "required": True,  "default": None},
        "match_type": {"type": "string", "required": False, "default": "best_fields"},
        "size":       {"type": "number", "required": False, "default": "10"},
    },
    "t5_complex_bool": {
        "query_text": {"type": "string", "required": True,  "default": None},
        "category":   {"type": "string", "required": False, "default": None},
        "brand":      {"type": "string", "required": False, "default": None},
        "min_rating": {"type": "number", "required": False, "default": None},
        "from":       {"type": "number", "required": False, "default": "0"},
        "size":       {"type": "number", "required": False, "default": "20"},
    },
    "t6_range": {
        "query_text":    {"type": "string",  "required": True,  "default": None},
        "price_filter":  {"type": "boolean", "required": False, "default": None},
        "min_price":     {"type": "number",  "required": False, "default": None},
        "max_price":     {"type": "number",  "required": False, "default": None},
        "size":          {"type": "number",  "required": False, "default": "10"},
    },
    "t7_source_sort": {
        "query_text":    {"type": "string", "required": True,  "default": None},
        "source_fields": {"type": "array",  "required": True,  "default": None},
        "sort_field":    {"type": "string", "required": False, "default": "_score"},
        "sort_order":    {"type": "string", "required": False, "default": "desc"},
        "size":          {"type": "number", "required": False, "default": "10"},
    },
    "t8_aggs": {
        "query_text":    {"type": "string",  "required": True,  "default": None},
        "agg_field":     {"type": "string",  "required": True,  "default": None},
        "agg_size":      {"type": "number",  "required": False, "default": "10"},
        "include_stats": {"type": "boolean", "required": False, "default": None},
    },
    "t9_highlight": {
        "query_text":  {"type": "string", "required": True,  "default": None},
        "title_boost": {"type": "number", "required": False, "default": "2"},
        "min_match":   {"type": "number", "required": False, "default": "1"},
        "size":        {"type": "number", "required": False, "default": "10"},
    },
    "t10_kitchen_sink": {
        "query_text":    {"type": "string", "required": True,  "default": None},
        "search_fields": {"type": "array",  "required": True,  "default": None},
        "categories":    {"type": "array",  "required": False, "default": None},
        "exclude_ids":   {"type": "array",  "required": False, "default": None},
        "from":          {"type": "number", "required": False, "default": "0"},
        "size":          {"type": "number", "required": False, "default": "25"},
    },
}

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


# --------------------------------------------------------------------------
# Setup
# --------------------------------------------------------------------------

def setup_templates():
    print("\n" + "=" * 70)
    print("  SETUP: Creating 10 search templates")
    print("=" * 70)
    for name, script in TEMPLATES.items():
        status, resp = api("POST", f"/_scripts/{name}", {"script": script})
        ok = status == 200 and resp.get("acknowledged", False)
        record(ok)
        symbol = "OK" if ok else "FAILED"
        print(f"  {symbol}: {name}")


# --------------------------------------------------------------------------
# Mode 1 Tests
# --------------------------------------------------------------------------

def test_mode1():
    print("\n" + "=" * 70)
    print("  MODE 1: Auto Extraction (no LLM)")
    print("=" * 70)

    for template_name, expected_params in MODE1_EXPECTED.items():
        print(f"\n--- {template_name} ---\n")

        # Show the template
        source = TEMPLATES[template_name]["source"]
        print(f"Template:\n{format_template_source(source)}\n")

        # Build and show request
        tool_name = f"M1_{template_name}_{int(time.time())}"
        req_body = create_tool_request(tool_name, template_name)
        print(f"Request:\n{pp(req_body)}\n")

        # Send and show response
        status, resp = send_create(req_body)
        print(f"Response (status {status}):\n{pp(resp)}\n")

        if status != 200 or "params" not in resp:
            record(False)
            print("RESULT: FAIL (bad response)\n")
            continue

        # Validate
        errors = check_params(resp["params"], expected_params, "Mode 1")
        if errors:
            record(False)
            print("RESULT: FAIL")
            for e in errors:
                print(e)
        else:
            record(True)
            print("RESULT: PASS")


# --------------------------------------------------------------------------
# Mode 2 Tests
# --------------------------------------------------------------------------

def test_mode2(model_id, llm_interface):
    print("\n" + "=" * 70)
    print("  MODE 2: LLM-Enhanced Descriptions")
    print("=" * 70)

    for template_name, expected_params in MODE1_EXPECTED.items():
        print(f"\n--- {template_name} ---\n")

        source = TEMPLATES[template_name]["source"]
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

        # Types and required must match Mode 1 expectations
        errors = check_params(resp["params"], expected_params, "Mode 2")

        # Additionally check that descriptions are enriched (longer than heuristic defaults)
        for pname in expected_params:
            if pname in resp["params"]:
                desc = resp["params"][pname].get("description", "")
                if len(desc) <= 20:
                    errors.append(f"  {pname}: description too short for LLM enrichment ({len(desc)} chars): \"{desc}\"")

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
# Mode 3 Tests
# --------------------------------------------------------------------------

def test_mode3():
    print("\n" + "=" * 70)
    print("  MODE 3: Manual Params")
    print("=" * 70)

    manual_params = {
        "query_text":  {"type": "text",    "description": "Words to match in product title", "required": True},
        "result_size": {"type": "integer", "description": "Max results",                     "required": False},
    }

    print(f"\n--- Manual params on t1_simple ---\n")

    source = TEMPLATES["t1_simple"]["source"]
    print(f"Template:\n{format_template_source(source)}\n")

    tool_name = f"M3_manual_{int(time.time())}"
    req_body = create_tool_request(tool_name, "t1_simple", params=manual_params)
    print(f"Request:\n{pp(req_body)}\n")

    status, resp = send_create(req_body)
    print(f"Response (status {status}):\n{pp(resp)}\n")

    if status != 200 or "params" not in resp:
        record(False)
        print("RESULT: FAIL (bad response)\n")
        return

    errors = []
    for pname, expected in manual_params.items():
        if pname not in resp["params"]:
            errors.append(f"  MISSING param: {pname}")
            continue
        actual = resp["params"][pname]
        if actual.get("type") != expected["type"]:
            errors.append(f"  {pname}: type expected '{expected['type']}', got '{actual.get('type')}'")
        if actual.get("description") != expected["description"]:
            errors.append(f"  {pname}: description expected '{expected['description']}', got '{actual.get('description')}'")
        if actual.get("required") != expected["required"]:
            errors.append(f"  {pname}: required expected {expected['required']}, got {actual.get('required')}")

    if errors:
        record(False)
        print("RESULT: FAIL")
        for e in errors:
            print(e)
    else:
        record(True)
        print("RESULT: PASS")


# --------------------------------------------------------------------------
# Validation Tests
# --------------------------------------------------------------------------

def test_validation(model_id=None):
    print("\n" + "=" * 70)
    print("  VALIDATION: Error Cases")
    print("=" * 70)

    # Test: Both params and model_id
    print("\n--- Both params + model_id (should fail) ---\n")
    req_body = {
        "name": f"V_both_{int(time.time())}",
        "description": "test",
        "type": "search_template",
        "search_template_name": "t1_simple",
        "params": {"x": {"type": "string"}},
        "model_id": "some-model-id",
        "llm_interface": "bedrock/converse/claude",
    }
    print(f"Request:\n{pp(req_body)}\n")
    status, resp = api("POST", "/_plugins/_ml/tools/_create", req_body)
    print(f"Response (status {status}):\n{pp(resp)}\n")
    error_msg = resp.get("error", {}).get("reason", "")
    ok = status == 400 and "both" in error_msg.lower()
    record(ok)
    print(f"RESULT: {'PASS' if ok else 'FAIL'} — expected 400 with 'both' in error\n")

    # Test: Non-existent template
    print("--- Non-existent template (should fail) ---\n")
    req_body = create_tool_request(f"V_bad_{int(time.time())}", "nonexistent_template_xyz")
    print(f"Request:\n{pp(req_body)}\n")
    status, resp = send_create(req_body)
    print(f"Response (status {status}):\n{pp(resp)}\n")
    error_msg = resp.get("error", {}).get("reason", "")
    ok = status == 400 and "not found" in error_msg.lower()
    record(ok)
    print(f"RESULT: {'PASS' if ok else 'FAIL'} — expected 400 with 'not found' in error\n")

    # Test: Duplicate name
    print("--- Duplicate name (should fail) ---\n")
    dup_name = f"V_dup_{int(time.time())}"
    send_create(create_tool_request(dup_name, "t1_simple"))
    req_body = create_tool_request(dup_name, "t1_simple")
    print(f"Request (2nd create with same name):\n{pp(req_body)}\n")
    status, resp = send_create(req_body)
    print(f"Response (status {status}):\n{pp(resp)}\n")
    error_msg = resp.get("error", {}).get("reason", "")
    ok = status == 400 and "already exists" in error_msg.lower()
    record(ok)
    print(f"RESULT: {'PASS' if ok else 'FAIL'} — expected 400 with 'already exists' in error\n")

    # Test: model_id without llm_interface
    if model_id:
        print("--- model_id without llm_interface (should fail) ---\n")
        req_body = create_tool_request(f"V_no_iface_{int(time.time())}", "t1_simple",
                                       model_id=model_id)
        print(f"Request:\n{pp(req_body)}\n")
        status, resp = send_create(req_body)
        print(f"Response (status {status}):\n{pp(resp)}\n")
        error_msg = resp.get("error", {}).get("reason", "")
        ok = status == 400 and "llm_interface" in error_msg.lower()
        record(ok)
        print(f"RESULT: {'PASS' if ok else 'FAIL'} — expected 400 with 'llm_interface' in error\n")


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
        "name": "Bedrock Sonnet - Param Enrichment Test",
        "function_name": "remote",
        "description": "Bedrock Claude for param enrichment testing",
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
    parser = argparse.ArgumentParser(description="Test parameter auto-generation for custom tools")
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

    setup_templates()
    test_mode1()
    test_mode3()

    # Model setup for Mode 2
    model_id = args.model_id
    if args.register_model and not model_id:
        model_id = register_and_deploy_model()

    test_validation(model_id)

    if model_id:
        test_mode2(model_id, args.llm_interface)
    else:
        print("\n" + "=" * 70)
        print("  MODE 2: SKIPPED (no --model-id or --register-model)")
        print("=" * 70)

    # Cleanup
    if not args.skip_cleanup:
        print("\n" + "=" * 70)
        print("  CLEANUP")
        print("=" * 70)
        cleanup_all()
        print(f"  Deleted {len(created_tool_ids)} tools")

    # Summary
    print("\n" + "=" * 70)
    total = passed + failed
    print(f"  RESULTS: {passed}/{total} passed, {failed}/{total} failed")
    print("=" * 70)

    if failed > 0:
        sys.exit(1)
    print("\n  All tests passed!")


if __name__ == "__main__":
    main()
