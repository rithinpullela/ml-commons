#!/usr/bin/env python3
"""
Custom Search Template Tools - End-to-End Test Suite

Tests custom search template tools against a running OpenSearch instance.
Covers Tier 1 (AST-only), Tier 2 (AST + LLM), and Tier 3 (manual params)
tool registration and execution.

Usage:
    # Full run (requires AWS creds for Tier 2):
    python test_custom_tools.py

    # Skip LLM/Bedrock setup (no AWS creds needed):
    python test_custom_tools.py --skip-llm

    # Re-run tests without re-registering templates/tools:
    python test_custom_tools.py --skip-setup

    # Custom OpenSearch URL:
    python test_custom_tools.py --os-url http://my-cluster:9200
"""

import argparse
import json
import os
import sys
import time
import traceback

import requests

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

OS_URL = "http://localhost:9200"
HEADERS = {"Content-Type": "application/json"}
AWS_REGION = "us-east-1"
BEDROCK_MODEL_ID = "anthropic.claude-3-haiku-20240307-v1:0"

# ---------------------------------------------------------------------------
# Helper Functions
# ---------------------------------------------------------------------------


def os_request(method, path, body=None):
    """Make an HTTP request to OpenSearch, print details, and return the response."""
    url = f"{OS_URL}{path}"
    method_upper = method.upper()

    print(f"\n>>> {method_upper} {path}")
    if body is not None:
        print(f"    Body: {json.dumps(body, indent=2)[:500]}")

    try:
        resp = requests.request(
            method_upper,
            url,
            headers=HEADERS,
            json=body if body is not None else None,
            timeout=60,
        )
    except requests.exceptions.ConnectionError as exc:
        print(f"    ERROR: Connection failed - {exc}")
        raise

    truncated = resp.text[:800] + ("..." if len(resp.text) > 800 else "")
    print(f"    Status: {resp.status_code}")
    print(f"    Response: {truncated}")
    return resp


def wait_for_cluster(max_retries=30, delay=2):
    """Poll cluster health until green or yellow."""
    print("Waiting for OpenSearch cluster to be ready...")
    for attempt in range(1, max_retries + 1):
        try:
            resp = requests.get(
                f"{OS_URL}/_cluster/health",
                headers=HEADERS,
                timeout=5,
            )
            if resp.status_code == 200:
                status = resp.json().get("status", "unknown")
                print(f"  Attempt {attempt}: cluster status = {status}")
                if status in ("green", "yellow"):
                    print("Cluster is ready.")
                    return
        except requests.exceptions.ConnectionError:
            print(f"  Attempt {attempt}: cluster not reachable yet...")
        time.sleep(delay)

    print("ERROR: Cluster did not become ready in time.")
    sys.exit(1)


def print_section(title):
    """Print a visual section separator."""
    print("\n" + "=" * 70)
    print(f"  {title}")
    print("=" * 70)


def assert_success(response, msg):
    """Assert that the response has a 2xx status code."""
    if 200 <= response.status_code < 300:
        print(f"  PASS: {msg} (status {response.status_code})")
    else:
        print(f"  FAIL: {msg} - expected 2xx, got {response.status_code}")
        print(f"        Body: {response.text[:400]}")


def assert_field(data, field, expected, msg):
    """Assert that a field in a dict matches an expected value."""
    actual = data.get(field)
    if actual == expected:
        print(f"  PASS: {msg} ({field} = {expected})")
    else:
        print(f"  FAIL: {msg} - expected {field}={expected}, got {actual}")


# ---------------------------------------------------------------------------
# Wipe: Clean up previous test state
# ---------------------------------------------------------------------------


def wipe_previous_state():
    """Delete all test artifacts so setup starts from a clean slate."""

    # Delete test indices
    for idx in ("products", "logs", "locations"):
        print(f"\n--- Deleting index: {idx} ---")
        requests.delete(f"{OS_URL}/{idx}", headers=HEADERS, timeout=10)

    # Delete stored search templates
    for template in ("product_search", "log_search", "geo_search"):
        print(f"\n--- Deleting search template: {template} ---")
        requests.delete(f"{OS_URL}/_scripts/{template}", headers=HEADERS, timeout=10)

    # Delete custom tools index (removes all registered tools)
    print("\n--- Deleting custom tools index ---")
    requests.delete(f"{OS_URL}/.plugins-ml-custom-tools", headers=HEADERS, timeout=10)

    print("\nWipe complete.")


# ---------------------------------------------------------------------------
# Base Setup: Search Templates
# ---------------------------------------------------------------------------


def register_search_templates():
    """Register 3 stored search templates via the _scripts API."""

    # Template 1: product_search
    # Use raw Mustache source strings to preserve conditional sections
    print("\n--- Registering product_search template ---")
    os_request(
        "PUT",
        "/_scripts/product_search",
        {
            "script": {
                "lang": "mustache",
                "source": (
                    '{"query":{"bool":{"must":[{"match":{"title":"{{query_text}}"}}]'
                    '{{#category}},"filter":[{"term":{"category":"{{category}}"}}]{{/category}}}}'
                    ',"from":{{#from}}{{from}}{{/from}}{{^from}}0{{/from}}'
                    ',"size":{{#size}}{{size}}{{/size}}{{^size}}20{{/size}}}'
                ),
            }
        },
    )

    # Template 2: log_search
    print("\n--- Registering log_search template ---")
    os_request(
        "PUT",
        "/_scripts/log_search",
        {
            "script": {
                "lang": "mustache",
                "source": (
                    '{"query":{"bool":{"must":[{"range":{"timestamp":{"gte":"{{start_date}}","lte":"{{end_date}}"}}}'
                    '{{#level}},{"term":{"level":"{{level}}"}}{{/level}}]}},'
                    '"size":{{#size}}{{size}}{{/size}}{{^size}}50{{/size}}}'
                ),
            }
        },
    )

    # Template 3: geo_search
    print("\n--- Registering geo_search template ---")
    os_request(
        "PUT",
        "/_scripts/geo_search",
        {
            "script": {
                "lang": "mustache",
                "source": (
                    '{"query":{"bool":{"must":[{"match":{"name":"{{search_text}}"}}],'
                    '"filter":[{"geo_distance":{"distance":"{{radius}}",'
                    '"location":{"lat":{{lat}},"lon":{{lon}}}}}]}},'
                    '"size":{{#size}}{{size}}{{/size}}{{^size}}10{{/size}}}'
                ),
            }
        },
    )


# ---------------------------------------------------------------------------
# Base Setup: Test Indices and Sample Data
# ---------------------------------------------------------------------------


def create_test_data():
    """Create test indices with sample documents so searches return results."""

    # --- products index ---
    print("\n--- Creating products index ---")
    os_request(
        "PUT",
        "/products",
        {
            "mappings": {
                "properties": {
                    "title": {"type": "text"},
                    "category": {"type": "keyword"},
                    "price": {"type": "float"},
                }
            }
        },
    )

    products = [
        {"title": "Wireless Bluetooth Headphones", "category": "electronics", "price": 59.99},
        {"title": "Noise Cancelling Wireless Headphones Pro", "category": "electronics", "price": 149.99},
        {"title": "Gaming Laptop 15 inch", "category": "electronics", "price": 999.99},
        {"title": "Tablet 10 inch with Stylus", "category": "electronics", "price": 449.99},
        {"title": "Organic Green Tea", "category": "groceries", "price": 12.99},
        {"title": "Running Shoes Lightweight", "category": "sports", "price": 89.99},
    ]

    for i, doc in enumerate(products):
        os_request("PUT", f"/products/_doc/{i + 1}", doc)

    # --- logs index ---
    print("\n--- Creating logs index ---")
    os_request(
        "PUT",
        "/logs",
        {
            "mappings": {
                "properties": {
                    "timestamp": {"type": "date"},
                    "level": {"type": "keyword"},
                    "message": {"type": "text"},
                }
            }
        },
    )

    logs = [
        {"timestamp": "2024-03-15T10:00:00Z", "level": "ERROR", "message": "Connection timeout to database"},
        {"timestamp": "2024-06-20T14:30:00Z", "level": "WARN", "message": "High memory usage detected"},
        {"timestamp": "2024-09-01T08:15:00Z", "level": "INFO", "message": "Service started successfully"},
        {"timestamp": "2024-11-10T22:45:00Z", "level": "ERROR", "message": "Disk space critically low"},
        {"timestamp": "2024-12-25T00:00:00Z", "level": "INFO", "message": "Scheduled maintenance complete"},
    ]

    for i, doc in enumerate(logs):
        os_request("PUT", f"/logs/_doc/{i + 1}", doc)

    # --- locations index (for geo_search template) ---
    print("\n--- Creating locations index ---")
    os_request(
        "PUT",
        "/locations",
        {
            "mappings": {
                "properties": {
                    "name": {"type": "text"},
                    "location": {"type": "geo_point"},
                    "type": {"type": "keyword"},
                }
            }
        },
    )

    locations = [
        {"name": "Central Park Coffee Shop", "location": {"lat": 40.7829, "lon": -73.9654}, "type": "cafe"},
        {"name": "Brooklyn Roasters", "location": {"lat": 40.6892, "lon": -73.9857}, "type": "cafe"},
        {"name": "Times Square Deli", "location": {"lat": 40.7580, "lon": -73.9855}, "type": "restaurant"},
        {"name": "SoHo Coffee House", "location": {"lat": 40.7233, "lon": -74.0030}, "type": "cafe"},
    ]

    for i, doc in enumerate(locations):
        os_request("PUT", f"/locations/_doc/{i + 1}", doc)

    # Refresh indices so documents are searchable immediately
    print("\n--- Refreshing indices ---")
    os_request("POST", "/products,logs,locations/_refresh")


# ---------------------------------------------------------------------------
# Base Setup: Bedrock Connector and Model
# ---------------------------------------------------------------------------


def setup_bedrock_model():
    """Create Bedrock connector and deploy model for LLM enrichment."""

    region = os.environ.get("AWS_REGION", AWS_REGION)

    # Register connector
    connector_body = {
        "name": "Bedrock Claude Haiku Connector",
        "description": "Connector for Claude 3 Haiku via Bedrock",
        "version": "1.0",
        "protocol": "aws_sigv4",
        "credential": {
            "access_key": os.environ.get("AWS_ACCESS_KEY_ID", ""),
            "secret_key": os.environ.get("AWS_SECRET_ACCESS_KEY", ""),
            "session_token": os.environ.get("AWS_SESSION_TOKEN", ""),
        },
        "parameters": {
            "region": region,
            "service_name": "bedrock",
            "model": BEDROCK_MODEL_ID,
        },
        "actions": [
            {
                "action_type": "predict",
                "method": "POST",
                "url": (
                    f"https://bedrock-runtime.{region}.amazonaws.com"
                    f"/model/{BEDROCK_MODEL_ID}/converse"
                ),
                "headers": {"Content-Type": "application/json"},
                "request_body": (
                    '{"system":[{"text":"${parameters.system_prompt:-You are a helpful assistant}"}],'
                    '"messages":[{"role":"user","content":[{"text":"${parameters.prompt}"}]}]'
                    "${parameters.tool_configs:-}}"
                ),
            }
        ],
    }

    resp = os_request("POST", "/_plugins/_ml/connectors/_create", connector_body)
    assert_success(resp, "Create Bedrock connector")
    connector_id = resp.json().get("connector_id")
    if not connector_id:
        print("ERROR: Failed to obtain connector_id")
        return None, None

    # Register model
    model_body = {
        "name": "Bedrock Claude Haiku",
        "function_name": "remote",
        "connector_id": connector_id,
    }
    resp = os_request("POST", "/_plugins/_ml/models/_register", model_body)
    assert_success(resp, "Register Bedrock model")

    # Handle task-based registration
    task_id = resp.json().get("task_id")
    model_id = resp.json().get("model_id")

    if task_id and not model_id:
        print(f"  Model registration is async (task_id={task_id}). Polling for completion...")
        for attempt in range(10):
            time.sleep(3)
            task_resp = os_request("GET", f"/_plugins/_ml/tasks/{task_id}")
            task_data = task_resp.json()
            state = task_data.get("state", "UNKNOWN")
            print(f"  Task state: {state}")
            if state == "COMPLETED":
                model_id = task_data.get("model_id")
                break
            if state in ("FAILED", "CANCELLED"):
                print(f"ERROR: Model registration failed with state {state}")
                return connector_id, None
        if not model_id:
            print("ERROR: Timed out waiting for model registration.")
            return connector_id, None

    print(f"  model_id = {model_id}")

    # Deploy model
    resp = os_request("POST", f"/_plugins/_ml/models/{model_id}/_deploy")
    assert_success(resp, "Deploy Bedrock model")
    print("  Waiting for model deployment to complete...")
    time.sleep(5)

    # Verify deployment
    resp = os_request("GET", f"/_plugins/_ml/models/{model_id}")
    if resp.status_code == 200:
        model_state = resp.json().get("model_state", "UNKNOWN")
        print(f"  Model state after deploy: {model_state}")

    return connector_id, model_id


# ---------------------------------------------------------------------------
# Base Setup: Register Custom Tools (3 Tiers)
# ---------------------------------------------------------------------------


def register_tier1_tool():
    """Register Tier 1 (AST only) tool: ProductSearch."""
    print("\n--- Registering Tier 1 tool: ProductSearch ---")
    body = {
        "name": "ProductSearch",
        "description": "Search products by title with optional category filter and pagination",
        "type": "search_template",
        "search_template_name": "product_search",
        "index": "products",
    }
    resp = os_request("POST", "/_plugins/_ml/tools/_create", body)
    assert_success(resp, "Register ProductSearch (Tier 1)")
    return resp


def register_tier2_tool(model_id):
    """Register Tier 2 (AST + LLM) tool: LogSearch."""
    print("\n--- Registering Tier 2 tool: LogSearch ---")
    body = {
        "name": "LogSearch",
        "description": "Search logs by date range with optional level filter",
        "type": "search_template",
        "search_template_name": "log_search",
        "index": "logs",
        "model_id": model_id,
        "llm_interface": "bedrock/converse/claude",
    }
    resp = os_request("POST", "/_plugins/_ml/tools/_create", body)
    assert_success(resp, "Register LogSearch (Tier 2)")
    return resp


def register_tier3_tool():
    """Register Tier 3 (manual params) tool: GeoSearch."""
    print("\n--- Registering Tier 3 tool: GeoSearch ---")
    body = {
        "name": "GeoSearch",
        "description": "Search locations by name within a geographic radius",
        "type": "search_template",
        "search_template_name": "geo_search",
        "index": "locations",
        "params": {
            "search_text": {
                "type": "string",
                "description": "Search text for location name",
                "required": True,
            },
            "radius": {
                "type": "string",
                "description": "Search radius (e.g., '10km')",
                "required": True,
            },
            "lat": {
                "type": "number",
                "description": "Latitude of center point",
                "required": True,
            },
            "lon": {
                "type": "number",
                "description": "Longitude of center point",
                "required": True,
            },
            "size": {
                "type": "number",
                "description": "Max results to return",
                "required": False,
                "default": "10",
            },
        },
    }
    resp = os_request("POST", "/_plugins/_ml/tools/_create", body)
    assert_success(resp, "Register GeoSearch (Tier 3)")
    return resp


# ---------------------------------------------------------------------------
# Phase 1 Tests: Execute API
# ---------------------------------------------------------------------------


def test_phase1_execute_api(has_llm_tools=False):
    """Test: POST /_plugins/_ml/tools/_execute/SearchTemplateTool with name field."""
    print_section("PHASE 1: Execute API Tests")

    passed = 0
    failed = 0

    # Test 1: Execute ProductSearch (Tier 1 tool)
    print("\n--- Test 1: Execute Tier 1 tool (ProductSearch) ---")
    try:
        resp = os_request(
            "POST",
            "/_plugins/_ml/tools/_execute/SearchTemplateTool",
            {
                "name": "ProductSearch",
                "parameters": {
                    "query_text": "wireless headphones",
                    "size": "5",
                },
            },
        )
        assert_success(resp, "Execute ProductSearch")
        result = resp.json()
        print(f"  Result: {json.dumps(result, indent=2)[:600]}")
        if 200 <= resp.status_code < 300:
            passed += 1
        else:
            failed += 1
    except Exception as exc:
        print(f"  ERROR: {exc}")
        traceback.print_exc()
        failed += 1

    # Test 2: Execute LogSearch (Tier 2 tool — only if LLM tools were registered)
    if has_llm_tools:
        print("\n--- Test 2: Execute Tier 2 tool (LogSearch) ---")
        try:
            resp = os_request(
                "POST",
                "/_plugins/_ml/tools/_execute/SearchTemplateTool",
                {
                    "name": "LogSearch",
                    "parameters": {
                        "start_date": "2024-01-01",
                        "end_date": "2024-12-31",
                    },
                },
            )
            assert_success(resp, "Execute LogSearch")
            if 200 <= resp.status_code < 300:
                passed += 1
            else:
                failed += 1
        except Exception as exc:
            print(f"  ERROR: {exc}")
            traceback.print_exc()
            failed += 1
    else:
        print("\n--- Test 2: SKIPPED (Tier 2 tool not registered, --skip-llm) ---")

    # Test 3: Execute GeoSearch (Tier 3 tool)
    print("\n--- Test 3: Execute Tier 3 tool (GeoSearch) ---")
    try:
        resp = os_request(
            "POST",
            "/_plugins/_ml/tools/_execute/SearchTemplateTool",
            {
                "name": "GeoSearch",
                "parameters": {
                    "search_text": "coffee shop",
                    "radius": "5km",
                    "lat": "40.7128",
                    "lon": "-74.0060",
                },
            },
        )
        assert_success(resp, "Execute GeoSearch")
        if 200 <= resp.status_code < 300:
            passed += 1
        else:
            failed += 1
    except Exception as exc:
        print(f"  ERROR: {exc}")
        traceback.print_exc()
        failed += 1

    # Test 4: Execute with category filter (optional param)
    print("\n--- Test 4: Execute with optional category filter ---")
    try:
        resp = os_request(
            "POST",
            "/_plugins/_ml/tools/_execute/SearchTemplateTool",
            {
                "name": "ProductSearch",
                "parameters": {
                    "query_text": "laptop",
                    "category": "electronics",
                    "size": "3",
                },
            },
        )
        assert_success(resp, "Execute ProductSearch with category")
        if 200 <= resp.status_code < 300:
            passed += 1
        else:
            failed += 1
    except Exception as exc:
        print(f"  ERROR: {exc}")
        traceback.print_exc()
        failed += 1

    # Test 5: Execute without name (backward compat - direct search_template_name)
    print("\n--- Test 5: Backward compat - direct search_template_name ---")
    try:
        resp = os_request(
            "POST",
            "/_plugins/_ml/tools/_execute/SearchTemplateTool",
            {
                "parameters": {
                    "search_template_name": "product_search",
                    "query_text": "tablet",
                },
            },
        )
        assert_success(resp, "Execute with direct search_template_name")
        if 200 <= resp.status_code < 300:
            passed += 1
        else:
            failed += 1
    except Exception as exc:
        print(f"  ERROR: {exc}")
        traceback.print_exc()
        failed += 1

    # Test 6: Execute with non-existent tool name (should fail)
    print("\n--- Test 6: Non-existent tool name (expect error) ---")
    try:
        resp = os_request(
            "POST",
            "/_plugins/_ml/tools/_execute/SearchTemplateTool",
            {
                "name": "NonExistentTool",
                "parameters": {
                    "query_text": "test",
                },
            },
        )
        if resp.status_code >= 400:
            print(f"  PASS: Got expected error: {resp.status_code}")
            passed += 1
        else:
            print(f"  FAIL: Expected error but got {resp.status_code}")
            failed += 1
    except Exception as exc:
        print(f"  ERROR: {exc}")
        traceback.print_exc()
        failed += 1

    # Test 7: Execute with missing required param (should fail validation)
    print("\n--- Test 7: Missing required param (expect error) ---")
    try:
        resp = os_request(
            "POST",
            "/_plugins/_ml/tools/_execute/SearchTemplateTool",
            {
                "name": "GeoSearch",
                "parameters": {
                    "search_text": "park",
                    # Missing required: radius, lat, lon
                },
            },
        )
        if resp.status_code >= 400:
            print(f"  PASS: Got expected validation error: {resp.status_code}")
            passed += 1
        else:
            print(f"  FAIL: Expected validation error but got {resp.status_code}")
            failed += 1
    except Exception as exc:
        print(f"  ERROR: {exc}")
        traceback.print_exc()
        failed += 1

    # Summary
    total = passed + failed
    print("\n" + "-" * 50)
    print(f"Phase 1 Results: {passed}/{total} passed, {failed}/{total} failed")
    print("-" * 50)

    return passed, failed


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def main():
    parser = argparse.ArgumentParser(
        description="E2E test for custom search template tools"
    )
    parser.add_argument(
        "--skip-setup",
        action="store_true",
        help="Skip base setup (templates, tools, data)",
    )
    parser.add_argument(
        "--skip-llm",
        action="store_true",
        help="Skip Tier 2 LLM setup (no AWS creds needed)",
    )
    parser.add_argument(
        "--phase",
        type=int,
        default=1,
        help="Which phase to test (1=execute API, 2=agents+MCP)",
    )
    parser.add_argument(
        "--os-url",
        default="http://localhost:9200",
        help="OpenSearch URL",
    )
    args = parser.parse_args()

    global OS_URL
    OS_URL = args.os_url

    print("=" * 70)
    print("  Custom Search Template Tools -- E2E Test Suite")
    print("=" * 70)
    print(f"  OpenSearch URL : {OS_URL}")
    print(f"  Skip setup     : {args.skip_setup}")
    print(f"  Skip LLM       : {args.skip_llm}")
    print(f"  Phase          : {args.phase}")
    print("=" * 70)

    total_passed = 0
    total_failed = 0

    if not args.skip_setup:
        wait_for_cluster()

        print_section("WIPE: Cleaning up previous state")
        wipe_previous_state()

        print_section("BASE SETUP: Search Templates")
        register_search_templates()

        print_section("BASE SETUP: Test Data")
        create_test_data()

        model_id = None
        if not args.skip_llm:
            print_section("BASE SETUP: Bedrock Model")
            try:
                _, model_id = setup_bedrock_model()
            except Exception as exc:
                print(f"WARNING: Bedrock model setup failed: {exc}")
                traceback.print_exc()
                print("Continuing without Tier 2 tool...")

        print_section("BASE SETUP: Register Custom Tools")
        register_tier1_tool()
        register_tier3_tool()
        has_llm_tools = False
        if model_id:
            register_tier2_tool(model_id)
            has_llm_tools = True
        else:
            print("\nSkipping Tier 2 tool registration (no LLM model)")
    else:
        has_llm_tools = not args.skip_llm  # assume tools exist if skipping setup

    if args.phase >= 1:
        try:
            p, f = test_phase1_execute_api(has_llm_tools=has_llm_tools)
            total_passed += p
            total_failed += f
        except Exception as exc:
            print(f"ERROR: Phase 1 tests failed unexpectedly: {exc}")
            traceback.print_exc()
            total_failed += 1

    print("\n" + "=" * 70)
    print("  TEST SUITE COMPLETE")
    print(f"  Total: {total_passed} passed, {total_failed} failed")
    print("=" * 70)

    # Exit with non-zero code if any tests failed
    if total_failed > 0:
        sys.exit(1)


if __name__ == "__main__":
    main()
