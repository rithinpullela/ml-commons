# Enterprise Agentic Search V2 — Design Document

## 1. The Problem

> *[Unchanged — keep existing Section 1 as-is]*

---

## 2. Why V1 Falls Short for Enterprises

> *[Unchanged — keep existing Section 2 as-is]*

---

## 3. Requirements

> *[Unchanged — keep existing Section 3 as-is]*

---

## 4. Solutions

### 4.1 The Idea: Custom Tools + Forced Tool Calls

Two building blocks make V2 work: **Custom Tools** and a **new QPT mode that uses forced tool calls**.

**Custom Tools** wrap search templates and expose only their parameters. They are standalone entities — created once via CRUD API, reusable across any number of agents.

```json
POST /_plugins/_ml/tools/_create
{
  "name": "ProductSearchTool",
  "description": "Search products by category, brand, price, and color.",
  "type": "search_template",
  "search_template_name": "product_search_v2",
  "index": "products",
  "params": {
    "category":  { "type": "string",  "description": "Product category",  "required": true  },
    "price_max": { "type": "number",  "description": "Maximum price in USD", "required": false },
    "color":     { "type": "string",  "description": "Product color",       "required": false }
  }
}
```

The LLM only ever sees `params` and `description`. It never sees the Mustache template body.

**Forced tool calls** (`tool_choice: "any"`) present custom tools as function definitions to the LLM. Instead of generating free-form DSL, the LLM returns a structured tool call with filled parameters (~30 output tokens instead of ~300). QPT renders the template server-side and executes the search.

```
V1: LLM generates DSL       → ~300 output tokens, 2-5s
V2: LLM fills tool call     → ~30 output tokens, <1s
```

The following two approaches differ only in **how custom tools are organized and presented to the LLM within QPT**. The custom tools CRUD API and the forced-tool-call execution model are shared by both.

---

### 4.2 Approach 1: `generation_type: "custom_tools"` with Routing

> *Approach 1 is included for completeness. See Approach 2 (Section 4.3) for the proposed design.*

#### QPT Configuration

QPT gets a new mode where instead of generating DSL, it presents custom tools to the LLM as function definitions and forces a tool call.

> *[Include existing Section 4.2 content — QPT mode: `generation_type: "custom_tools"` explanation]*

#### System Flow

> *[Include existing Section 5 content — 1 call, 2 call, 3 call flow diagrams]*

#### End-to-End Setup

> *[Include existing Section 6 content — Steps 1-4]*

#### Scaling Up: Multiple Tools

> *[Include existing Section 7 content — 7.1 through 7.4, including the routing field variants and "when to use what" table]*

---

### 4.3 Approach 2: `tool_groups` in Existing `user_templates` Mode

Based on review feedback, Approach 2 simplifies the QPT interface. Instead of introducing a new `generation_type` and a polymorphic `routing` field, we expand the existing `user_templates` mode with a single new field: `tool_groups`.

#### What changes from Approach 1

- **No new `generation_type`** — stays within `user_templates`
- **No `routing` field** — the number of groups implicitly determines the call pattern
- **No per-tool prompts in QPT config** — the custom tool's own `description` is the single source of truth
- **Max 2 LLM calls** (not 3) — group selection + tool selection/param filling
- **`tool_groups` and `search_templates` are mutually exclusive** — if both are present, QPT rejects with a validation error

#### Configuration

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
        "group_description": "Order tools — use for queries about order status, history, or returns",
        "custom_tools": ["OrderLookupTool", "ReturnSearchTool"]
      }
    ]
  }
}
```

- `tool_groups` — array of groups. Each group has a `group_description` and a list of `custom_tools` referenced **by name** (QPT loads definitions from the custom tools index at runtime)
- `group_description` — used by the LLM in the group selection call to pick the right domain

#### LLM Call Pattern

The number of LLM calls is determined by the number of groups:

| Groups | Call 1 | Call 2 | Total |
|--------|--------|--------|-------|
| **1 group** | *(skipped)* | Tool selection + param filling | **1 LLM call** |
| **N groups** | Group selection | Tool selection + param filling | **2 LLM calls** |

**1 group — Direct tool selection + param filling (1 LLM call):**

```
User: "red shoes under $50"
   |
   v
QPT: 1 group → skip group selection
   |
   v
LLM Call (tool_choice=any):
  System: [default query planner prompt]
  Tools:  [ProductSearchTool(category, brand, price_max, color),
           ProductAnalyticsTool(metric, time_range, category)]
  User:   "red shoes under $50"
  Output: tool_call → ProductSearchTool(category="shoes", color="red", price_max=50)
   |
   v
QPT: render template + execute search → Results
```

**N groups — Group selection, then tool selection + param filling (2 LLM calls):**

```
User: "red shoes under $50"
   |
   v
QPT: 2+ groups → group selection needed
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
QPT: render template + execute search → Results
```

#### Default Prompts (Overridable)

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

If not provided, QPT uses built-in defaults. The custom tool's own `description` and `params` are always included as function definitions — no additional per-tool prompt configuration needed.

#### End-to-End Setup

**Step 1: Create a search template** (standard OpenSearch)

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

**Step 2: Create a custom tool** wrapping the template

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

**Step 3: Register a flow agent** with QPT

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

**Step 4: Create a search pipeline**

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

**What happens at query time:**

```json
GET /products/_search?search_pipeline=product-search-pipeline
{ "query": { "agentic": { "query_text": "red shoes under $50" } } }
```

```
User ("red shoes under $50")
   → Search Pipeline
   → QPT (1 group → skip group selection)
     - load ProductSearchTool from custom tools index
     - build function definition from param schema
   → Single LLM call (tool_choice=any)
     → ProductSearchTool(category="shoes", color="red", price_max=50)
   → Render template "product_search_v2" with params
   → Execute search → Results
```

**Total: 1 LLM call, ~30 output tokens, < 1 sec latency.**

#### Scaling Up

**Multiple tools, single group (1 LLM call):**

```json
"tool_groups": [
  {
    "group_description": "E-commerce search tools",
    "custom_tools": ["ProductSearchTool", "SalesAnalyticsTool", "ReviewSearchTool"]
  }
]
```

**Multiple groups (2 LLM calls):**

```json
"tool_groups": [
  {
    "group_description": "Product search tools — finding, browsing, or filtering products",
    "custom_tools": ["ProductSearchTool", "ProductAnalyticsTool"]
  },
  {
    "group_description": "Order management tools — order status, history, or returns",
    "custom_tools": ["OrderLookupTool", "OrderAnalyticsTool", "ReturnSearchTool"]
  },
  {
    "group_description": "Inventory tools — stock levels, warehouse availability",
    "custom_tools": ["InventoryCheckTool", "WarehouseSearchTool"]
  }
]
```

| Setup | Groups | LLM Calls | Latency Target |
|-------|--------|-----------|---------------|
| 1-5 tools, single domain | 1 group | 1 | < 1s |
| Many tools, multiple domains | N groups | 2 | < 2s |

#### Approach 1 vs. Approach 2

| Dimension | Approach 1 | Approach 2 |
|-----------|-----------|-----------|
| **QPT generation_type** | New: `"custom_tools"` | Existing: `"user_templates"` (expanded) |
| **Tool organization** | Flat list + `routing` field | `tool_groups` array |
| **Routing config** | 4 shapes: omitted / `"tool"` / `{by:"group"}` / `[{...},{...}]` | Implicit: 1 group = no routing, N groups = routing |
| **Max LLM calls** | 3 | 2 |
| **Per-tool prompts in QPT** | `per_tool_system_prompt` inline | None — lives in tool's `description` |
| **Per-group prompts** | `system_prompt_map` keyed by group name | `group_description` per group |
| **Prompt customization** | Per-step prompt fields | Two overridable defaults |
| **New concepts to learn** | `generation_type`, `routing`, `per_tool_system_prompt`, `system_prompt_map` | `tool_groups` (one new field) |

---

## 5. Observability and Fallbacks

> *[Unchanged from existing Section 8, with one update: default fallback is error, not match_all]*

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
                    |               +-- Valid? --> Render + Execute
                    |               +-- Still invalid? --> continue below
                    |
                    +-- NO/retries exhausted --> Fallback configured?
                                                    |-- YES --> Execute fallback_query
                                                    +-- NO --> Return error
```

Default is **error**, not `match_all`. Users who want a `match_all` fallback can configure it explicitly as a `fallback_query` on the custom tool.

---

## 6. Open Questions

> *[Unchanged — keep existing Section 9 as-is]*

---

## 7. Risks

> *[Unchanged — keep existing Section 10 as-is]*
