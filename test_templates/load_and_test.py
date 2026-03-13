#!/usr/bin/env python3
"""
Load search templates into OpenSearch, create custom tools via ml-commons,
and compare auto-extracted params against expected values.

Usage:
    python3 load_and_test.py [--host HOST] [--cleanup] [--only TEMPLATE_ID]

Examples:
    python3 load_and_test.py                          # Run all tests
    python3 load_and_test.py --only t05               # Run one test (prefix match)
    python3 load_and_test.py --cleanup                # Delete all test templates + tools, then run
    python3 load_and_test.py --host https://my-host:9200
"""

import argparse
import json
import sys
import time
from datetime import datetime
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError


def api(method, host, path, body=None):
    """Make a request to OpenSearch. Returns parsed JSON or None on 404."""
    url = f"{host}{path}"
    data = json.dumps(body).encode() if body else None
    req = Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    try:
        with urlopen(req) as resp:
            return json.loads(resp.read())
    except HTTPError as e:
        if e.code == 404:
            return None
        error_body = e.read().decode()
        try:
            error_json = json.loads(error_body)
            return {"error": error_json, "status": e.code}
        except json.JSONDecodeError:
            return {"error": error_body, "status": e.code}


def put_template(host, template_id, source):
    """Store a search template (script) in OpenSearch."""
    return api("POST", host, f"/_scripts/{template_id}", {
        "script": {
            "lang": "mustache",
            "source": source
        }
    })


def delete_template(host, template_id):
    """Delete a search template."""
    return api("DELETE", host, f"/_scripts/{template_id}")


def create_tool(host, name, template_id):
    """Create a custom tool via the ml-commons API (Tier 1 — auto-extract)."""
    return api("POST", host, "/_plugins/_ml/tools/_create", {
        "name": name,
        "description": f"Auto-test tool for {template_id}",
        "type": "search_template",
        "search_template_name": template_id
    })


def delete_tool_by_name(host, name):
    """Search for and delete a custom tool by name."""
    result = api("POST", host, "/.plugins-ml-custom-tools/_search", {
        "query": {"term": {"name.keyword": name}}
    })
    if result and "hits" in result:
        for hit in result["hits"].get("hits", []):
            api("DELETE", host, f"/_plugins/_ml/tools/{hit['_id']}")


def compare_params(expected, actual):
    """Compare expected params to actual extracted params. Returns list of diffs."""
    diffs = []

    expected_names = set(expected.keys())
    actual_names = set(actual.keys()) if actual else set()

    for name in sorted(expected_names - actual_names):
        diffs.append(f"MISSING param: '{name}'")

    for name in sorted(actual_names - expected_names):
        diffs.append(f"EXTRA param: '{name}' = {json.dumps(actual[name])}")

    for name in sorted(expected_names & actual_names):
        exp = expected[name]
        act = actual[name]

        if exp.get("type") and act.get("type") != exp["type"]:
            diffs.append(f"'{name}' type: expected={exp['type']}, got={act.get('type')}")

        if "required" in exp and act.get("required") != exp["required"]:
            diffs.append(f"'{name}' required: expected={exp['required']}, got={act.get('required')}")

        if "default" in exp and act.get("default") != exp["default"]:
            diffs.append(f"'{name}' default: expected={exp['default']}, got={act.get('default')}")

    return diffs


def run_tests(host, templates, cleanup=False, only=None):
    """Run the full test suite. Returns (results_list, passed, failed, errors)."""
    passed = 0
    failed = 0
    errors = 0
    results = []

    filtered = templates
    if only:
        filtered = [t for t in templates if only in t["id"]]
        if not filtered:
            print(f"No templates matching '{only}'. Available: {[t['id'] for t in templates]}")
            return [], 0, 0, 0

    print(f"\nRunning {len(filtered)} tests against {host}\n")
    print("=" * 80)

    for tmpl in filtered:
        tid = tmpl["id"]
        tool_name = f"test_{tid}"
        desc = tmpl["description"]
        note = tmpl.get("expected_note", "")
        print(f"\n[{tid}] {desc}")
        if note:
            print(f"  NOTE: {note}")

        # Skip _comment entries
        if tid == "_comment":
            continue

        # Cleanup existing if requested
        if cleanup:
            delete_tool_by_name(host, tool_name)
            delete_template(host, tid)

        # 1. Store the search template
        put_result = put_template(host, tid, tmpl["source"])
        if put_result and "error" in put_result:
            err_detail = json.dumps(put_result["error"], indent=2)
            print(f"  ERROR storing template: {err_detail}")
            errors += 1
            results.append({
                "id": tid, "description": desc, "status": "ERROR",
                "detail": f"Failed to store template: {err_detail}",
                "note": note
            })
            continue

        # 2. Delete any existing tool with this name (idempotent re-runs)
        delete_tool_by_name(host, tool_name)

        # small delay for index refresh
        time.sleep(0.3)

        # 3. Create tool (triggers auto-extraction)
        tool_result = create_tool(host, tool_name, tid)

        if not tool_result or "error" in tool_result:
            err_msg = json.dumps(tool_result.get("error", "unknown"), indent=2) if tool_result else "No response"
            if tmpl.get("expected_error"):
                print(f"  PASS (expected error)")
                passed += 1
                results.append({
                    "id": tid, "description": desc, "status": "PASS",
                    "tool_id": "N/A", "param_count": 0,
                    "actual_params": {}, "note": f"Expected error: {note}"
                })
            else:
                print(f"  ERROR creating tool: {err_msg}")
                errors += 1
                results.append({
                    "id": tid, "description": desc, "status": "ERROR",
                    "detail": f"Failed to create tool: {err_msg}",
                    "note": note
                })
            continue

        tool_id = tool_result.get("tool_id", "?")
        actual_params = tool_result.get("params", {})

        # 4. Compare
        expected = tmpl.get("expected", {})
        diffs = compare_params(expected, actual_params)

        if not diffs:
            print(f"  PASS (tool_id={tool_id}, {len(actual_params)} params)")
            passed += 1
            results.append({
                "id": tid, "description": desc, "status": "PASS",
                "tool_id": tool_id, "param_count": len(actual_params),
                "actual_params": actual_params, "note": note
            })
        else:
            print(f"  FAIL (tool_id={tool_id})")
            for d in diffs:
                print(f"    - {d}")
            failed += 1
            results.append({
                "id": tid, "description": desc, "status": "FAIL",
                "tool_id": tool_id, "diffs": diffs,
                "expected": expected, "actual_params": actual_params,
                "note": note
            })

    # Summary
    total = len([t for t in filtered if t.get("id") != "_comment"])
    print("\n" + "=" * 80)
    print(f"\nRESULTS: {passed} passed, {failed} failed, {errors} errors out of {total} tests\n")

    print(f"{'Template':<35} {'Status':<8} {'Details'}")
    print("-" * 80)
    for r in results:
        tid = r["id"]
        status = r["status"]
        if status == "PASS":
            print(f"{tid:<35} {status:<8} {r['param_count']} params")
        elif status == "FAIL":
            print(f"{tid:<35} {status:<8} {r['diffs'][0]}")
            for d in r["diffs"][1:]:
                print(f"{'':35} {'':8} {d}")
        else:
            detail = r["detail"][:60]
            print(f"{tid:<35} {status:<8} {detail}")

    print()
    return results, passed, failed, errors


def write_results_file(results, passed, failed, errors, output_path):
    """Write detailed results to a markdown file."""
    with open(output_path, "w") as f:
        f.write("# Parameter Auto-Extraction Test Results\n\n")
        f.write(f"**Date:** {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n\n")
        f.write(f"**Summary:** {passed} passed, {failed} failed, {errors} errors out of {len(results)} tests\n\n")
        f.write("---\n\n")

        # Summary table
        f.write("## Summary Table\n\n")
        f.write("| # | Template | Status | Params | Notes |\n")
        f.write("|---|----------|--------|--------|-------|\n")
        for r in results:
            tid = r["id"]
            status = r["status"]
            note = r.get("note", "")
            if status == "PASS":
                f.write(f"| | `{tid}` | PASS | {r['param_count']} | {note} |\n")
            elif status == "FAIL":
                diff_summary = "; ".join(r["diffs"][:3])
                f.write(f"| | `{tid}` | **FAIL** | - | {diff_summary} |\n")
            else:
                f.write(f"| | `{tid}` | ERROR | - | {r['detail'][:80]} |\n")

        f.write("\n---\n\n")

        # Detailed results for each test
        f.write("## Detailed Results\n\n")
        for r in results:
            tid = r["id"]
            desc = r["description"]
            status = r["status"]

            f.write(f"### {tid}: {desc}\n\n")
            f.write(f"**Status:** {status}\n\n")

            if r.get("note"):
                f.write(f"**Note:** {r['note']}\n\n")

            if status == "PASS":
                f.write("**Extracted params:**\n```json\n")
                f.write(json.dumps(r["actual_params"], indent=2))
                f.write("\n```\n\n")
            elif status == "FAIL":
                f.write("**Diffs:**\n")
                for d in r["diffs"]:
                    f.write(f"- {d}\n")
                f.write("\n**Expected:**\n```json\n")
                f.write(json.dumps(r.get("expected", {}), indent=2))
                f.write("\n```\n\n**Actual:**\n```json\n")
                f.write(json.dumps(r.get("actual_params", {}), indent=2))
                f.write("\n```\n\n")
            else:
                f.write(f"**Error:** {r['detail']}\n\n")

            f.write("---\n\n")


def main():
    parser = argparse.ArgumentParser(description="Test custom tool parameter auto-extraction")
    parser.add_argument("--host", default="http://localhost:9200", help="OpenSearch host URL")
    parser.add_argument("--cleanup", action="store_true", help="Delete existing test templates and tools first")
    parser.add_argument("--only", default=None, help="Only run templates whose ID contains this string")
    parser.add_argument("--output", default=None, help="Path to write results markdown file")
    args = parser.parse_args()

    templates_path = Path(__file__).parent / "templates.json"
    with open(templates_path) as f:
        data = json.loads(f.read())

    # Filter out _comment entries
    templates = [t for t in data["templates"] if not t.get("_comment")]
    print(f"Loaded {len(templates)} test templates from {templates_path}")

    results, passed, failed, errors = run_tests(args.host, templates, cleanup=args.cleanup, only=args.only)

    # Write results file
    output_path = args.output or str(Path(__file__).parent / "results.md")
    write_results_file(results, passed, failed, errors, output_path)
    print(f"Results written to {output_path}")

    sys.exit(0 if (failed == 0 and errors == 0) else 1)


if __name__ == "__main__":
    main()
