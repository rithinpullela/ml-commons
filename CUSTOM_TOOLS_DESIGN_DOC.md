# Custom Tools for ML Commons — Design Document

## 1. Overview

This feature introduces user-defined custom tools backed by OpenSearch search templates that can be managed via CRUD APIs and attached to ML agents. Previously, only built-in tools (hardcoded via `Tool.Factory` in the plugin) were available to agents. Custom tools allow users to wrap search templates — parameterized, reusable query definitions — as first-class tools that agents can invoke at runtime.

### User Flow

```
1. User creates a search template:     POST _scripts/<template_name>
2. User registers a custom tool:       POST /_plugins/_ml/tools/_create
3. User attaches the tool to an agent:  POST /_plugins/_ml/agents/_register
4. Agent executes the tool at runtime:  POST /_plugins/_ml/agents/<id>/_execute
```

---

## 2. Design Decisions

### 2.1 Dedicated System Index vs. Extending Existing Storage

**Decision**: Store custom tools in a new dedicated system index `.plugins-ml-custom-tools`.

**Alternatives considered**:
- Storing tool definitions inline within agent configurations
- Reusing the existing `.plugins-ml-config` index

**Rationale**: A dedicated index provides independent lifecycle management (tools can be shared across agents), clean index mappings tailored to tool metadata, and follows the established pattern in ml-commons where each resource type has its own index (connectors, models, agents, MCP tools all have separate indices). This also enables the tools to be listed, searched, and managed independently.

### 2.2 Tool Name Uniqueness

**Decision**: Tool names must be unique. The system rejects creation of a tool whose name already exists in the custom tools index.

**Alternatives considered**:
- Allow duplicate names, identify tools by internal document ID only
- Allow duplicates but warn

**Rationale**: Tools are addressed by name in multiple contexts:
- The `GET /_plugins/_ml/tools/{tool_name}` endpoint looks up tools by name
- The `GET /_plugins/_ml/tools` endpoint merges built-in and custom tools by name (deduplication)
- Agents reference tools by type/name when constructing tool instances

Non-unique names would make GET-by-name non-deterministic and create ambiguity when agents resolve tools. Enforcing uniqueness at creation time makes the system predictable. The uniqueness check queries the custom tools index by `name.keyword` before allowing creation.

### 2.3 Route Path Wildcard Constraint

**Decision**: UPDATE and DELETE routes use `{tool_name}` as the path parameter name (not `{tool_id}`), accepting the internal document ID through a parameter named `tool_name`.

**Context**: OpenSearch's `PathTrie` requires all routes at the same path position to use the same wildcard name. The pre-existing GET route is:
```
GET /_plugins/_ml/tools/{tool_name}
```

Our new routes share the same path prefix:
```
PUT    /_plugins/_ml/tools/{tool_name}
DELETE /_plugins/_ml/tools/{tool_name}
```

Using `{tool_id}` for PUT/DELETE would cause a startup crash:
```
IllegalArgumentException: Trying to use conflicting wildcard names for same path: tool_name and tool_id
```

**Rationale**: This is an OpenSearch framework constraint, not a design choice. The path parameter name is cosmetic — the handler correctly extracts the value and uses it as a document ID for update/delete operations. The semantic mismatch (parameter named `tool_name` containing a document ID) is contained within the REST layer and doesn't leak into the transport layer.

### 2.4 Merged GET/LIST Response

**Decision**: `GET /_plugins/_ml/tools` and `GET /_plugins/_ml/tools/{name}` return a merged view of built-in tools and custom tools in a single response.

**Alternatives considered**:
- Separate endpoints for built-in vs. custom tools
- A query parameter to filter (`?source=custom`)

**Rationale**: From a user/agent perspective, a tool is a tool regardless of whether it's built-in or user-defined. A merged response gives a complete picture of what's available. The implementation checks built-in tools first (in-memory, fast), then falls back to the custom tools index. `ListToolsTransportAction` deduplicates by name (built-in takes precedence) and gracefully degrades — if the custom tools index query fails, it returns only built-in tools rather than failing entirely.

### 2.5 SearchTemplateTool: Template Rendering via ScriptService

**Decision**: The `SearchTemplateTool` renders stored Mustache templates using OpenSearch's core `ScriptService` API (`ScriptService.compile()` + `TemplateScript`), without any direct dependency on the `lang-mustache` module.

**Alternatives considered**:
- Using `SearchTemplateAction` / `SearchTemplateRequest` from the `lang-mustache` module directly
- Manual regex-based `{{param}}` substitution

**Rationale**: The `lang-mustache` module is not in ml-commons' dependency graph and **cannot** be added — even as a `compileOnly` dependency. OpenSearch loads the `lang-mustache` module with its own classloader, isolated from the plugin classloader. Attempting to use `SearchTemplateRequest` or `SearchTemplateAction` at runtime results in `NoClassDefFoundError` / `ClassNotFoundException`, regardless of compile-time availability.

The solution uses `ScriptService`, which is part of core OpenSearch. The `MustacheScriptEngine` (from the `lang-mustache` module) registers itself with `ScriptService` at node startup via the `ScriptPlugin` SPI. This means any code with access to `ScriptService` can compile and render stored Mustache templates through:

```java
Script script = new Script(ScriptType.STORED, null, templateName, Collections.emptyMap());
TemplateScript compiled = scriptService.compile(script, TemplateScript.CONTEXT).newInstance(params);
String rendered = compiled.execute();
```

This approach provides **full Mustache support** — including sections (`{{#param}}...{{/param}}`), inverted sections (`{{^param}}...{{/param}}`), list iteration, and all other Mustache features — without importing any `lang-mustache` classes. The rendered JSON string is then parsed into a `SearchSourceBuilder` and executed via `client.search()`.

### 2.6 Execution Modes

**Decision**: The tool supports three execution modes exposed as a user/LLM parameter:
- `execute` (default) — renders template, executes search, returns results
- `render_only` — renders template, returns the DSL query without executing
- `both` — renders template, executes search, returns both query and results

**Rationale**: Different use cases need different outputs:
- An agent answering user questions needs search results (`execute`)
- A debugging/transparency flow needs to see what query was generated (`render_only`)
- An observability or audit flow needs both (`both`)

Exposing this as a parameter (rather than separate tools) keeps the tool count manageable while giving flexibility. The `execution_mode` parameter is included in the auto-generated `input_schema` so LLMs can discover it.

### 2.7 Parameter Type Conversion

**Decision**: Parameter definitions include a `type` field (`text`, `integer`, `float`, `double`, `boolean`, `long`) that drives automatic type conversion from string inputs.

**Rationale**: LLMs and REST APIs pass all parameters as strings. Search templates often need typed values — e.g., `"size": {{result_size}}` expects an integer, not a quoted string. Without type conversion, the rendered JSON would be `"size": "5"` (invalid) instead of `"size": 5`. The type metadata in parameter definitions enables the tool to convert `"5"` to `5` before template rendering, producing valid JSON.

### 2.8 Validation at Both Create-Time and Run-Time

**Decision**: The search template is validated (confirmed to exist) both when the custom tool is created and when it is executed.

**Alternatives considered**:
- Validate only at creation time (simpler, but template could be deleted later)
- Validate only at runtime (allows creating tools for templates that don't exist yet)

**Rationale**: Create-time validation catches typos and configuration errors immediately, giving clear feedback via `GetStoredScriptRequest`. Runtime validation is implicit — if the stored script has been deleted, `ScriptService.compile()` will throw an error when attempting to compile the stored template, providing a clear failure message.

### 2.9 `type` Field Restricted to `search_template`

**Decision**: The `type` field in `MLCustomToolInput` is validated to be exactly `"search_template"`. Any other value is rejected.

**Rationale**: This POC only implements the `SearchTemplateTool` backend. The `type` field is designed to be extensible — future custom tool types (e.g., `"http_connector"`, `"script"`) can be added by relaxing this validation and implementing corresponding `Tool` classes. Restricting to `search_template` for now prevents users from creating tools that can't actually execute.

### 2.10 Multi-Tenancy Support

**Decision**: Follow the same multi-tenancy pattern as connectors — `tenant_id` stored in the index, validated on every CRUD operation via `TenantAwareHelper`.

**Rationale**: Consistency with the existing connector CRUD pattern. The `MLCustomToolInput` carries a `tenantId` field, set from request headers by the REST layer. Transport actions call `TenantAwareHelper.validateTenantId()` before any operation. The index mapping includes `tenant_id` as a keyword field.

---

## 3. Architecture

### 3.1 Layered Architecture

```
REST Layer                    Transport Layer                   Storage
─────────────────────────     ────────────────────────────      ──────────────────
RestMLCreateCustomToolAction → CreateCustomToolTransportAction → .plugins-ml-custom-tools
RestMLUpdateCustomToolAction → UpdateCustomToolTransportAction → .plugins-ml-custom-tools
RestMLDeleteCustomToolAction → DeleteCustomToolTransportAction → .plugins-ml-custom-tools
RestMLGetToolAction          → GetToolTransportAction          → ToolFactory (built-in)
                                                                + .plugins-ml-custom-tools
RestMLListToolsAction        → ListToolsTransportAction        → ToolFactory (built-in)
                                                                + .plugins-ml-custom-tools
```

### 3.2 Tool Execution Flow

```
Agent._execute(params)
  └→ SearchTemplateTool.run(params)
       ├→ validate(params)                        // Check required params
       ├→ buildScriptParams(params)               // Convert types via paramDefinitions
       ├→ renderTemplate(scriptParams)             // ScriptService.compile() + TemplateScript.execute()
       ├→ [if render_only] return rendered DSL
       ├→ buildSearchRequest(renderedQuery)        // Parse JSON → SearchSourceBuilder
       ├→ client.search(searchRequest)             // Execute search
       └→ processSearchResponse()                 // Format hits as JSON
```

### 3.3 Input Schema Auto-Generation

When `SearchTemplateTool.Factory.create()` builds a tool instance, it converts the parameter definitions into a JSON Schema stored in `attributes["input_schema"]`:

```
params: {                          input_schema: {
  "query_text": {                    "type": "object",
    "type": "text",        →        "properties": {
    "description": "...",              "query_text": {"type":"string","description":"..."},
    "required": true                   "execution_mode": {"type":"string","description":"..."}
  }                                  },
}                                    "required": ["query_text"]
                                   }
```

This schema is used by LLM-based agents (e.g., `conversational` agents) to understand what parameters the tool expects, enabling function-calling style invocations.

---

## 4. Files Summary

### New Files (17)

| Layer | File | Purpose |
|-------|------|---------|
| Index | `common/src/main/resources/index-mappings/ml_custom_tools.json` | Index mapping for `.plugins-ml-custom-tools` |
| Model | `common/.../transport/tools/MLCustomToolInput.java` | Data model (parse, serialize, validate) |
| Transport | `common/.../transport/tools/MLCreateCustomToolAction.java` | Create action type |
| Transport | `common/.../transport/tools/MLCreateCustomToolRequest.java` | Create request |
| Transport | `common/.../transport/tools/MLCreateCustomToolResponse.java` | Create response (returns tool_id) |
| Transport | `common/.../transport/tools/MLUpdateCustomToolAction.java` | Update action type |
| Transport | `common/.../transport/tools/MLUpdateCustomToolRequest.java` | Update request |
| Transport | `common/.../transport/tools/MLDeleteCustomToolAction.java` | Delete action type |
| Transport | `common/.../transport/tools/MLDeleteCustomToolRequest.java` | Delete request |
| Tool | `ml-algorithms/.../engine/tools/SearchTemplateTool.java` | Tool implementation + Factory |
| Action | `plugin/.../action/tools/CreateCustomToolTransportAction.java` | Create handler (validate + persist) |
| Action | `plugin/.../action/tools/UpdateCustomToolTransportAction.java` | Update handler |
| Action | `plugin/.../action/tools/DeleteCustomToolTransportAction.java` | Delete handler |
| Helper | `plugin/.../action/tools/CustomToolsHelper.java` | Index search utilities |
| REST | `plugin/.../rest/RestMLCreateCustomToolAction.java` | POST `/_plugins/_ml/tools/_create` |
| REST | `plugin/.../rest/RestMLUpdateCustomToolAction.java` | PUT `/_plugins/_ml/tools/{tool_name}` |
| REST | `plugin/.../rest/RestMLDeleteCustomToolAction.java` | DELETE `/_plugins/_ml/tools/{tool_name}` |

### Modified Files (6)

| File | Change |
|------|--------|
| `CommonValue.java` | Added `ML_CUSTOM_TOOLS_INDEX`, `ML_CUSTOM_TOOLS_INDEX_MAPPING_PATH` |
| `MLIndex.java` | Added `CUSTOM_TOOLS` enum entry |
| `MLIndicesHandler.java` | Added `initMLCustomToolsIndex()` |
| `GetToolTransportAction.java` | Falls back to custom tools index if built-in not found |
| `ListToolsTransportAction.java` | Merges custom tools into response, deduplicates by name |
| `MachineLearningPlugin.java` | Registers actions, REST handlers, `SearchTemplateTool.Factory`, `CustomToolsHelper` |

---

## 5. Validation Rules

| Rule | Where Enforced | Error |
|------|---------------|-------|
| Name required | `MLCustomToolInput` constructor | `Custom tool name is required` |
| Description required | `MLCustomToolInput` constructor | `Custom tool description is required` |
| Type required | `MLCustomToolInput` constructor | `Custom tool type is required` |
| Type must be `search_template` | `MLCustomToolInput` constructor | `Custom tool type must be 'search_template'` |
| Search template name required | `MLCustomToolInput` constructor | `Search template name is required` |
| Name cannot start with `_` | `CreateCustomToolTransportAction` | `Custom tool name cannot start with '_'` |
| Name must be unique | `CreateCustomToolTransportAction` | `A custom tool with name '...' already exists` |
| Search template must exist | `CreateCustomToolTransportAction` | `Search template '...' not found` |
| Tenant validation | All transport actions | Via `TenantAwareHelper` |
| Required params at runtime | `SearchTemplateTool.validate()` | `Missing required parameters` |
| Template exists at runtime | `ScriptService.compile()` (implicit) | Script compilation error if template deleted |

---

## 6. LLM-Assisted Parameter Auto-Generation

### 6.1 Motivation

Creating a custom tool requires manually defining every parameter — name, type, description, and whether it's required. For a customer with 20+ templates, this is tedious and error-prone, especially since the template already implicitly contains this information in its Mustache variables and DSL structure.

To reduce setup friction, users can optionally provide a `model_id` during tool creation. The system will fetch the stored template, extract parameters programmatically, call the LLM to infer types and descriptions, and store the generated params alongside the tool.

### 6.2 Interface

**With `model_id` (auto-generate params):**

```json
POST /_plugins/_ml/tools/_create
{
  "name": "ProductSearchTool",
  "description": "Search products by category, brand, price, and color.",
  "type": "search_template",
  "search_template_name": "product_search_v2",
  "index": "products",
  "model_id": "haiku-model-id"
}
```

**With `params` (manual — existing behavior, unchanged):**

```json
POST /_plugins/_ml/tools/_create
{
  "name": "ProductSearchTool",
  "description": "Search products by category, brand, price, and color.",
  "type": "search_template",
  "search_template_name": "product_search_v2",
  "index": "products",
  "params": {
    "category":  { "type": "string",  "description": "Product category", "required": true },
    "price_max": { "type": "number",  "description": "Maximum price",    "required": false }
  }
}
```

**Validation:**

| Field provided | Behavior |
|---|---|
| `params` only | Manual — user defines all param schemas (existing behavior) |
| `model_id` only | Auto — params extracted from template + LLM |
| Both `params` and `model_id` | Error: `Cannot specify both 'params' and 'model_id'. Use one or the other.` |
| Neither | Error: `Either 'params' or 'model_id' is required.` |

**Response includes generated params** so the user can immediately review:

```json
{
  "tool_id": "abc123",
  "name": "ProductSearchTool",
  "params": {
    "category":  { "type": "string",  "description": "Product category to match against the category field", "required": true },
    "color":     { "type": "string",  "description": "Product color for term filtering",                     "required": false },
    "price_max": { "type": "float",   "description": "Maximum price in USD for range filtering",             "required": false }
  }
}
```

If the LLM gets something wrong, the user corrects it via `PUT /_plugins/_ml/tools/{tool_id}`.

### 6.3 Two-Phase Extraction: Programmatic + LLM

Parameter extraction is split into two phases. Phase 1 is deterministic (no LLM needed). Phase 2 uses the LLM only for what requires semantic understanding.

#### Phase 1: Programmatic Extraction (Server-Side Java)

Parse the Mustache template source string to extract:

1. **Variable names** — scan for `{{variable_name}}` patterns, excluding section markers (`{{#`, `{{/`, `{{^`)
2. **Required vs. optional** — determined by template structure:
   - A variable that appears ONLY inside a conditional section (`{{#var}}...{{var}}...{{/var}}`) is **optional**
   - A variable that appears outside any conditional section is **required**
   - A variable with an inverted section default (`{{^var}}default_value{{/var}}`) is **optional**

**Example:**

```
Template: {"must":[{"match":{"title":"{{query_text}}"}}]{{#genre}},{"filter":[{"term":{"genre":"{{genre}}"}}]}{{/genre}}},"size":{{result_size}}}

Variables found:  [query_text, genre, result_size]
Sections found:   [genre]

query_text  → appears outside all sections   → required: true
genre       → appears only inside {{#genre}} → required: false
result_size → appears outside all sections   → required: true
```

This logic is fully deterministic — no LLM involved, no hallucination risk.

#### Phase 2: LLM-Assisted Type and Description Inference

The LLM receives the template source and the pre-extracted variable list, and infers `type` and `description` for each.

**Prompt:**

```
You are analyzing an OpenSearch Mustache search template to determine parameter types and descriptions.

## Template Source
{template_source}

## Parameters to Annotate
The following parameters were extracted from the template. For each one, determine its type and write a description.

Parameters: {extracted_variable_names}

## Instructions

For each parameter, determine:

1. **type**: Infer from how the parameter is used in the query DSL:
   - "string" — used in match, term, or text field contexts (e.g., "match": {"field": "{{var}}"})
   - "integer" — used as a bare numeric value without quotes (e.g., "size": {{var}}) or in integer contexts
   - "float" — used in range queries with decimal values, boost values, or scores
   - "double" — used for high-precision numeric values
   - "boolean" — used in boolean contexts (e.g., "track_total_hits": {{var}})
   - "long" — used for timestamps, epoch values, or large numeric IDs
   - Default to "string" if the usage context is ambiguous

2. **description**: A clear, concise description of what this parameter controls. Base it on:
   - The field name it maps to in the index
   - The query clause it appears in (match, term, range, filter, bool, etc.)
   - Its role in the query (filtering, scoring, pagination, etc.)

## Important
- Do NOT add or remove parameters — annotate exactly the list provided
- Do NOT determine "required" — that is already handled separately

Return ONLY a valid JSON object in this exact format, no other text:
{
  "params": {
    "parameter_name": {
      "type": "string|integer|long|float|double|boolean",
      "description": "description of the parameter"
    }
  }
}
```

**Why this split works:**

| Aspect | Phase 1 (Programmatic) | Phase 2 (LLM) |
|--------|----------------------|---------------|
| Variable names | Regex extraction | N/A |
| Required/optional | Section nesting analysis | N/A |
| Type | N/A | DSL context inference |
| Description | N/A | Semantic understanding |
| Reliability | 100% deterministic | Best-effort, user-reviewable |

The LLM's job is reduced to type inference and description writing — both of which it's good at and both of which are easily reviewable/correctable via `PUT`.

### 6.4 Creation Flow with Auto-Generation

```
POST /_plugins/_ml/tools/_create (with model_id)
  │
  ├→ 1. Validate tenant, name uniqueness, name format (existing)
  │
  ├→ 2. Fetch stored script via GetStoredScriptRequest
  │     └→ Extract template source string
  │
  ├→ 3. Phase 1: Programmatic extraction
  │     ├→ Extract variable names from template
  │     └→ Determine required/optional from section nesting
  │
  ├→ 4. Phase 2: LLM call
  │     ├→ Build prompt with template source + variable names
  │     ├→ Call model via MachineLearningNodeClient.predict()
  │     └→ Parse JSON response → type + description per param
  │
  ├→ 5. Merge Phase 1 + Phase 2
  │     └→ Combine: {name, type (LLM), description (LLM), required (programmatic)}
  │
  └→ 6. Store custom tool with generated params (existing indexing flow)
```

### 6.5 Prompt Walkthrough with Real Template

**Input template** (Shakespeare search):
```
{"query":{"bool":{"must":[{"match":{"title":"{{query_text}}"}}]{{#genre}},
"filter":[{"term":{"genre":"{{genre}}"}}]{{/genre}}}},"size":{{result_size}}}
```

**Phase 1 output:**
```
query_text  → required: true
genre       → required: false
result_size → required: true
```

**Phase 2 prompt sends:**
```
Parameters to Annotate: [query_text, genre, result_size]
```

**Phase 2 LLM response:**
```json
{
  "params": {
    "query_text":  { "type": "string",  "description": "Text to match against the title field using full-text search" },
    "genre":       { "type": "string",  "description": "Genre keyword to filter results by (e.g., tragedy, comedy)" },
    "result_size": { "type": "integer", "description": "Maximum number of search results to return" }
  }
}
```

**Merged final params stored:**
```json
{
  "query_text":  { "type": "string",  "description": "Text to match against the title field using full-text search", "required": true },
  "genre":       { "type": "string",  "description": "Genre keyword to filter results by (e.g., tragedy, comedy)",   "required": false },
  "result_size": { "type": "integer", "description": "Maximum number of search results to return",                   "required": true }
}
```

### 6.6 Design Decisions

#### 6.6.1 JSON Prompt vs. Forced Tool Call

**Decision**: Use a JSON prompt for the LLM call, not forced tool calls (`tool_choice: "required"`).

**Alternatives considered**:
- Define a function schema for `extract_parameters` and force the LLM to call it via `tool_choice: "required"`

**Rationale**: The creation flow makes a standalone LLM call via `MachineLearningNodeClient.predict()`, not through the agent loop. The function calling infrastructure (`FunctionCalling` interface, `OpenaiV1ChatCompletionsFunctionCalling`, etc.) is tightly coupled to `MLChatAgentRunner` and the ReAct loop. Reusing it for a one-shot call would require significant refactoring.

A well-structured JSON prompt is sufficient for this use case because:
- The output schema is simple and fixed (a flat map of param names to type+description)
- The response is validated and parsed server-side — malformed JSON triggers an error
- This is a creation-time operation, not a latency-critical query-time call
- The user reviews and can correct the output via `PUT`

If reliability becomes an issue, forced tool calls can be added as a future enhancement by making the function calling infrastructure available outside the agent loop.

#### 6.6.2 `model_id` and `params` Are Mutually Exclusive

**Decision**: Reject requests that provide both `model_id` and `params`.

**Rationale**: If the user provides manual params, auto-generation is unnecessary. If they want auto-generation, manual params would be overwritten. Mutual exclusion eliminates ambiguity about which source of truth wins.

---

## 7. Limitations and Future Work

### Current Limitations

1. **No `custom_tool_id` in agent spec**: Agents reference the tool via `type: "SearchTemplateTool"` with parameters, not by custom tool ID. Adding ID-based resolution requires making `AgentUtils.createTool()` async (significant refactor).
2. **No search on custom tools**: No dedicated search API (e.g., `POST /_plugins/_ml/tools/_search`). Users can query the index directly if needed.
3. **Single tool type**: Only `search_template` is supported. The `type` field is validated strictly.

### Future Enhancements

1. **Agent-level custom tool resolution by ID**: Add `custom_tool_id` to `MLToolSpec` so agents can reference tools by their index ID instead of manually specifying parameters.
2. **Additional tool types**: Support `http_connector` (arbitrary HTTP calls), `script` (painless scripts), etc. by adding new `Tool` implementations and relaxing the type validation.
3. **Search API**: Add `POST /_plugins/_ml/tools/_search` for querying custom tools with arbitrary criteria.
4. **Access control**: Add `backend_roles` and `access_mode` fields (like connectors) for fine-grained access control on custom tools.
