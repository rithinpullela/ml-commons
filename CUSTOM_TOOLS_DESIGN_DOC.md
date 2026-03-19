# Custom Tools for ML Agents — Design Document

## 1. Problem Statement

OpenSearch ML agents today have no way to leverage existing search templates. When an agent needs to execute a search, the LLM must generate full OpenSearch DSL from scratch — even when the customer already has a well-tested, parameterized search template that does exactly what's needed. This creates several problems:

- **Wasted tokens and latency:** The LLM receives the full index mapping, sample documents, and template references in its prompt (~4,000-5,000 tokens), then generates a complete DSL query as output (~300 tokens, 2-5 seconds). Most of this work is redundant when a template already defines the query structure.
- **Unreliable output:** The LLM can produce syntactically invalid queries, deviate from the expected query shape, or hallucinate field names that don't exist in the index. There is no guarantee the generated DSL matches the intended search pattern.
- **Expensive models required:** DSL generation is a complex task that demands large, capable models. Smaller, cheaper models cannot reliably produce correct OpenSearch queries.
- **No reuse of existing work:** Customers have invested in building and validating search templates for their use cases — product searches, log filters, geo queries, aggregation pipelines. There is no mechanism to expose these templates as tools that agents can call.

Customers are asking for the ability to take their existing search templates and make them directly usable by agents, without the overhead and risk of full DSL generation.

## 2. Motivation

**The core insight** is that if the search pattern already exists as a template, the LLM's job should reduce to **filling in parameter values** — not generating DSL. Instead of asking the LLM "write me a bool query with a match on title, a term filter on category, and a range on price," we ask it "what is the category, and what is the max price?" The template handles the rest.

This shift has a direct impact on latency. Output tokens are the primary bottleneck in LLM response time (`time_to_last_token = TTFT + output_tokens / tokens_per_second`). Generating full DSL produces ~300 output tokens. Filling parameters via a structured tool call produces ~30 tokens — a 10x reduction that translates to sub-second latency instead of 2-5 seconds. Token cost drops proportionally (~250-500 tokens per request vs ~4,000-5,000), and smaller, cheaper models become viable since parameter extraction is a far simpler task than DSL generation.

Custom tools also open the door for the **Query Planner Tool** to select and invoke tools as sub-plans, and work natively with **MCP servers** for external agent orchestration.

### Example — How This Helps an Agent

Without custom tools, an agent handling *"Find red shoes under $50"* must receive the full index mapping, a sample document, and possibly a template as reference in its prompt. The LLM generates a complete bool query with match, term, and range clauses (~300 output tokens, 2-5s). With a custom tool, the agent sees only: `ProductSearchTool(category: string, color: string, price_max: number)`. The LLM returns a single tool call — `ProductSearch(category="shoes", color="red", price_max=50)` — in ~30 tokens, under 1 second. The template renders the DSL server-side, guaranteeing the correct query structure every time.

## 3. Requirements

**Functional:**
- Users can wrap existing search templates as tools that ML agents can invoke directly
- The system should minimize setup effort — ideally, pointing at a search template is enough to create a usable tool
- Tools must be manageable (create, read, update, delete) and shareable across agents
- Custom tools should appear alongside built-in tools so agents have a unified view of available capabilities
- At runtime, the tool handles template rendering, type conversion, and search execution — the agent only provides parameter values

**Non-Functional:**
- Tool creation should not require an LLM in the default path — customers without deployed models should still benefit
- Tool execution overhead (beyond the search itself) should be negligible

## 4. User Flow

The end-to-end flow involves four steps: creating a search template (standard OpenSearch), registering a custom tool (which auto-extracts parameters and stores the tool definition in a dedicated system index), attaching the tool to an agent, and the agent invoking the tool at runtime. The custom tool definition — including its auto-generated parameter schema — is persisted in the `.plugins-ml-custom-tools` system index, making it reusable across multiple agents.

```mermaid
sequenceDiagram
    participant User
    participant OpenSearch
    participant Index as .plugins-ml-custom-tools
    participant Agent
    participant LLM

    User->>OpenSearch: 1. POST _scripts/product_search (create search template)
    User->>OpenSearch: 2. POST /_plugins/_ml/tools/_create (register custom tool)
    Note right of OpenSearch: Auto-extracts params from template AST
    OpenSearch->>Index: Store tool definition + generated params
    User->>OpenSearch: 3. POST /_plugins/_ml/agents/_register (attach tool to agent)
    User->>Agent: 4. "Find red shoes under $50"
    Agent->>LLM: Tool definitions (params only, no DSL)
    LLM->>Agent: tool_call: ProductSearch(category="shoes", color="red", price_max=50)
    Agent->>OpenSearch: Render template + execute search
    OpenSearch->>Agent: Search results
    Agent->>User: "Here are 12 red shoes under $50..."
```

---

## 5. Approaches for Parameter Definition

For an LLM to use a tool effectively via function calling, the tool must expose a well-defined parameter schema — each parameter needs a name, type, description, and whether it's required. This schema becomes the `input_schema` (JSON Schema) that the agent presents to the LLM as a function definition. Without it, the LLM has no way to know what inputs the tool expects or how to fill them. The question is: who defines these parameters? Four approaches were considered:

### Approach 1: Manual — User Provides Params

The user explicitly defines every parameter in the create request.

```json
POST /_plugins/_ml/tools/_create
{
  "name": "ProductSearchTool",
  "type": "search_template",
  "search_template_name": "product_search",
  "params": {
    "category":  { "type": "string",  "description": "Product category",  "required": true  },
    "price_max": { "type": "number",  "description": "Maximum price",     "required": false }
  }
}
```

**Pros:**
- Full control over every parameter definition
- No surprises — what you write is what gets stored

**Cons:**
- Tedious for customers with 20+ templates
- Error-prone — params must match template variables exactly, typos cause silent failures
- Duplicates information already present in the template

### Approach 2: AST Parsing — Auto-Extract from Template

Compile the Mustache template using the same `mustache.java` library OpenSearch uses, walk the AST, and programmatically extract:
- **Variable names** from `ValueCode`, `ToJsonCode`, `JoinerCode` nodes
- **Required/optional** from section nesting (self-guarding sections, inverted defaults)
- **Types** from DSL context (quoted = string, unquoted = number, toJson = array, section-only = boolean)
- **Default values** from inverted sections (`{{^size}}10{{/size}}` -> default "10")
- **Descriptions** from surrounding DSL context (`"match":{"title":"{{query_text}}"}` -> "Value for the 'title' field (match)")

```json
POST /_plugins/_ml/tools/_create
{
  "name": "ProductSearchTool",
  "type": "search_template",
  "search_template_name": "product_search"
}
// No params, no model_id — system auto-extracts everything
```

**Pros:**
- Zero effort from the user — just provide a template name
- Deterministic and fast (~10ms, no LLM dependency)
- No additional cost — no model needed

**Cons:**
- Descriptions are functional but generic — for example, given the template `"match":{"title":"{{query_text}}"}`, the analyzer generates `"Value for the 'title' field (match)"`. An LLM would produce something more natural like `"Search text to match against product titles using full-text search"`. Similarly, `"size":{{result_size}}` yields `"Value for 'result_size'"` instead of `"Maximum number of search results to return (default 10)"`
- Type inference is heuristic-based — works well for common patterns but may miss edge cases

### Approach 3: AST Parsing + LLM — Use a Model to Enhance Descriptions

Perform the same AST tree walking as Approach 2 to extract variable names, required/optional, and defaults deterministically. Then pass the template source and the extracted variable list to an LLM, asking it to provide richer type inference and human-quality descriptions for each parameter. The LLM does not determine requiredness or extract variables — that's already handled by the AST.

```json
POST /_plugins/_ml/tools/_create
{
  "name": "ProductSearchTool",
  "type": "search_template",
  "search_template_name": "product_search",
  "model_id": "haiku-model-id"
}
```

**Pros:**
- Rich, human-quality descriptions (e.g., "Maximum price in USD for filtering products" instead of "Value for 'price_max'")
- Better type refinement — LLM can distinguish `float` vs `integer` from semantic context

**Cons:**
- Adds latency (1-3s for the LLM call) to tool creation
- Requires a deployed model — not always available
- Non-deterministic — same template may produce slightly different descriptions across calls
- Hallucination risk on types (mitigated by AST handling required/optional)

### Approach 4 (Recommended): Hybrid — All Three Paths Available

Expose all three approaches as tiers within a single API. The user chooses their level of involvement:

- **Tier 1 (default):** Provide nothing extra — the system auto-extracts everything from the template AST (Approach 2)
- **Tier 2:** Provide a `model_id` — the system auto-extracts via AST, then enhances descriptions with the LLM (Approach 3)
- **Tier 3:** Provide `params` manually — the system stores them as-is, no extraction (Approach 1)

This way, customers who want zero friction get it (Tier 1), customers who want polished descriptions can opt in (Tier 2), and customers who want full control can provide their own params (Tier 3). After creation via any tier, users can always correct or refine params via the `PUT` update API.

```mermaid
flowchart TD
    A[POST /_plugins/_ml/tools/_create] --> B{What's provided?}
    B -->|Neither params nor model_id| C[Tier 1: AST-only extraction]
    B -->|model_id only| D[Tier 2: AST + LLM enhancement]
    B -->|params only| E[Tier 3: Manual — store as-is]
    B -->|Both params and model_id| F[Error: mutually exclusive]

    C --> G[Compile template with mustache.java]
    D --> G
    G --> H[Walk AST: extract names, types, required, defaults]
    H --> I{model_id provided?}
    I -->|No| J[Generate heuristic descriptions]
    I -->|Yes| K[Call LLM for better types + descriptions]
    K --> L[Merge: LLM types/descriptions + AST required/defaults]
    J --> M[Store tool in .plugins-ml-custom-tools index]
    L --> M
    E --> M
```

**Key principle:** The LLM never determines required/optional — that's structural, not semantic. The AST tells us definitively whether a variable is inside a conditional section. The LLM's role is limited to enriching descriptions and refining types — things it's good at and that are easily reviewable.

---

## 6. Low-Level Implementation

### 6.1 System Index: `.plugins-ml-custom-tools`

Custom tools are stored in a dedicated system index, following the same pattern as connectors, models, and agents.

**Index mapping:**

| Field | Type | Notes |
|---|---|---|
| `name` | text + keyword | Unique, validated at create time |
| `description` | text + keyword | Max 512 chars |
| `type` | keyword | Currently restricted to `"search_template"` |
| `search_template_name` | keyword | Reference to stored script |
| `params` | flat_object | Parameter definitions (name -> {type, description, required, default}) |
| `model_id` | keyword | Optional, for Tier 2 LLM enhancement |
| `tenant_id` | keyword | Multi-tenancy support |
| `create_time` | date | Auto-set |
| `last_update_time` | date | Auto-set on create/update |

### 6.2 CRUD API

```mermaid
flowchart LR
    subgraph REST Layer
        R1[POST /_plugins/_ml/tools/_create]
        R2[GET /_plugins/_ml/tools/name]
        R3[GET /_plugins/_ml/tools]
        R4[PUT /_plugins/_ml/tools/id]
        R5[DELETE /_plugins/_ml/tools/id]
    end

    subgraph Transport Layer
        T1[CreateCustomToolTransportAction]
        T2[GetToolTransportAction]
        T3[ListToolsTransportAction]
        T4[UpdateCustomToolTransportAction]
        T5[DeleteCustomToolTransportAction]
    end

    subgraph Storage
        S1[(.plugins-ml-custom-tools)]
        S2[ToolFactory - built-in tools]
    end

    R1 --> T1 --> S1
    R2 --> T2 --> S2
    R2 --> T2 --> S1
    R3 --> T3 --> S2
    R3 --> T3 --> S1
    R4 --> T4 --> S1
    R5 --> T5 --> S1
```

**GET/LIST merges built-in and custom tools** into a single response. Built-in tools take precedence on name conflicts.

### 6.3 SearchTemplateTool Execution

The tool renders Mustache templates via `ScriptService` (OpenSearch core), avoiding any direct dependency on the `lang-mustache` module.

```mermaid
flowchart TD
    A[Agent calls SearchTemplateTool.run] --> B[Validate required params]
    B --> C[Convert types: string inputs -> typed values]
    C --> D[ScriptService.compile stored template]
    D --> E[TemplateScript.execute with params]
    E --> F{execution_mode?}
    F -->|render_only| G[Return rendered DSL query]
    F -->|execute| H[Parse JSON -> SearchSourceBuilder]
    F -->|both| I[Return query + results]
    H --> J[client.search]
    J --> K[Format hits as JSON]
```

**Type conversion** at runtime: LLMs pass all values as strings. The tool converts based on param definitions (`"5"` -> `5` for integer fields) to produce valid JSON.

**Execution modes:**
- `execute` (default) — render + search + return results
- `render_only` — render + return the DSL query (debugging/transparency)
- `both` — render + search + return both

### 6.4 MustacheTemplateAnalyzer (AST Walker)

The analyzer compiles the template with `mustache.java` (same library OpenSearch uses: `com.github.spullara.mustache.java:compiler:0.9.14`) and recursively walks the Code tree.

**How it handles each AST node type:**

| Node Type | Example | Extraction |
|---|---|---|
| `ValueCode` | `{{query_text}}` | Variable name, type from surrounding text context |
| `IterableCode` | `{{#genre}}...{{/genre}}` | Section controller (boolean if no inner value usage) |
| `NotIterableCode` | `{{^size}}10{{/size}}` | Inverted default → `required: false`, `default: "10"` |
| `ToJsonCode` | `{{#toJson}}tags{{/toJson}}` | Array type |
| `JoinerCode` | `{{#join}}emails{{/join}}` | Array type |
| `UrlEncoderCode` | `{{#url}}...{{/url}}` | Transparent — recurse into children |
| `WriteCode` | Literal text | Captured as `precedingText` for type/description inference |

**Type inference from preceding literal text:**
- Ends with `"` → `string` (quoted value in DSL)
- Ends with `:` or `,` → `number` (bare value position)
- Inside toJson/join helper → `array`
- Section controller with no value usage → `boolean`

**Required/optional logic:**
- Has inverted default (`{{^var}}default{{/var}}`) → **optional**
- Section controller only (no `{{var}}` usage) → **optional** (boolean flag)
- Appears only inside own section (`{{#var}}...{{var}}...{{/var}}`) → **optional** (self-guarding)
- Appears at root scope with none of the above → **required**

### 6.5 Validation Rules

| Rule | When | Error |
|---|---|---|
| Name required | Create | `Custom tool name is required` |
| Name unique | Create | `A custom tool with name '...' already exists` |
| Name no `_` prefix | Create | `Custom tool name cannot start with '_'` |
| Type = `search_template` | Create | `Custom tool type must be 'search_template'` |
| Template exists | Create | `Search template '...' not found` |
| params XOR model_id | Create | `Cannot specify both 'params' and 'model_id'` |
| Required params present | Runtime | `Missing required parameters` |

### 6.6 Files

**New files (17):** Index mapping, data model (`MLCustomToolInput`), transport actions/requests/responses for Create/Update/Delete, `SearchTemplateTool` + Factory, `MustacheTemplateAnalyzer`, REST handlers, `CustomToolsHelper`.

**Modified files (6):** `CommonValue` (constants), `MLIndex` (enum entry), `MLIndicesHandler` (init index), `GetToolTransportAction` (fallback to custom tools), `ListToolsTransportAction` (merge custom tools), `MachineLearningPlugin` (register everything).

---

## 7. Appendix

### 7.1 POC Branch

**Branch:** [`feature/custom-tools`](https://github.com/rithin-pullela-aws/ml-commons/tree/feature/custom-tools)

**How to test:**

```bash
# 1. Build and start OpenSearch with ml-commons
./gradlew run

# 2. Create a search template
curl -X POST 'http://localhost:9200/_scripts/product_search' \
  -H 'Content-Type: application/json' -d '{
  "script": {
    "lang": "mustache",
    "source": "{\"query\":{\"bool\":{\"must\":[{\"match\":{\"title\":\"{{query_text}}\"}}]{{#category}},\"filter\":[{\"term\":{\"category\":\"{{category}}\"}}]{{/category}}}},\"from\":{{#from}}{{from}}{{/from}}{{^from}}0{{/from}},\"size\":{{#size}}{{size}}{{/size}}{{^size}}20{{/size}}}"
  }
}'

# 3. Create a custom tool (Tier 1 — auto-extract params)
curl -X POST 'http://localhost:9200/_plugins/_ml/tools/_create' \
  -H 'Content-Type: application/json' -d '{
  "name": "ProductSearchTool",
  "description": "Search products with optional category filter and pagination",
  "type": "search_template",
  "search_template_name": "product_search"
}'
# Response includes auto-generated params:
# {
#   "tool_id": "abc123",
#   "params": {
#     "query_text": { "type": "string", "description": "Value for the 'title' field (match)", "required": true },
#     "category":   { "type": "string", "description": "Value for the 'category' field (term)", "required": false },
#     "from":       { "type": "string", "description": "Value for 'from'", "required": false, "default": "0" },
#     "size":       { "type": "string", "description": "Value for 'size'", "required": false, "default": "20" }
#   }
# }

# 4. Verify: Get the tool
curl 'http://localhost:9200/_plugins/_ml/tools/ProductSearchTool' | python3 -m json.tool

# 5. List all tools (built-in + custom merged)
curl 'http://localhost:9200/_plugins/_ml/tools' | python3 -m json.tool

# 6. Register an agent with the custom tool
curl -X POST 'http://localhost:9200/_plugins/_ml/agents/_register' \
  -H 'Content-Type: application/json' -d '{
  "name": "ProductAgent",
  "type": "conversational",
  "llm": { "model_id": "<your-model-id>" },
  "tools": [{
    "type": "SearchTemplateTool",
    "parameters": {
      "search_template_name": "product_search"
    }
  }]
}'
```

### 7.2 POC: Custom Tools via MCP Server

Custom tools can be exposed as MCP (Model Context Protocol) tools, allowing external agents (Claude, GPT, etc.) to discover and invoke them directly.

```mermaid
sequenceDiagram
    participant ExternalAgent as External Agent (Claude/GPT)
    participant MCP as MCP Server
    participant OpenSearch

    ExternalAgent->>MCP: tools/list
    MCP->>OpenSearch: GET /_plugins/_ml/tools
    OpenSearch->>MCP: Built-in + custom tools with input_schema
    MCP->>ExternalAgent: Tool definitions (JSON Schema)

    ExternalAgent->>MCP: tools/call ProductSearchTool {category: "shoes", price_max: 50}
    MCP->>OpenSearch: POST /_plugins/_ml/agents/<id>/_execute
    OpenSearch->>MCP: Search results
    MCP->>ExternalAgent: Results
```

The `input_schema` auto-generated from parameter definitions is already valid JSON Schema, making MCP integration straightforward — the MCP server simply proxies tool definitions and invocations.

### 7.3 Test Results Summary

40 templates tested covering: basic variables, inverted defaults, toJson arrays, self-guarding sections, boolean guards, nested scopes, dot notation, triple braces, helpers (join/url/toJson), and a 15-parameter e-commerce template.

| Category | Templates | Params Extracted | Arrays | Booleans | Defaults |
|---|---|---|---|---|---|
| Basic & pagination | 5 | 18 | 1 | 0 | 7 |
| Filters & guards | 8 | 35 | 3 | 3 | 9 |
| Helpers & edge cases | 12 | 28 | 8 | 2 | 6 |
| Complex real-world | 15 | 60+ | 6 | 4 | 15 |

Full results: [PARAM_AUTO_GENERATION_TEST_RESULTS.md](PARAM_AUTO_GENERATION_TEST_RESULTS.md)

### 7.4 Future Work

1. **Agent-level tool resolution by ID** — Add `custom_tool_id` to `MLToolSpec` so agents reference tools by index ID
2. **Tier 2 LLM enhancement** — Wire up `MachineLearningNodeClient.predict()` for richer descriptions
3. **Additional tool types** — `http_connector`, `script` (painless), etc.
4. **Search API** — `POST /_plugins/_ml/tools/_search` for querying custom tools
5. **Access control** — `backend_roles` and `access_mode` fields for fine-grained permissions
