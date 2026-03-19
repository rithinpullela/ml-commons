# SearchTemplateTool — Interface Specification

## Overview

Search template tools are pre-registered tool definitions stored in a system index. Once created via the tool registration API, they can be referenced by **name** across:

- **Tool Execute API** — direct execution
- **Agents** — attach to conversational/flow agents
- **MCP Server** — expose via Model Context Protocol

The key principle: **define once, use everywhere**. The stored tool definition includes the search template reference, parameter schema (types, descriptions, required/optional, defaults), and description — all auto-populated during creation.

---

## 1. Registering a Tool

### Tier 1 — AST-only (no LLM)

Parameters are auto-extracted from the Mustache template AST with heuristic types and descriptions.

```bash
POST /_plugins/_ml/tools/_create
{
  "name": "ProductSearch",
  "description": "Search products by title with optional category filter and pagination",
  "type": "search_template",
  "search_template_name": "product_search"
}
```

### Tier 2 — AST + LLM enrichment

Same as Tier 1, but also calls an LLM to generate human-quality parameter descriptions.

```bash
POST /_plugins/_ml/tools/_create
{
  "name": "ProductSearch",
  "description": "Search products by title with optional category filter and pagination",
  "type": "search_template",
  "search_template_name": "product_search",
  "model_id": "abc123",
  "llm_interface": "bedrock/converse/claude"
}
```

### Tier 3 — Manual params

User provides parameter definitions manually. No AST extraction or LLM.

```bash
POST /_plugins/_ml/tools/_create
{
  "name": "ProductSearch",
  "description": "Search products by title with optional category filter and pagination",
  "type": "search_template",
  "search_template_name": "product_search",
  "params": {
    "query_text": { "type": "string", "description": "Search query for product titles", "required": true },
    "category":   { "type": "string", "description": "Filter by product category", "required": false },
    "from":       { "type": "number", "description": "Pagination offset", "required": false, "default": "0" },
    "size":       { "type": "number", "description": "Results per page", "required": false, "default": "20" }
  }
}
```

### Response (all tiers)

```json
{
  "tool_id": "xyz789",
  "params": {
    "query_text": {
      "type": "string",
      "description": "The search query text to match against product titles.",
      "required": true
    },
    "category": {
      "type": "string",
      "description": "The product category to filter results by.",
      "required": false
    },
    "from": {
      "type": "number",
      "description": "The starting offset for pagination.",
      "required": false,
      "default": "0"
    },
    "size": {
      "type": "number",
      "description": "The maximum number of results to return per page.",
      "required": false,
      "default": "20"
    }
  }
}
```

---

## 2. Tool Execute API

### With a pre-registered tool (by name)

The `name` field is a **top-level field** in the request body, separate from `parameters`. This avoids any collision with template parameter names.

```bash
POST /_plugins/_ml/tools/_execute/SearchTemplateTool
{
  "name": "ProductSearch",
  "parameters": {
    "query_text": "wireless headphones",
    "category": "electronics",
    "size": "5"
  }
}
```

- `name` — references the pre-registered tool. The system fetches `search_template_name`, param definitions, and description from the index.
- `parameters` — runtime values. These override stored defaults (e.g., stored `size` default is 20, but user passes 5).

### Without a pre-registered tool (direct usage)

For ad-hoc usage without a pre-registered tool, provide `search_template_name` directly in parameters (existing behavior, fully backward compatible):

```bash
POST /_plugins/_ml/tools/_execute/SearchTemplateTool
{
  "parameters": {
    "search_template_name": "product_search",
    "query_text": "wireless headphones"
  }
}
```

### Response

```json
{
  "inference_results": [{
    "output": [{
      "name": "response",
      "result": "{\"_index\":\"products\",\"_id\":\"abc\",\"_score\":0.87,\"_source\":{\"title\":\"Wireless Bluetooth Headphones\",\"category\":\"electronics\",\"price\":49.99}}\n"
    }]
  }]
}
```

---

## 3. Agents

### Minimal — just type and name

Just provide `type` and `name`. Everything else (description, input_schema, search_template_name) is auto-fetched from the tool's stored definition.

```bash
POST /_plugins/_ml/agents/_register
{
  "name": "Shopping Assistant",
  "type": "conversational",
  "llm": {
    "model_id": "abc123",
    "parameters": {
      "response_filter": "$.output.message.content[0].text"
    }
  },
  "tools": [
    {
      "type": "SearchTemplateTool",
      "name": "ProductSearch"
    }
  ]
}
```

Here `name` serves as the **lookup name** (to fetch the stored definition) and the **display name** (what the LLM sees in function calling).

### Overriding description and input_schema

Description and input_schema can be overridden per-agent. The stored tool definition provides defaults; user-provided values take precedence.

```bash
POST /_plugins/_ml/agents/_register
{
  "name": "Shopping Assistant",
  "type": "conversational",
  "llm": { "model_id": "abc123" },
  "tools": [
    {
      "type": "SearchTemplateTool",
      "name": "ProductSearch",
      "description": "Use this tool when the user asks about products. Search by keywords and optionally filter by category.",
      "attributes": {
        "input_schema": "{\"type\":\"object\",\"properties\":{\"query_text\":{\"type\":\"string\",\"description\":\"Product search keywords\"}},\"required\":[\"query_text\"]}"
      }
    }
  ]
}
```

> **Note on input_schema override:** The `input_schema` controls what the LLM sees and can call. However, the tool still validates against the **stored** parameter definitions. If you override the schema to remove a required parameter, the LLM won't provide it, and the tool will reject the call with a validation error. Override the schema only when you know what you're doing.

### What the LLM sees (auto-generated from stored params)

When no overrides are provided, the input_schema is built automatically from the stored parameter definitions.

```json
{
  "name": "ProductSearch",
  "description": "Search products by title with optional category filter and pagination",
  "input_schema": {
    "type": "object",
    "properties": {
      "query_text": {
        "type": "string",
        "description": "The search query text to match against product titles."
      },
      "category": {
        "type": "string",
        "description": "The product category to filter results by."
      },
      "from": {
        "type": "number",
        "description": "The starting offset for pagination."
      },
      "size": {
        "type": "number",
        "description": "The maximum number of results to return per page."
      }
    },
    "required": ["query_text"]
  }
}
```

### Execute agent

```bash
POST /_plugins/_ml/agents/<agent_id>/_execute
{
  "parameters": {
    "question": "Find me wireless headphones under $50"
  }
}
```

The LLM generates a tool call like `ProductSearch(query_text="wireless headphones")`, the SearchTemplateTool renders the template and executes the search, and the LLM summarizes the results.

---

## 4. MCP Server

### Register tool with MCP (minimal)

```bash
POST /_plugins/_ml/mcp/tools/_register
{
  "tools": [
    {
      "type": "SearchTemplateTool",
      "name": "ProductSearch"
    }
  ]
}
```

No description, no parameters, no attributes needed. The factory fetches everything from the stored tool definition.

### Register tool with MCP (with overrides)

Description and schema can be overridden at MCP registration time, same as agents.

```bash
POST /_plugins/_ml/mcp/tools/_register
{
  "tools": [
    {
      "type": "SearchTemplateTool",
      "name": "ProductSearch",
      "description": "MCP-specific description for product search",
      "attributes": {
        "input_schema": "{...schema override...}"
      }
    }
  ]
}
```

### MCP client: list_tools response

```json
{
  "tools": [
    {
      "name": "ProductSearch",
      "description": "Search products by title with optional category filter and pagination",
      "inputSchema": {
        "type": "object",
        "properties": {
          "query_text": {
            "type": "string",
            "description": "The search query text to match against product titles."
          },
          "category": {
            "type": "string",
            "description": "The product category to filter results by."
          },
          "from": {
            "type": "number",
            "description": "The starting offset for pagination."
          },
          "size": {
            "type": "number",
            "description": "The maximum number of results to return per page."
          }
        },
        "required": ["query_text"]
      }
    }
  ]
}
```

### MCP client: call_tool

```json
{
  "name": "ProductSearch",
  "arguments": {
    "query_text": "wireless headphones",
    "category": "electronics"
  }
}
```

### MCP client: call_tool response

```json
{
  "content": [
    {
      "type": "text",
      "text": "{\"_index\":\"products\",\"_id\":\"abc\",\"_score\":0.87,\"_source\":{\"title\":\"Wireless Bluetooth Headphones\",\"category\":\"electronics\",\"price\":49.99}}\n"
    }
  ]
}
```

---

## 5. Override & Fallback Chain

When a tool is referenced by name, the system resolves each field using the following priority:

| Field | Priority (highest → lowest) |
|-------|----------------------------|
| **Description** | User-provided at agent/MCP config → Stored tool description → Factory default |
| **Input Schema** | User-provided `attributes.input_schema` → Built from stored tool params → Factory default |
| **Runtime params** | User execution-time values → Stored param defaults |

### Override behavior

- **Description override** — Changes what the LLM/MCP client sees. No side effects.
- **Input schema override** — Changes what the LLM/MCP client sees as available parameters. The tool still validates against the **stored** parameter definitions. Removing a required field from the schema will cause a validation error at execution time, because the LLM won't provide a parameter the tool expects.
- **Runtime param override** — Stored defaults (e.g., `size=20`) are used when the caller doesn't provide a value. Caller-provided values always take precedence.

### Name resolution

| Context | Lookup name (index fetch) | Display name (LLM/MCP sees) |
|---------|--------------------------|----------------------------|
| **Execute API** | Top-level `name` field | N/A |
| **Agent** | `name` | `name` |
| **MCP** | `name` | `name` |

---

## 6. Backward Compatibility

All existing usage patterns continue to work unchanged:

| Pattern | Status |
|---------|--------|
| `SearchTemplateTool` with explicit `search_template_name` in params | Works as before |
| `SearchTemplateTool` in agent config with `config.search_template_name` | Works as before |
| `SearchTemplateTool` registered in MCP with explicit parameters | Works as before |
| Other built-in tools (SearchIndexTool, ListIndexTool, etc.) | Unaffected |

The `name` lookup is additive — it only triggers when `search_template_name` is NOT provided directly. Existing configs that specify `search_template_name` are unaffected.
