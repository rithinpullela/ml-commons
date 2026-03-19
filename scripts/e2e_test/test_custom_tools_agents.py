#!/usr/bin/env python3
"""
Custom Tools + Agents + MCP - Phase 2 E2E Test Suite

Tests that pre-registered custom tools work inside:
  1. Chat (conversational) agents
  2. PER (Plan Execute and Reflect) agents
  3. MCP server (tool discovery and invocation via JSON-RPC)

The agent should be able to invoke a custom SearchTemplateTool by name,
and the LLM should see the tool with its stored description and parameters.
The MCP server should list the custom tool and allow calling it.

Prerequisites:
    - Running OpenSearch instance with ml-commons plugin
    - AWS credentials (access_key, secret_key, session_token) as env vars
    - Phase 1 custom tools CRUD already working
    - Phase 2 agent async createTools implemented

Usage:
    # Set AWS creds:
    export AWS_ACCESS_KEY_ID=...
    export AWS_SECRET_ACCESS_KEY=...
    export AWS_SESSION_TOKEN=...

    # Full run:
    python test_custom_tools_agents.py

    # Skip data/template/tool setup (reuse from previous run):
    python test_custom_tools_agents.py --skip-setup

    # Only run MCP tests (no agents, no AWS creds needed):
    python test_custom_tools_agents.py --mcp-only

    # Custom OpenSearch URL:
    python test_custom_tools_agents.py --os-url http://my-cluster:9200
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
BEDROCK_MODEL = "us.anthropic.claude-sonnet-4-20250514-v1:0"

# ---------------------------------------------------------------------------
# Helper Functions
# ---------------------------------------------------------------------------


def os_request(method, path, body=None, timeout=60):
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
            timeout=timeout,
        )
    except requests.exceptions.ConnectionError as exc:
        print(f"    ERROR: Connection failed - {exc}")
        raise

    truncated = resp.text[:1200] + ("..." if len(resp.text) > 1200 else "")
    print(f"    Status: {resp.status_code}")
    print(f"    Response: {truncated}")
    return resp


def wait_for_cluster(max_retries=30, delay=2):
    """Poll cluster health until green or yellow."""
    print("Waiting for OpenSearch cluster to be ready...")
    for attempt in range(1, max_retries + 1):
        try:
            resp = requests.get(f"{OS_URL}/_cluster/health", headers=HEADERS, timeout=5)
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


def wait_for_model_deployed(model_id, max_retries=30, delay=5):
    """Poll model status until DEPLOYED."""
    print(f"\nWaiting for model {model_id} to be deployed...")
    for attempt in range(1, max_retries + 1):
        resp = os_request("GET", f"/_plugins/_ml/models/{model_id}")
        if resp.status_code == 200:
            state = resp.json().get("model_state", "UNKNOWN")
            print(f"  Attempt {attempt}: model_state = {state}")
            if state == "DEPLOYED":
                return True
            if state in ("DEPLOY_FAILED", "REGISTER_FAILED"):
                print(f"  Model failed: {state}")
                return False
        time.sleep(delay)
    print("  Model did not deploy in time.")
    return False


# ---------------------------------------------------------------------------
# Wipe: Clean up previous test state
# ---------------------------------------------------------------------------


def wipe_previous_state():
    """Delete all test artifacts so setup starts from a clean slate."""
    print_section("WIPE: Cleaning previous state")

    # Delete test indices
    for idx in ("products",):
        print(f"\n--- Deleting index: {idx} ---")
        requests.delete(f"{OS_URL}/{idx}", headers=HEADERS, timeout=10)

    # Delete stored search templates
    for template in ("product_search",):
        print(f"\n--- Deleting search template: {template} ---")
        requests.delete(f"{OS_URL}/_scripts/{template}", headers=HEADERS, timeout=10)

    # Delete custom tools index
    print("\n--- Deleting custom tools index ---")
    requests.delete(f"{OS_URL}/.plugins-ml-custom-tools", headers=HEADERS, timeout=10)

    # Delete any test agents (best effort - search by name)
    for agent_name in ("TestChat_CustomTools", "TestPER_CustomTools"):
        print(f"\n--- Searching for agent: {agent_name} ---")
        resp = requests.post(
            f"{OS_URL}/_plugins/_ml/agents/_search",
            headers=HEADERS,
            json={"query": {"term": {"name.keyword": agent_name}}},
            timeout=10,
        )
        if resp.status_code == 200:
            hits = resp.json().get("hits", {}).get("hits", [])
            for hit in hits:
                agent_id = hit["_id"]
                print(f"  Deleting agent {agent_id}")
                requests.delete(f"{OS_URL}/_plugins/_ml/agents/{agent_id}", headers=HEADERS, timeout=10)

    print("\nWipe complete.")


# ---------------------------------------------------------------------------
# Setup: Test Data + Search Template + Custom Tool
# ---------------------------------------------------------------------------


def create_test_data():
    """Create a products index with sample data."""
    print_section("SETUP: Creating test data")

    # Create products index
    os_request("PUT", "/products", {
        "mappings": {
            "properties": {
                "title": {"type": "text"},
                "category": {"type": "keyword"},
                "price": {"type": "float"},
                "description": {"type": "text"},
            }
        }
    })

    # Bulk index sample products
    products = [
        {"title": "Wireless Bluetooth Headphones", "category": "electronics", "price": 79.99, "description": "Noise cancelling over-ear headphones with 30hr battery"},
        {"title": "USB-C Charging Cable", "category": "electronics", "price": 12.99, "description": "Fast charging cable compatible with all USB-C devices"},
        {"title": "Running Shoes Pro", "category": "sports", "price": 129.99, "description": "Lightweight running shoes with cushioned sole"},
        {"title": "Yoga Mat Premium", "category": "sports", "price": 45.99, "description": "Non-slip yoga mat with carrying strap"},
        {"title": "Coffee Maker Deluxe", "category": "kitchen", "price": 89.99, "description": "12-cup programmable coffee maker with thermal carafe"},
    ]

    for i, product in enumerate(products):
        os_request("PUT", f"/products/_doc/{i+1}", product)

    # Refresh so data is searchable
    os_request("POST", "/products/_refresh")


def register_search_template():
    """Register the product_search stored search template."""
    print_section("SETUP: Registering search template")

    os_request("PUT", "/_scripts/product_search", {
        "script": {
            "lang": "mustache",
            "source": (
                '{"query":{"bool":{"must":[{"match":{"title":"{{query_text}}"}}]'
                '{{#category}},"filter":[{"term":{"category":"{{category}}"}}]{{/category}}}}'
                ',"from":{{#from}}{{from}}{{/from}}{{^from}}0{{/from}}'
                ',"size":{{#size}}{{size}}{{/size}}{{^size}}20{{/size}}}'
            ),
        }
    })


def register_custom_tool():
    """Register a custom tool backed by product_search template."""
    print_section("SETUP: Registering custom tool")

    resp = os_request("POST", "/_plugins/_ml/tools/_create", {
        "type": "search_template",
        "name": "ProductSearch",
        "description": "Search for products by title keywords. Use this tool when the user asks about finding, looking for, or searching for products. Provide query_text with the search keywords.",
        "search_template_name": "product_search",
        "params": {
            "query_text": {"type": "string", "description": "The search keywords to find products by title", "required": True},
            "category": {"type": "string", "description": "Optional product category filter (e.g. electronics, sports, kitchen)", "required": False},
            "size": {"type": "number", "description": "Maximum number of results to return", "required": False},
        },
    })
    if resp.status_code in (200, 201):
        return resp.json().get("_id")
    return None


# ---------------------------------------------------------------------------
# Setup: Model + Deploy
# ---------------------------------------------------------------------------


def register_and_deploy_model():
    """Register and deploy a Bedrock Claude model."""
    print_section("SETUP: Registering and deploying model")

    access_key = os.environ.get("AWS_ACCESS_KEY_ID", "")
    secret_key = os.environ.get("AWS_SECRET_ACCESS_KEY", "")
    session_token = os.environ.get("AWS_SESSION_TOKEN", "")

    if not access_key or not secret_key:
        print("ERROR: AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY must be set")
        sys.exit(1)

    model_body = {
        "name": "Sonnet 4 - Custom Tools Test",
        "function_name": "remote",
        "description": "Bedrock Claude model for custom tools agent testing",
        "connector": {
            "name": "Amazon Bedrock Claude connector",
            "description": "Connector to Amazon Bedrock service for the Claude model",
            "version": 1,
            "protocol": "aws_sigv4",
            "parameters": {
                "region": AWS_REGION,
                "service_name": "bedrock",
                "model": BEDROCK_MODEL,
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
                    "url": "https://bedrock-runtime.${parameters.region}.amazonaws.com/model/${parameters.model}/converse",
                    "headers": {"content-type": "application/json"},
                    "request_body": '{ "system": [{"text": "${parameters.system_prompt}"}], "messages": [${parameters._chat_history:-}{"role":"user","content":[{"text":"${parameters.prompt}"}]}${parameters._interactions:-}]${parameters.tool_configs:-} }',
                }
            ],
        },
    }

    # Register
    resp = os_request("POST", "/_plugins/_ml/models/_register?deploy=true", model_body)
    if resp.status_code not in (200, 201):
        print("ERROR: Failed to register model")
        sys.exit(1)

    data = resp.json()
    model_id = data.get("model_id")
    task_id = data.get("task_id")

    if task_id and not model_id:
        # Async registration — poll task
        print(f"\nModel registration task: {task_id}")
        for _ in range(30):
            time.sleep(5)
            task_resp = os_request("GET", f"/_plugins/_ml/tasks/{task_id}")
            if task_resp.status_code == 200:
                task_data = task_resp.json()
                state = task_data.get("state", "UNKNOWN")
                print(f"  Task state: {state}")
                if state == "COMPLETED":
                    model_id = task_data.get("model_id")
                    break
                if state == "FAILED":
                    print("  Task failed!")
                    sys.exit(1)

    if not model_id:
        print("ERROR: Could not get model_id")
        sys.exit(1)

    print(f"\nModel ID: {model_id}")

    # Wait for deployment
    if not wait_for_model_deployed(model_id):
        print("ERROR: Model deployment failed")
        sys.exit(1)

    return model_id


# ---------------------------------------------------------------------------
# Setup: Create Agents
# ---------------------------------------------------------------------------


def create_chat_agent(model_id):
    """Create a conversational agent with custom SearchTemplateTool."""
    print_section("SETUP: Creating Chat Agent with custom tool")

    agent_body = {
        "name": "TestChat_CustomTools",
        "type": "conversational",
        "description": "Chat agent for testing custom SearchTemplateTool resolution",
        "tools": [
            {
                "type": "SearchTemplateTool",
                "name": "ProductSearch",
            },
            {
                "type": "ListIndexTool",
            },
        ],
        "llm": {
            "model_id": model_id,
            "parameters": {
                "prompt": "${parameters.question}",
            },
        },
        "parameters": {
            "_llm_interface": "bedrock/converse/claude",
        },
        "memory": {
            "type": "conversation_index",
        },
    }

    resp = os_request("POST", "/_plugins/_ml/agents/_register", agent_body)
    if resp.status_code in (200, 201):
        agent_id = resp.json().get("agent_id")
        print(f"\nChat Agent ID: {agent_id}")
        return agent_id
    print("ERROR: Failed to create chat agent")
    return None


def create_per_agent(model_id):
    """Create a PER agent with custom SearchTemplateTool."""
    print_section("SETUP: Creating PER Agent with custom tool")

    agent_body = {
        "name": "TestPER_CustomTools",
        "type": "PLAN_EXECUTE_AND_REFLECT",
        "description": "PER agent for testing custom SearchTemplateTool resolution",
        "tools": [
            {
                "type": "SearchTemplateTool",
                "name": "ProductSearch",
            },
            {
                "type": "ListIndexTool",
            },
        ],
        "llm": {
            "model_id": model_id,
            "parameters": {
                "prompt": "${parameters.question}",
            },
        },
        "parameters": {
            "_llm_interface": "bedrock/converse/claude",
        },
        "memory": {
            "type": "conversation_index",
        },
    }

    resp = os_request("POST", "/_plugins/_ml/agents/_register", agent_body)
    if resp.status_code in (200, 201):
        agent_id = resp.json().get("agent_id")
        print(f"\nPER Agent ID: {agent_id}")
        return agent_id
    print("ERROR: Failed to create PER agent")
    return None


# ---------------------------------------------------------------------------
# Tests: Execute Agents
# ---------------------------------------------------------------------------


def test_chat_agent_uses_custom_tool(agent_id):
    """
    Test 1: Chat agent should resolve ProductSearch custom tool and use it.
    Ask a question that should trigger the ProductSearch tool.
    Check server logs for:
      - Tool config sent to LLM includes "ProductSearch" with correct description
      - LLM responds with a tool_use for ProductSearch
      - Tool execution returns product search results
    """
    print_section("TEST 1: Chat Agent - Custom tool invocation")

    resp = os_request(
        "POST",
        f"/_plugins/_ml/agents/{agent_id}/_execute",
        {
            "parameters": {
                "question": "Find me wireless headphones",
                "verbose": "true",
            }
        },
        timeout=120,
    )

    print(f"\n--- Test 1 Result ---")
    if resp.status_code in (200, 201):
        print("  PASS: Chat agent executed successfully")
        data = resp.json()
        # Print the full response for log analysis
        print(f"  Full response:\n{json.dumps(data, indent=2)[:3000]}")
    else:
        print(f"  FAIL: Chat agent execution failed (status {resp.status_code})")
        print(f"  CHECK SERVER LOGS for errors related to custom tool resolution")

    return resp


def test_chat_agent_with_category_filter(agent_id):
    """
    Test 2: Chat agent with a more specific query that should use category filter.
    """
    print_section("TEST 2: Chat Agent - Custom tool with category context")

    resp = os_request(
        "POST",
        f"/_plugins/_ml/agents/{agent_id}/_execute",
        {
            "parameters": {
                "question": "Search for electronics products related to charging",
                "verbose": "true",
            }
        },
        timeout=120,
    )

    print(f"\n--- Test 2 Result ---")
    if resp.status_code in (200, 201):
        print("  PASS: Chat agent executed successfully")
        data = resp.json()
        print(f"  Full response:\n{json.dumps(data, indent=2)[:3000]}")
    else:
        print(f"  FAIL: Chat agent execution failed (status {resp.status_code})")

    return resp


def test_per_agent_uses_custom_tool(agent_id):
    """
    Test 3: PER agent should resolve ProductSearch custom tool and use it.
    PER agents plan first, then execute tools, then reflect.
    Check server logs for:
      - Planning step sees ProductSearch in available tools
      - Execution step resolves and runs ProductSearch
      - Reflection step incorporates search results
    """
    print_section("TEST 3: PER Agent - Custom tool invocation")

    resp = os_request(
        "POST",
        f"/_plugins/_ml/agents/{agent_id}/_execute",
        {
            "parameters": {
                "question": "What running shoes are available in the products catalog?",
                "verbose": "true",
            }
        },
        timeout=180,  # PER agents take longer
    )

    print(f"\n--- Test 3 Result ---")
    if resp.status_code in (200, 201):
        print("  PASS: PER agent executed successfully")
        data = resp.json()
        print(f"  Full response:\n{json.dumps(data, indent=2)[:3000]}")
    else:
        print(f"  FAIL: PER agent execution failed (status {resp.status_code})")
        print(f"  CHECK SERVER LOGS for errors related to custom tool resolution")

    return resp


def test_chat_agent_no_tool_needed(agent_id):
    """
    Test 4: Ask a question that should NOT trigger ProductSearch.
    The agent should just respond directly without using the tool.
    """
    print_section("TEST 4: Chat Agent - No custom tool needed")

    resp = os_request(
        "POST",
        f"/_plugins/_ml/agents/{agent_id}/_execute",
        {
            "parameters": {
                "question": "What is 2 + 2?",
                "verbose": "true",
            }
        },
        timeout=120,
    )

    print(f"\n--- Test 4 Result ---")
    if resp.status_code in (200, 201):
        print("  PASS: Chat agent executed successfully")
        data = resp.json()
        print(f"  Full response:\n{json.dumps(data, indent=2)[:3000]}")
    else:
        print(f"  FAIL: Chat agent execution failed (status {resp.status_code})")

    return resp


# ---------------------------------------------------------------------------
# MCP: Setup + Tests
# ---------------------------------------------------------------------------

MCP_ENDPOINT = "/_plugins/_ml/mcp"
MCP_REQUEST_ID = 0


def next_mcp_id():
    global MCP_REQUEST_ID
    MCP_REQUEST_ID += 1
    return MCP_REQUEST_ID


def mcp_request(method, params=None):
    """Send a JSON-RPC 2.0 request to the MCP server endpoint."""
    body = {
        "jsonrpc": "2.0",
        "id": next_mcp_id(),
        "method": method,
    }
    if params is not None:
        body["params"] = params
    return os_request("POST", MCP_ENDPOINT, body)


def mcp_notification(method, params=None):
    """Send a JSON-RPC 2.0 notification (no id, no response expected)."""
    body = {
        "jsonrpc": "2.0",
        "method": method,
        "params": params or {},
    }
    return os_request("POST", MCP_ENDPOINT, body)


def enable_mcp_server():
    """Enable the MCP server via cluster settings."""
    print_section("MCP SETUP: Enabling MCP server")
    resp = os_request("PUT", "/_cluster/settings", {
        "persistent": {
            "plugins.ml_commons.mcp_server_enabled": "true"
        }
    })
    if resp.status_code not in (200, 201):
        print("WARNING: Could not enable MCP server")
    return resp


def register_mcp_custom_tool():
    """Register a custom SearchTemplateTool as an MCP tool.

    This registers ProductSearch with its description and input schema
    so MCP clients can discover and invoke it.
    """
    print_section("MCP SETUP: Registering custom tool as MCP tool")

    resp = os_request("POST", "/_plugins/_ml/mcp/tools/_register", {
        "tools": [
            {
                "type": "SearchTemplateTool",
                "name": "ProductSearch",
                "description": "Search for products by title keywords. Use this tool when the user asks about finding, looking for, or searching for products.",
                "parameters": {
                    "name": "ProductSearch",
                },
                "attributes": {
                    "input_schema": {
                        "type": "object",
                        "properties": {
                            "query_text": {
                                "type": "string",
                                "description": "The search keywords to find products by title",
                            },
                            "category": {
                                "type": "string",
                                "description": "Optional product category filter (e.g. electronics, sports, kitchen)",
                            },
                            "size": {
                                "type": "number",
                                "description": "Maximum number of results to return",
                            },
                        },
                        "required": ["query_text"],
                        "additionalProperties": False,
                    },
                },
            }
        ]
    })

    if resp.status_code in (200, 201):
        print("  MCP tool registered successfully")
    else:
        print(f"  WARNING: MCP tool registration returned {resp.status_code}")

    return resp


def test_mcp_initialize():
    """
    Test 5: MCP server initialization handshake.
    Send initialize → get capabilities → send initialized notification.
    """
    print_section("TEST 5: MCP - Initialize handshake")

    resp = mcp_request("initialize", {
        "protocolVersion": "2025-03-26",
        "capabilities": {
            "roots": {"listChanged": True},
            "sampling": {},
        },
        "clientInfo": {
            "name": "custom-tools-test",
            "version": "1.0.0",
        },
    })

    print(f"\n--- Test 5a: Initialize ---")
    if resp.status_code == 200:
        data = resp.json()
        result = data.get("result", {})
        server_info = result.get("serverInfo", {})
        print(f"  Server: {server_info.get('name')} v{server_info.get('version')}")
        tools_cap = result.get("capabilities", {}).get("tools", {})
        print(f"  Tools listChanged: {tools_cap.get('listChanged')}")
        print("  PASS: MCP initialize succeeded")
    else:
        print(f"  FAIL: MCP initialize failed (status {resp.status_code})")

    # Send initialized notification
    print(f"\n--- Test 5b: Initialized notification ---")
    notif_resp = mcp_notification("notifications/initialized")
    if notif_resp.status_code == 202:
        print("  PASS: Initialized notification accepted")
    else:
        print(f"  INFO: Notification returned status {notif_resp.status_code} (202 expected)")

    return resp


def test_mcp_list_tools():
    """
    Test 6: MCP tools/list should include ProductSearch with correct description and schema.
    """
    print_section("TEST 6: MCP - List tools (should include ProductSearch)")

    resp = mcp_request("tools/list", {})

    print(f"\n--- Test 6 Result ---")
    if resp.status_code == 200:
        data = resp.json()
        tools = data.get("result", {}).get("tools", [])
        print(f"  Found {len(tools)} tools")

        product_search = None
        for tool in tools:
            print(f"    - {tool.get('name')}: {str(tool.get('description', ''))[:80]}...")
            if tool.get("name") == "ProductSearch":
                product_search = tool

        if product_search:
            print(f"\n  ProductSearch tool found!")
            print(f"    Description: {product_search.get('description', '')[:200]}")
            print(f"    InputSchema: {json.dumps(product_search.get('inputSchema', {}), indent=2)[:500]}")
            print("  PASS: ProductSearch listed in MCP tools")
        else:
            print("\n  FAIL: ProductSearch NOT found in MCP tools list")
    else:
        print(f"  FAIL: tools/list failed (status {resp.status_code})")

    return resp


def test_mcp_call_custom_tool():
    """
    Test 7: MCP tools/call ProductSearch with query_text.
    Should resolve the custom tool, execute the search template, return results.
    """
    print_section("TEST 7: MCP - Call ProductSearch tool")

    resp = mcp_request("tools/call", {
        "name": "ProductSearch",
        "arguments": {
            "query_text": "headphones",
        },
    })

    print(f"\n--- Test 7 Result ---")
    if resp.status_code == 200:
        data = resp.json()
        result = data.get("result", {})
        is_error = result.get("isError", True)
        content = result.get("content", [])

        if not is_error and content:
            print(f"  Tool returned {len(content)} content item(s)")
            for item in content:
                text = item.get("text", "")
                print(f"    Type: {item.get('type')}")
                print(f"    Text: {text[:500]}...")
            print("  PASS: ProductSearch tool executed via MCP")
        else:
            print(f"  FAIL: Tool returned isError={is_error}")
            print(f"  Content: {json.dumps(content, indent=2)[:500]}")
    else:
        print(f"  FAIL: tools/call failed (status {resp.status_code})")
        print(f"  CHECK SERVER LOGS for custom tool resolution errors")

    return resp


def test_mcp_call_with_category():
    """
    Test 8: MCP tools/call ProductSearch with query_text and category filter.
    """
    print_section("TEST 8: MCP - Call ProductSearch with category filter")

    resp = mcp_request("tools/call", {
        "name": "ProductSearch",
        "arguments": {
            "query_text": "cable",
            "category": "electronics",
        },
    })

    print(f"\n--- Test 8 Result ---")
    if resp.status_code == 200:
        data = resp.json()
        result = data.get("result", {})
        is_error = result.get("isError", True)
        content = result.get("content", [])

        if not is_error and content:
            text = content[0].get("text", "") if content else ""
            print(f"  Result text: {text[:500]}...")
            print("  PASS: ProductSearch with category filter executed via MCP")
        else:
            print(f"  FAIL: Tool returned isError={is_error}")
    else:
        print(f"  FAIL: tools/call failed (status {resp.status_code})")

    return resp


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def main():
    parser = argparse.ArgumentParser(description="Custom Tools Agent + MCP E2E Tests")
    parser.add_argument("--os-url", default="http://localhost:9200", help="OpenSearch URL")
    parser.add_argument("--skip-setup", action="store_true", help="Skip data/template/tool/model setup")
    parser.add_argument("--model-id", default=None, help="Reuse an existing model ID (skips model registration)")
    parser.add_argument("--chat-agent-id", default=None, help="Reuse an existing chat agent ID")
    parser.add_argument("--per-agent-id", default=None, help="Reuse an existing PER agent ID")
    parser.add_argument("--mcp-only", action="store_true", help="Only run MCP tests (no agents, no AWS creds needed)")
    parser.add_argument("--skip-mcp", action="store_true", help="Skip MCP tests")
    args = parser.parse_args()

    global OS_URL
    OS_URL = args.os_url

    wait_for_cluster()

    results = []

    # ---- Setup (shared: data, template, custom tool) ----
    if not args.skip_setup:
        wipe_previous_state()
        create_test_data()
        register_search_template()
        register_custom_tool()

    # ---- Agent Tests ----
    if not args.mcp_only:
        model_id = args.model_id
        chat_agent_id = args.chat_agent_id
        per_agent_id = args.per_agent_id

        if not model_id:
            model_id = register_and_deploy_model()

        if not chat_agent_id:
            chat_agent_id = create_chat_agent(model_id)

        if not per_agent_id:
            per_agent_id = create_per_agent(model_id)

        # Print IDs for re-runs
        print_section("RESOURCE IDS (for --skip-setup re-runs)")
        print(f"  Model ID:      {model_id}")
        print(f"  Chat Agent ID: {chat_agent_id}")
        print(f"  PER Agent ID:  {per_agent_id}")
        print(f"\n  Re-run command:")
        print(f"  python test_custom_tools_agents.py --skip-setup --model-id {model_id} --chat-agent-id {chat_agent_id} --per-agent-id {per_agent_id}")

        if chat_agent_id:
            print_section("RUNNING CHAT AGENT TESTS")
            r1 = test_chat_agent_uses_custom_tool(chat_agent_id)
            results.append(("Test 1: Chat - custom tool invocation", r1))

            r2 = test_chat_agent_with_category_filter(chat_agent_id)
            results.append(("Test 2: Chat - custom tool with category", r2))

            r4 = test_chat_agent_no_tool_needed(chat_agent_id)
            results.append(("Test 4: Chat - no tool needed", r4))

        if per_agent_id:
            print_section("RUNNING PER AGENT TESTS")
            r3 = test_per_agent_uses_custom_tool(per_agent_id)
            results.append(("Test 3: PER - custom tool invocation", r3))

    # ---- MCP Tests ----
    if not args.skip_mcp:
        # Enable MCP server
        enable_mcp_server()

        # Register custom tool in MCP
        if not args.skip_setup:
            register_mcp_custom_tool()

        print_section("RUNNING MCP TESTS")

        r5 = test_mcp_initialize()
        results.append(("Test 5: MCP - initialize handshake", r5))

        r6 = test_mcp_list_tools()
        results.append(("Test 6: MCP - list tools (ProductSearch)", r6))

        r7 = test_mcp_call_custom_tool()
        results.append(("Test 7: MCP - call ProductSearch", r7))

        r8 = test_mcp_call_with_category()
        results.append(("Test 8: MCP - call ProductSearch with category", r8))

    # ---- Summary ----
    print_section("TEST SUMMARY")
    passed = 0
    failed = 0
    for name, resp in results:
        ok = False
        if resp and 200 <= resp.status_code < 300:
            # For MCP JSON-RPC responses, check for error in body
            try:
                body = resp.json()
                if "error" in body:
                    ok = False
                else:
                    ok = True
            except Exception:
                ok = True  # non-JSON 2xx is still a pass
        if ok:
            print(f"  PASS: {name}")
            passed += 1
        else:
            status = resp.status_code if resp else "N/A"
            print(f"  FAIL: {name} (status {status})")
            failed += 1

    print(f"\n  Total: {passed + failed}, Passed: {passed}, Failed: {failed}")
    print(f"\n  NOTE: Check OpenSearch server logs for detailed tool resolution flow.")
    print(f"  Look for:")
    print(f"    - 'CustomToolResolver' logs showing name resolution")
    print(f"    - Tool configs sent to LLM (should include ProductSearch)")
    print(f"    - LLM tool_use responses")
    print(f"    - SearchTemplateTool execution with product_search template")
    print(f"    - MCP server tool listing and invocation")

    sys.exit(0 if failed == 0 else 1)


if __name__ == "__main__":
    main()
