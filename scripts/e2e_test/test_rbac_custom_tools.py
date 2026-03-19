#!/usr/bin/env python3
"""
Custom Tools RBAC — End-to-End Test Suite

Tests role-based access control for custom tools against a running OpenSearch
instance with the Security plugin enabled.

Covers:
  - PRIVATE access mode (owner-only visibility and mutation)
  - PUBLIC access mode (everyone can see, only owner can mutate)
  - RESTRICTED access mode (backend-role-based visibility)
  - Admin override (admins bypass all checks)
  - Validation edge cases (invalid param combos, disabled AC)

Prerequisites:
  - OpenSearch running with Security plugin enabled
  - ML Commons plugin installed

Usage:
    python test_rbac_custom_tools.py
    python test_rbac_custom_tools.py --os-url https://localhost:9200
    python test_rbac_custom_tools.py --admin-pass myAdminPass --user1-pass u1pass --user2-pass u2pass
    python test_rbac_custom_tools.py --skip-setup   # reuse existing users/templates
    python test_rbac_custom_tools.py --skip-cleanup  # leave test data for debugging
"""

import argparse
import json
import sys
import traceback
import urllib3

import requests

# Suppress InsecureRequestWarning for self-signed certs
urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

OS_URL = "https://localhost:9200"
HEADERS = {"Content-Type": "application/json"}

ADMIN_USER = "admin"
ADMIN_PASS = "admin"
USER1_NAME = "user1"
USER1_PASS = "user1"
USER2_NAME = "user2"
USER2_PASS = "user2"

# ---------------------------------------------------------------------------
# Test tracking
# ---------------------------------------------------------------------------

_passed = 0
_failed = 0


def _reset_counts():
    global _passed, _failed
    _passed = 0
    _failed = 0


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def os_request(method, path, body=None, user=None, password=None):
    """Make an authenticated request to OpenSearch."""
    url = f"{OS_URL}{path}"
    auth = (user or ADMIN_USER, password or ADMIN_PASS)
    resp = requests.request(
        method.upper(),
        url,
        headers=HEADERS,
        json=body,
        auth=auth,
        verify=False,
        timeout=30,
    )
    return resp


def assert_status(test_name, expected, resp):
    """Assert HTTP status code."""
    global _passed, _failed
    actual = resp.status_code
    if actual == expected:
        print(f"  PASS: {test_name} (HTTP {actual})")
        _passed += 1
    else:
        print(f"  FAIL: {test_name} (expected {expected}, got {actual})")
        print(f"        Body: {resp.text[:300]}")
        _failed += 1


def assert_contains(test_name, substr, resp_text):
    """Assert response body contains a substring."""
    global _passed, _failed
    if substr in resp_text:
        print(f"  PASS: {test_name}")
        _passed += 1
    else:
        print(f"  FAIL: {test_name} (response missing '{substr}')")
        print(f"        Body: {resp_text[:300]}")
        _failed += 1


def assert_not_contains(test_name, substr, resp_text):
    """Assert response body does NOT contain a substring."""
    global _passed, _failed
    if substr not in resp_text:
        print(f"  PASS: {test_name}")
        _passed += 1
    else:
        print(f"  FAIL: {test_name} (response should NOT contain '{substr}')")
        print(f"        Body: {resp_text[:300]}")
        _failed += 1


def extract_tool_id(resp):
    """Extract _id from a create response."""
    try:
        return resp.json().get("_id", "")
    except Exception:
        return ""


def print_section(title):
    print(f"\n{'=' * 60}")
    print(f"  {title}")
    print(f"{'=' * 60}")


# ---------------------------------------------------------------------------
# Setup: Users, roles, templates, settings
# ---------------------------------------------------------------------------


def setup_users_and_roles():
    """Create test roles and users for RBAC testing."""
    print("\nCreating ML custom tool role...")
    os_request("PUT", "/_plugins/_security/api/roles/ml_custom_tool_role", {
        "cluster_permissions": ["cluster_monitor", "cluster:admin/opensearch/ml/*", "cluster:admin/script/get"],
        "index_permissions": [{
            "index_patterns": [".plugins-ml-*"],
            "allowed_actions": ["crud", "indices_monitor"],
        }],
    })

    print("Creating user1 (backend_role: role_a)...")
    os_request("PUT", "/_plugins/_security/api/internalusers/user1", {
        "password": USER1_PASS,
        "backend_roles": ["role_a"],
    })

    print("Creating user2 (backend_role: role_b)...")
    os_request("PUT", "/_plugins/_security/api/internalusers/user2", {
        "password": USER2_PASS,
        "backend_roles": ["role_b"],
    })

    print("Mapping users to role...")
    os_request("PUT", "/_plugins/_security/api/rolesmapping/ml_custom_tool_role", {
        "backend_roles": ["role_a", "role_b"],
        "users": ["user1", "user2"],
    })


def setup_search_template():
    """Create a simple search template for tools to reference."""
    print("\nCreating test search template 'test-rbac-template'...")
    resp = os_request("POST", "/_scripts/test-rbac-template", {
        "script": {
            "lang": "mustache",
            "source": '{"query":{"match":{"{{field}}":"{{value}}"}}}',
        },
    })
    assert_status("Create search template", 200, resp)


def enable_access_control():
    """Enable custom tool access control cluster setting."""
    print("\nEnabling custom_tool_access_control_enabled...")
    resp = os_request("PUT", "/_cluster/settings", {
        "persistent": {
            "plugins.ml_commons.custom_tool_access_control_enabled": True,
        },
    })
    assert_contains("Enable access control", "acknowledged", resp.text)


def disable_access_control():
    """Disable custom tool access control cluster setting."""
    print("\nDisabling custom_tool_access_control_enabled...")
    os_request("PUT", "/_cluster/settings", {
        "persistent": {
            "plugins.ml_commons.custom_tool_access_control_enabled": False,
        },
    })


# ---------------------------------------------------------------------------
# Phase 1: PRIVATE access mode
# ---------------------------------------------------------------------------


def test_private_access():
    print_section("Phase 1: PRIVATE Access Mode")

    # user1 creates a PRIVATE tool
    print("\nuser1 creates a PRIVATE custom tool...")
    resp = os_request("POST", "/_plugins/_ml/tools/_create", {
        "name": "rbac_private_tool",
        "description": "Private tool owned by user1",
        "type": "search_template",
        "search_template_name": "test-rbac-template",
        "access": "private",
    }, user=USER1_NAME, password=USER1_PASS)
    assert_status("user1 creates PRIVATE tool", 200, resp)
    tool_id = extract_tool_id(resp)
    print(f"  Tool ID: {tool_id}")

    # user1 can see their private tool in list
    print("\nuser1 lists tools (should see private tool)...")
    resp = os_request("GET", "/_plugins/_ml/tools", user=USER1_NAME, password=USER1_PASS)
    assert_contains("user1 sees own PRIVATE tool in list", "rbac_private_tool", resp.text)

    # user2 should NOT see user1's private tool
    print("\nuser2 lists tools (should NOT see user1's private tool)...")
    resp = os_request("GET", "/_plugins/_ml/tools", user=USER2_NAME, password=USER2_PASS)
    assert_not_contains("user2 cannot see user1's PRIVATE tool", "rbac_private_tool", resp.text)

    if tool_id:
        # user2 cannot update user1's private tool
        print("\nuser2 tries to update user1's PRIVATE tool...")
        resp = os_request("PUT", f"/_plugins/_ml/tools/{tool_id}", {
            "description": "hacked by user2",
        }, user=USER2_NAME, password=USER2_PASS)
        assert_status("user2 cannot update user1's PRIVATE tool", 403, resp)

        # user2 cannot delete user1's private tool
        print("\nuser2 tries to delete user1's PRIVATE tool...")
        resp = os_request("DELETE", f"/_plugins/_ml/tools/{tool_id}",
                          user=USER2_NAME, password=USER2_PASS)
        assert_status("user2 cannot delete user1's PRIVATE tool", 403, resp)

        # user1 can update their own tool
        print("\nuser1 updates own PRIVATE tool...")
        resp = os_request("PUT", f"/_plugins/_ml/tools/{tool_id}", {
            "description": "Updated by owner",
        }, user=USER1_NAME, password=USER1_PASS)
        assert_status("user1 updates own PRIVATE tool", 200, resp)

        # user1 can delete their own tool
        print("\nuser1 deletes own PRIVATE tool...")
        resp = os_request("DELETE", f"/_plugins/_ml/tools/{tool_id}",
                          user=USER1_NAME, password=USER1_PASS)
        assert_status("user1 deletes own PRIVATE tool", 200, resp)


# ---------------------------------------------------------------------------
# Phase 2: PUBLIC access mode
# ---------------------------------------------------------------------------


def test_public_access():
    print_section("Phase 2: PUBLIC Access Mode")

    # user1 creates a PUBLIC tool
    print("\nuser1 creates a PUBLIC custom tool...")
    resp = os_request("POST", "/_plugins/_ml/tools/_create", {
        "name": "rbac_public_tool",
        "description": "Public tool created by user1",
        "type": "search_template",
        "search_template_name": "test-rbac-template",
        "access": "public",
    }, user=USER1_NAME, password=USER1_PASS)
    assert_status("user1 creates PUBLIC tool", 200, resp)
    tool_id = extract_tool_id(resp)
    print(f"  Tool ID: {tool_id}")

    # user2 can see the public tool
    print("\nuser2 lists tools (should see PUBLIC tool)...")
    resp = os_request("GET", "/_plugins/_ml/tools", user=USER2_NAME, password=USER2_PASS)
    assert_contains("user2 can see PUBLIC tool", "rbac_public_tool", resp.text)

    if tool_id:
        # user2 cannot update user1's public tool (not owner)
        print("\nuser2 tries to update user1's PUBLIC tool...")
        resp = os_request("PUT", f"/_plugins/_ml/tools/{tool_id}", {
            "description": "hacked by user2",
        }, user=USER2_NAME, password=USER2_PASS)
        assert_status("user2 cannot update user1's PUBLIC tool", 403, resp)

        # user2 cannot delete user1's public tool
        print("\nuser2 tries to delete user1's PUBLIC tool...")
        resp = os_request("DELETE", f"/_plugins/_ml/tools/{tool_id}",
                          user=USER2_NAME, password=USER2_PASS)
        assert_status("user2 cannot delete user1's PUBLIC tool", 403, resp)

    return tool_id


# ---------------------------------------------------------------------------
# Phase 3: RESTRICTED access mode
# ---------------------------------------------------------------------------


def test_restricted_access():
    print_section("Phase 3: RESTRICTED Access Mode")

    # user1 creates a RESTRICTED tool with backend_role "role_a"
    print("\nuser1 creates a RESTRICTED tool (backend_roles: [role_a])...")
    resp = os_request("POST", "/_plugins/_ml/tools/_create", {
        "name": "rbac_restricted_tool_a",
        "description": "Restricted to role_a users",
        "type": "search_template",
        "search_template_name": "test-rbac-template",
        "access": "restricted",
        "backend_roles": ["role_a"],
    }, user=USER1_NAME, password=USER1_PASS)
    assert_status("user1 creates RESTRICTED tool (role_a)", 200, resp)
    tool_id = extract_tool_id(resp)
    print(f"  Tool ID: {tool_id}")

    # user1 (role_a) can see the restricted tool
    print("\nuser1 lists tools (should see restricted tool)...")
    resp = os_request("GET", "/_plugins/_ml/tools", user=USER1_NAME, password=USER1_PASS)
    assert_contains("user1 (role_a) sees RESTRICTED(role_a) tool", "rbac_restricted_tool_a", resp.text)

    # user2 (role_b) should NOT see tool restricted to role_a
    print("\nuser2 lists tools (should NOT see role_a restricted tool)...")
    resp = os_request("GET", "/_plugins/_ml/tools", user=USER2_NAME, password=USER2_PASS)
    assert_not_contains("user2 (role_b) cannot see RESTRICTED(role_a) tool", "rbac_restricted_tool_a", resp.text)

    if tool_id:
        # user2 cannot update restricted tool
        print("\nuser2 tries to update RESTRICTED(role_a) tool...")
        resp = os_request("PUT", f"/_plugins/_ml/tools/{tool_id}", {
            "description": "hacked",
        }, user=USER2_NAME, password=USER2_PASS)
        assert_status("user2 cannot update RESTRICTED(role_a) tool", 403, resp)

    # user1 creates tool with add_all_backend_roles
    print("\nuser1 creates RESTRICTED tool with add_all_backend_roles...")
    resp = os_request("POST", "/_plugins/_ml/tools/_create", {
        "name": "rbac_restricted_all_roles",
        "description": "Restricted to all of user1's roles",
        "type": "search_template",
        "search_template_name": "test-rbac-template",
        "add_all_backend_roles": True,
    }, user=USER1_NAME, password=USER1_PASS)
    assert_status("user1 creates RESTRICTED tool (add_all_backend_roles)", 200, resp)

    return tool_id


# ---------------------------------------------------------------------------
# Phase 4: Admin override
# ---------------------------------------------------------------------------


def test_admin_override(public_tool_id, restricted_tool_id):
    print_section("Phase 4: Admin Override")

    # Admin can see all tools
    print("\nadmin lists tools (should see ALL tools)...")
    resp = os_request("GET", "/_plugins/_ml/tools")
    assert_contains("admin sees PUBLIC tool", "rbac_public_tool", resp.text)
    assert_contains("admin sees RESTRICTED(role_a) tool", "rbac_restricted_tool_a", resp.text)

    # Admin can update any tool
    if public_tool_id:
        print("\nadmin updates user1's PUBLIC tool...")
        resp = os_request("PUT", f"/_plugins/_ml/tools/{public_tool_id}", {
            "description": "Updated by admin",
        })
        assert_status("admin can update user1's PUBLIC tool", 200, resp)

    # Admin can delete any tool
    if restricted_tool_id:
        print("\nadmin deletes user1's RESTRICTED tool...")
        resp = os_request("DELETE", f"/_plugins/_ml/tools/{restricted_tool_id}")
        assert_status("admin can delete user1's RESTRICTED tool", 200, resp)


# ---------------------------------------------------------------------------
# Phase 5: Validation edge cases
# ---------------------------------------------------------------------------


def test_validation_edge_cases():
    print_section("Phase 5: Validation Edge Cases")

    # PUBLIC + backend_roles -> should fail
    print("\nuser1 tries PUBLIC + backend_roles (should fail)...")
    resp = os_request("POST", "/_plugins/_ml/tools/_create", {
        "name": "should_fail_public_with_roles",
        "description": "Invalid combo",
        "type": "search_template",
        "search_template_name": "test-rbac-template",
        "access": "public",
        "backend_roles": ["role_a"],
    }, user=USER1_NAME, password=USER1_PASS)
    assert_status("Reject PUBLIC with backend_roles", 400, resp)

    # PRIVATE + backend_roles -> should fail
    print("\nuser1 tries PRIVATE + backend_roles (should fail)...")
    resp = os_request("POST", "/_plugins/_ml/tools/_create", {
        "name": "should_fail_private_with_roles",
        "description": "Invalid combo",
        "type": "search_template",
        "search_template_name": "test-rbac-template",
        "access": "private",
        "backend_roles": ["role_a"],
    }, user=USER1_NAME, password=USER1_PASS)
    assert_status("Reject PRIVATE with backend_roles", 400, resp)

    # RESTRICTED without backend_roles and without add_all -> should fail
    print("\nuser1 tries RESTRICTED without backend_roles (should fail)...")
    resp = os_request("POST", "/_plugins/_ml/tools/_create", {
        "name": "should_fail_restricted_no_roles",
        "description": "Invalid combo",
        "type": "search_template",
        "search_template_name": "test-rbac-template",
        "access": "restricted",
    }, user=USER1_NAME, password=USER1_PASS)
    assert_status("Reject RESTRICTED without backend_roles", 400, resp)

    # Admin + add_all_backend_roles -> should fail
    print("\nadmin tries add_all_backend_roles (should fail)...")
    resp = os_request("POST", "/_plugins/_ml/tools/_create", {
        "name": "should_fail_admin_all_roles",
        "description": "Invalid for admin",
        "type": "search_template",
        "search_template_name": "test-rbac-template",
        "add_all_backend_roles": True,
    })
    assert_status("Reject admin add_all_backend_roles", 400, resp)

    # user1 specifying backend roles they don't have -> should fail
    print("\nuser1 tries specifying backend_role they don't have...")
    resp = os_request("POST", "/_plugins/_ml/tools/_create", {
        "name": "should_fail_wrong_role",
        "description": "User1 does not have role_b",
        "type": "search_template",
        "search_template_name": "test-rbac-template",
        "access": "restricted",
        "backend_roles": ["role_b"],
    }, user=USER1_NAME, password=USER1_PASS)
    assert_status("Reject user specifying roles they don't have", 400, resp)


# ---------------------------------------------------------------------------
# Phase 6: Access control disabled
# ---------------------------------------------------------------------------


def test_access_control_disabled():
    print_section("Phase 6: Access Control Disabled")

    disable_access_control()

    # Specifying RBAC params when AC disabled -> should fail
    print("\nuser1 tries to create tool with access param when AC disabled...")
    resp = os_request("POST", "/_plugins/_ml/tools/_create", {
        "name": "should_fail_ac_disabled",
        "description": "AC disabled test",
        "type": "search_template",
        "search_template_name": "test-rbac-template",
        "access": "public",
    }, user=USER1_NAME, password=USER1_PASS)
    assert_status("Reject RBAC params when AC disabled", 400, resp)

    # Without RBAC params, creation should work
    print("\nuser1 creates tool without RBAC params when AC disabled...")
    resp = os_request("POST", "/_plugins/_ml/tools/_create", {
        "name": "no_rbac_tool",
        "description": "No RBAC params",
        "type": "search_template",
        "search_template_name": "test-rbac-template",
    }, user=USER1_NAME, password=USER1_PASS)
    assert_status("Create tool without RBAC when AC disabled", 200, resp)
    tool_id = extract_tool_id(resp)

    # Anyone can modify tools when AC disabled
    if tool_id:
        print("\nuser2 updates tool when AC disabled...")
        resp = os_request("PUT", f"/_plugins/_ml/tools/{tool_id}", {
            "description": "Updated by user2 when AC disabled",
        }, user=USER2_NAME, password=USER2_PASS)
        assert_status("user2 can update any tool when AC disabled", 200, resp)

        # Clean up
        os_request("DELETE", f"/_plugins/_ml/tools/{tool_id}")

    # Re-enable for any subsequent tests
    enable_access_control()


# ---------------------------------------------------------------------------
# Cleanup
# ---------------------------------------------------------------------------


def cleanup(public_tool_id):
    print_section("Cleanup")

    # Delete remaining tools by ID
    if public_tool_id:
        os_request("DELETE", f"/_plugins/_ml/tools/{public_tool_id}")

    # Delete tools by name search
    for name in ("rbac_restricted_all_roles",):
        resp = os_request("POST", "/.plugins-ml-custom-tools/_search", {
            "query": {"term": {"name.keyword": name}},
        })
        try:
            hits = resp.json().get("hits", {}).get("hits", [])
            for hit in hits:
                os_request("DELETE", f"/_plugins/_ml/tools/{hit['_id']}")
        except Exception:
            pass

    # Delete search template
    os_request("DELETE", "/_scripts/test-rbac-template")

    print("  Cleanup complete.")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def main():
    global OS_URL, ADMIN_PASS, USER1_PASS, USER2_PASS

    parser = argparse.ArgumentParser(description="E2E RBAC test for custom tools")
    parser.add_argument("--os-url", default=OS_URL, help="OpenSearch URL")
    parser.add_argument("--admin-pass", default=ADMIN_PASS, help="Admin password")
    parser.add_argument("--user1-pass", default=USER1_PASS, help="User1 password")
    parser.add_argument("--user2-pass", default=USER2_PASS, help="User2 password")
    parser.add_argument("--skip-setup", action="store_true", help="Skip user/template setup")
    parser.add_argument("--skip-cleanup", action="store_true", help="Leave test data for debugging")
    args = parser.parse_args()

    OS_URL = args.os_url
    ADMIN_PASS = args.admin_pass
    USER1_PASS = args.user1_pass
    USER2_PASS = args.user2_pass

    print("=" * 60)
    print("  Custom Tools RBAC — E2E Test Suite")
    print("=" * 60)
    print(f"  OpenSearch URL : {OS_URL}")
    print(f"  Skip setup     : {args.skip_setup}")
    print(f"  Skip cleanup   : {args.skip_cleanup}")
    print("=" * 60)

    # Check cluster reachability
    try:
        resp = os_request("GET", "/_cluster/health")
        if resp.status_code != 200:
            print(f"ERROR: Cannot connect to cluster (HTTP {resp.status_code})")
            sys.exit(1)
        print(f"  Cluster status: {resp.json().get('status', 'unknown')}")
    except requests.exceptions.ConnectionError as e:
        print(f"ERROR: Cannot connect to {OS_URL}: {e}")
        sys.exit(1)

    # Setup
    if not args.skip_setup:
        print_section("Setup: Users, Roles, Templates")
        setup_users_and_roles()
        setup_search_template()
        enable_access_control()

    # Run tests
    public_tool_id = None
    restricted_tool_id = None
    try:
        test_private_access()
        public_tool_id = test_public_access()
        restricted_tool_id = test_restricted_access()
        test_admin_override(public_tool_id, restricted_tool_id)
        test_validation_edge_cases()
        test_access_control_disabled()
    except Exception as exc:
        print(f"\nERROR: Test failed unexpectedly: {exc}")
        traceback.print_exc()

    # Cleanup
    if not args.skip_cleanup:
        try:
            cleanup(public_tool_id)
        except Exception as exc:
            print(f"  Cleanup error (non-fatal): {exc}")

    # Summary
    total = _passed + _failed
    print(f"\n{'=' * 60}")
    print("  TEST RESULTS")
    print(f"{'=' * 60}")
    print(f"  Total:  {total}")
    print(f"  Passed: {_passed}")
    print(f"  Failed: {_failed}")
    print(f"{'=' * 60}")

    if _failed > 0:
        print("\n  Some tests FAILED!")
        sys.exit(1)
    else:
        print("\n  All tests PASSED!")


if __name__ == "__main__":
    main()
