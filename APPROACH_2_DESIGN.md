# Enterprise Agentic Search V2 — Approach 2: Simplified Interface

## 1. The Problem

Agentic Search V1 treats every natural-language query the same way: hand the LLM an index mapping, a sample document, and (optionally) a Mustache template, then ask it to generate a full OpenSearch DSL query from scratch. This works for exploration, but enterprise customers don't explore — they have **known indices, known templates, and known query shapes**. The only unknowns at query time are **which template** and **what parameter values**.

Asking the LLM to generate full DSL for a problem that is really just parameter filling is wasteful. By reducing the LLM's job to parameter extraction, we can:

* **Save tokens:** ~4,000-5,000 tokens per request reduced to ~250-500
* **Make agentic search faster:** Sub-second latency instead of 2-5 seconds
* **Guarantee DSL structure:** Template rendering replaces free-form generation — output always matches the intended query shape
* **Make agentic search cheaper:** Small, fast models handle parameter filling; no need for expensive models capable of DSL generation

### The key insight

The LLM should never see DSL it doesn't need to generate. Expose only the parameters the LLM must fill, not the template body. This makes the task simple enough for the cheapest, fastest models — and cuts tokens by ~90%.

### Before and After

**V1 — LLM generates full DSL (~300 output tokens, 2-5s):**

```
System: [3,000 tokens of rules and examples]
User:   "red shoes under $50" + index mapping + sample doc + full Mustache template
LLM:    { "query": { "bool": { "must": [ { "match": { "category": "shoes" } },
          { "range": { "price": { "lte": 50 } } }, { "term": { "color": "red" } } ] } } }
```

**V2 — LLM fills parameters via tool call (~30 output tokens, < 1 sec (goal)):**

```
System: "Extract product search parameters."
User:   "red shoes under $50"
Tools:  [ProductSearchTool(category, brand, price_max, color)]
LLM:    tool_call: ProductSearchTool(category="shoes", color="red", price_max=50)
```

The template rendering happens server-side — the LLM never sees or generates DSL.

---

## 2. Why V1 Falls Short for Enterprises

* **LLM sees full template body** — Hundreds of tokens of Mustache DSL injected into the prompt that the LLM never needs
* **LLM generates full DSL** — Even with a template as "reference," output can deviate. The template is guidance, not a contract
* **No per-template prompts** — All templates share one system prompt, but different templates need different instructions
* **2 LLM calls minimum** — Template selection + query generation, both with large prompts
* **No structured output** — Free-form text generation; no function-calling enforcement; unpredictable output tokens
* **2-5s latency** — Index mapping fetch + sample doc fetch + 2 LLM calls. Enterprise target is < 1 second
* **Output tokens are the latency bottleneck** — `time_to_last_token = TTFT + output_tokens / tokens_per_second`. The only variable we control is output tokens. Forced tool calls with parameter values produce ~30 tokens instead of ~300 for full DSL

---

## 3. Requirements

### Functional

* **F1:** LLM sees only parameter schemas, not full template bodies
* **F2:** Forced tool calls (structured output) to minimize output tokens
* **F3:** Configurable 1-call and 2-call patterns via `tool_groups`
* **F4:** Custom tools (search template wrappers) reusable across agents
* **F5:** Index association per tool
* **F6:** Backwards compatible — existing `search_templates` agents unchanged

### Non-Functional

* **NF1:** < 1 second E2E latency for single-group cases with fast models
* **NF2:** Observable — token usage, selected tool, filled params visible in response
* **NF3:** Configurable fallbacks when LLM output is invalid

---

## 4. Two Building Blocks

### 4.1 Custom Tools (CRUD API)

A custom tool wraps a search template and exposes only its parameters. It is a standalone entity — created once via CRUD API, reusable across any number of agents.

```json
POST /_plugins/_ml/tools/_create
{
  "name": "ProductSearchTool",
  "description": "Search products by category, brand, price, and color. Use for product browsing and discovery queries.",
  "type": "search_template",
  "search_template_name": "product_search_v2",
  "index": "products",
  "params": {
    "category":  { "type": "string",  "description": "Product category (e.g. shoes, jackets, bags)",  "required": true  },
    "brand":     { "type": "string",  "description": "Brand name (e.g. Nike, Adidas)",                "required": false },
    "price_max": { "type": "number",  "description": "Maximum price in USD",                          "required": false },
    "color":     { "type": "string",  "description": "Product color (e.g. red, blue, black)",         "required": false }
  }
}
```

Key fields:

* `search_template_name` — points to an existing Mustache search template
* `index` — the index this tool searches against
* `params` — the parameter schema the LLM will see. Each param has a type, description, and required flag
* `description` — what the LLM reads to decide when to use this tool

The LLM only ever sees `params` and `description`. It never sees the Mustache template body.

**CRUD operations:**

| Operation | Endpoint |
|-----------|----------|
| Create | `POST /_plugins/_ml/tools/_create` |
| Get | `GET /_plugins/_ml/tools/{tool_name}` |
| List | `GET /_plugins/_ml/tools` |
| Update | `PUT /_plugins/_ml/tools/{tool_name}` |
| Delete | `DELETE /_plugins/_ml/tools/{tool_name}` |

Custom tools are stored in a dedicated system index (`.plugins-ml-custom-tools`) and are managed independently of agents. Full CRUD design details are in [CUSTOM_TOOLS_DESIGN_DOC.md](CUSTOM_TOOLS_DESIGN_DOC.md).

### 4.2 `tool_groups` in QPT (`user_templates` mode)

Instead of introducing a new `generation_type`, we expand the existing `user_templates` mode with a new `tool_groups` field. This keeps the interface familiar and backwards compatible.

**`tool_groups` and `search_templates` are mutually exclusive.** If both are present, QPT rejects the configuration with a validation error. This prevents silent precedence confusion — users must choose one path.

* `search_templates` — the existing V1 path (template selection + DSL generation)
* `tool_groups` — the new V2 path (group selection + forced tool calls for parameter extraction)

---

## 5. How `tool_groups` Works

### Configuration

```json
{
  "type": "QueryPlanningTool",
  "parameters": {
    "model_id": "haiku-model-id",
    "generation_type": "user_templates",
    "tool_groups": [
      {
        "group_description": "Product search tools — use for queries about finding, browsing, or filtering products",
        "custom_tools": ["ProductSearchTool", "ProductAnalyticsTool"]
      },
      {
        "group_description": "Order tools — use for queries about order status, order history, or returns",
        "custom_tools": ["OrderLookupTool", "ReturnSearchTool"]
      }
    ]
  }
}
```

* `tool_groups` — array of groups. Each group has a `group_description` and a list of `custom_tools` referenced by name.
* `custom_tools` — string array of tool names. QPT loads each tool's definition (description, param schema) from the custom tools index at runtime.

### LLM Call Pattern

The number of LLM calls is determined by the number of groups:

| Groups | Call 1 | Call 2 | Total |
|--------|--------|--------|-------|
| **1 group** | *(skipped)* | Tool selection + param filling | **1 LLM call** |
| **N groups** | Group selection | Tool selection + param filling | **2 LLM calls** |

**1 group — Direct tool selection + param filling (1 LLM call):**

When there is only one group, QPT skips group selection entirely. The LLM sees all tools in that group as function definitions and picks one to call with filled parameters.

```
User: "red shoes under $50"
   |
   v
QPT: 1 group → skip group selection
   |
   v
LLM Call 1 (tool_choice=any):
  System: [default query planner prompt]
  Tools:  [ProductSearchTool(category, brand, price_max, color),
           ProductAnalyticsTool(metric, time_range, category)]
  User:   "red shoes under $50"
  Output: tool_call → ProductSearchTool(category="shoes", color="red", price_max=50)
   |
   v
QPT: render template + execute search
   |
   v
Results
```

**N groups — Group selection, then tool selection + param filling (2 LLM calls):**

When there are multiple groups, QPT first asks the LLM to select the appropriate group based on group descriptions, then presents that group's tools for selection and param filling.

```
User: "red shoes under $50"
   |
   v
QPT: 2 groups → group selection needed
   |
   v
LLM Call 1 (group selection):
  System: [default group selection prompt]
  Groups: ["Product search tools — ...", "Order tools — ..."]
  User:   "red shoes under $50"
  Output: "Product search tools"
   |
   v
LLM Call 2 (tool_choice=any):
  System: [default query planner prompt]
  Tools:  [ProductSearchTool(...), ProductAnalyticsTool(...)]
  User:   "red shoes under $50"
  Output: tool_call → ProductSearchTool(category="shoes", color="red", price_max=50)
   |
   v
QPT: render template + execute search
   |
   v
Results
```

### Default Prompts

Both the group selection prompt and the query planner prompt have sensible defaults that work out of the box. Users can override them during agent registration:

```json
{
  "type": "QueryPlanningTool",
  "parameters": {
    "model_id": "haiku-model-id",
    "generation_type": "user_templates",
    "group_selection_prompt": "You are a routing assistant. Given the user's question, select the most relevant data domain.",
    "query_planner_prompt": "You are a search assistant. Extract search parameters from the user's question and call the appropriate tool.",
    "tool_groups": [...]
  }
}
```

| Parameter | Used in | Default behavior |
|-----------|---------|-----------------|
| `group_selection_prompt` | Call 1 (group selection) | Select the group whose description best matches the user's query |
| `query_planner_prompt` | Call 2 (tool selection + param filling) | Select the best tool and fill its parameters from the user's query |

If not provided, QPT uses built-in defaults. The custom tool's own `description` and `params` are always included as function definitions — no additional per-tool prompt configuration is needed in QPT.

---

## 6. End-to-End Setup

### Step 1: Create a search template (standard OpenSearch)

```json
POST _scripts/product_search_v2
{
  "script": {
    "lang": "mustache",
    "source": {
      "query": {
        "bool": {
          "must": [
            { "match": { "category": "{{category}}" } }
          ],
          "filter": [
            { "{{#color}}": { "term": { "color": "{{color}}" } } },
            { "{{#price_max}}": { "range": { "price": { "lte": "{{price_max}}" } } } }
          ]
        }
      }
    }
  }
}
```

### Step 2: Create a custom tool wrapping the template

```json
POST /_plugins/_ml/tools/_create
{
  "name": "ProductSearchTool",
  "description": "Search products by category, brand, price, and color. Use for product browsing and discovery queries.",
  "type": "search_template",
  "search_template_name": "product_search_v2",
  "index": "products",
  "params": {
    "category":  { "type": "string",  "description": "Product category (e.g. shoes, jackets, bags)",  "required": true  },
    "brand":     { "type": "string",  "description": "Brand name (e.g. Nike, Adidas)",                "required": false },
    "price_max": { "type": "number",  "description": "Maximum price in USD",                          "required": false },
    "color":     { "type": "string",  "description": "Product color (e.g. red, blue, black)",         "required": false }
  }
}
```

### Step 3: Register a flow agent with QPT

```json
POST /_plugins/_ml/agents/_register
{
  "name": "Product Search Agent",
  "type": "flow",
  "tools": [
    {
      "type": "QueryPlanningTool",
      "parameters": {
        "model_id": "haiku-model-id",
        "generation_type": "user_templates",
        "tool_groups": [
          {
            "group_description": "Product search tools",
            "custom_tools": ["ProductSearchTool"]
          }
        ]
      }
    }
  ]
}
```

### Step 4: Create a search pipeline

```json
PUT /_search/pipeline/product-search-pipeline
{
  "response_processors": [
    {
      "agentic_query_translator": {
        "agent_id": "<agent_id_from_step_3>"
      }
    }
  ]
}
```

### What happens at query time

```json
GET /products/_search?search_pipeline=product-search-pipeline
{ "query": { "agentic": { "query_text": "red shoes under $50" } } }
```

```
User ("red shoes under $50")
   |
   v
Search Pipeline
   |
   v
QPT (1 group → skip group selection)
  - load ProductSearchTool from custom tools index
  - build function definition from param schema
  - no mappings/docs fetch needed
   |
   v
Single LLM call (tool_choice=any)
  → ProductSearchTool(category="shoes", color="red", price_max=50)
   |
   v
QPT: render template "product_search_v2" with params → execute search
   |
   v
Search Pipeline → Results
```

**Total: 1 LLM call, ~30 output tokens, < 1 sec latency.**

---

## 7. Scaling Up: Multiple Tools and Groups

### 7.1 Multiple Tools, Single Group (1 LLM call)

For 2-5 tools in the same domain, put them all in one group. QPT skips group selection and presents all tools to the LLM in a single call.

```json
{
  "type": "QueryPlanningTool",
  "parameters": {
    "model_id": "haiku-model-id",
    "generation_type": "user_templates",
    "tool_groups": [
      {
        "group_description": "E-commerce search tools",
        "custom_tools": ["ProductSearchTool", "SalesAnalyticsTool", "ReviewSearchTool"]
      }
    ]
  }
}
```

> **Call 1 — Select tool + fill params:** LLM picks ProductSearchTool and fills `category="shoes", color="red", price_max=50`

### 7.2 Multiple Groups (2 LLM calls)

For multiple data domains or many tools, split them into groups. QPT adds a group selection call before tool selection.

```json
{
  "type": "QueryPlanningTool",
  "parameters": {
    "model_id": "haiku-model-id",
    "generation_type": "user_templates",
    "tool_groups": [
      {
        "group_description": "Product search tools — use for queries about finding, browsing, or filtering products by attributes like category, brand, price, or color",
        "custom_tools": ["ProductSearchTool", "ProductAnalyticsTool"]
      },
      {
        "group_description": "Order management tools — use for queries about order status, order history, lookups, or returns",
        "custom_tools": ["OrderLookupTool", "OrderAnalyticsTool", "ReturnSearchTool"]
      },
      {
        "group_description": "Inventory tools — use for queries about stock levels, warehouse availability, or restocking",
        "custom_tools": ["InventoryCheckTool", "WarehouseSearchTool"]
      }
    ]
  }
}
```

> **Call 1 — Pick group:** LLM selects "Product search tools" from 3 groups
> **Call 2 — Pick tool + fill params:** LLM picks ProductSearchTool and fills `category="shoes", color="red", price_max=50`

### When to use what

| Setup | Groups | LLM Calls | Latency Target |
|-------|--------|-----------|---------------|
| 1-5 tools, single domain | 1 group | 1 | < 1s |
| Many tools, multiple domains | N groups | 2 | < 2s |

---

## 8. Observability and Fallbacks

### Response Metadata

Every V2 response includes full transparency in the `ext` block:

```json
{
  "ext": {
    "dsl_query": "{...rendered template...}",
    "tool_used": "ProductSearchTool",
    "template_id": "product_search_v2",
    "index_selected": "products",
    "group_selected": "Product search tools",
    "params_filled": {
      "category": "shoes",
      "color": "red",
      "price_max": 50
    },
    "token_usage": { "input_tokens": 245, "output_tokens": 32 },
    "latency_breakdown_ms": { "llm_call_1": 380, "llm_call_2": null, "template_render": 2, "search_execution": 45, "total": 427 }
  }
}
```

### Fallback Strategy

```
LLM Response
     |
     v
Valid tool call?
     |-- YES --> Render Template --> Execute Search
     |
     +-- NO --> Retry configured?
                    |-- YES --> Retry LLM call (up to max_retries)
                    |               |
                    |               +-- Valid? --> Render Template --> Execute Search
                    |               +-- Still invalid? --> continue below
                    |
                    +-- NO/retries exhausted --> Fallback configured?
                                                    |-- YES --> Execute fallback_query (user-defined per tool)
                                                    +-- NO --> Return error
```

When the LLM fails to produce a valid tool call and no `fallback_query` is configured on the custom tool, QPT returns an error rather than executing a `match_all`. Users who want a `match_all` fallback can configure it explicitly as a `fallback_query` on the custom tool.

---

## 9. Validation Rules

| Rule | Where Enforced | Error |
|------|---------------|-------|
| `search_templates` and `tool_groups` are mutually exclusive | QPT config validation | `Cannot specify both 'search_templates' and 'tool_groups'. Use one or the other.` |
| `tool_groups` must have at least 1 group | QPT config validation | `'tool_groups' must contain at least one group` |
| Each group must have at least 1 tool | QPT config validation | `Each tool group must contain at least one custom tool` |
| `group_description` required per group | QPT config validation | `'group_description' is required for each tool group` |
| Referenced custom tools must exist | QPT runtime (tool loading) | `Custom tool '{name}' not found` |
| Custom tool CRUD validation | See [CUSTOM_TOOLS_DESIGN_DOC.md](CUSTOM_TOOLS_DESIGN_DOC.md) | Various (name uniqueness, template existence, etc.) |

---

## 10. Comparison: Approach 1 vs. Approach 2

| Dimension | Approach 1 (full design doc) | Approach 2 (this doc) |
|-----------|------------------------------|----------------------|
| **Custom tools CRUD** | Yes | Yes (same) |
| **QPT generation_type** | New: `"custom_tools"` | Existing: `"user_templates"` (expanded) |
| **Tool organization** | Flat list + `routing` field | `tool_groups` array |
| **Routing config** | 4 shapes: omitted, `"tool"`, `{by:"group"}`, `[{...},{...}]` | Implicit: 1 group = no routing, N groups = routing |
| **Max LLM calls** | 3 (group → tool → params) | 2 (group → tool+params) |
| **Per-tool prompts in QPT** | `per_tool_system_prompt` inline | None — lives in the custom tool's `description` |
| **Per-group prompts** | `system_prompt_map` keyed by group name | `group_description` per group |
| **Prompt customization** | Per-step prompt fields | Two overridable defaults: `group_selection_prompt`, `query_planner_prompt` |
| **New concepts to learn** | `generation_type: "custom_tools"`, `routing`, `per_tool_system_prompt`, `system_prompt_map` | `tool_groups` (one new field) |
| **Setup steps** | 4 (template → custom tool → agent → pipeline) | 4 (same — custom tools CRUD is retained) |
| **Migration from V1** | Switch `generation_type`, rewrite QPT config | Replace `search_templates` with `tool_groups` in existing config |

### Why Approach 2

The core V2 execution model is identical — forced tool calls, param schemas, ~30 output tokens, sub-second latency. The difference is purely in the configuration surface:

1. **No new `generation_type` to learn.** Users stay in `user_templates` mode. The presence of `tool_groups` activates the V2 code path internally.
2. **No `routing` field with 4 shapes.** The call pattern is implicit from the number of groups. One less concept, zero ambiguity.
3. **No prompt fields scattered across QPT config.** Two overridable defaults (`group_selection_prompt`, `query_planner_prompt`) plus the tool's own `description`. One source of truth per prompt.
4. **2-call maximum.** Covers the vast majority of use cases. Simpler to reason about, explain, and debug.

---

## 11. Open Questions

* **Index from tool config vs. search request URL?** — URL-based is more flexible; tool-based is more encapsulated. Could support both — tool index as default, URL as override.
* **Multi-index support?** — Should the custom tool's `index` field accept patterns (e.g., `logs-*`) or arrays? Needed for observability use cases with per-month indices.
* **Parallel tool execution?** — Some queries map to multiple templates ("compare prices across electronics and clothing"). Most function-calling LLMs support `parallel_tool_calls`.
* **Use `_search/template` API directly?** — More efficient than render + search separately. Standard OpenSearch pattern.
* **Function calling fallback?** — If the model doesn't support function calling, fall back to text-based JSON extraction with `extract_json` parser. This is an Agent framework gap, not specific to this design.

---

## 12. Risks

* **Function calling not supported by all providers** — Text-based JSON fallback with `extract_json` parser. Currently supported: Bedrock, OpenAI, Gemini, DeepSeek.
* **Param descriptions insufficient for complex templates** — Add optional `examples` and `constraints` fields to param definitions in future.
* **Customers expect V2 to improve general NLQ-to-DSL** — Clear docs: V2 targets "known templates." V1 `llmGenerated` mode remains for general use.
* **Group descriptions too vague → misrouting** — Quality of group selection depends entirely on `group_description`. Docs should include best practices and examples for writing effective descriptions.
