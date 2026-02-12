# Custom Tools E2E Test Guide

Tested on a local OpenSearch 3.6.0-SNAPSHOT cluster with the ml-commons plugin.

---

## Prerequisites

```bash
./gradlew run   # Start local cluster on localhost:9200
```

---

## Step 1: Create a test index and ingest data

```bash
PUT /shakespeare
{
  "mappings": {
    "properties": {
      "title": { "type": "text" },
      "author": { "type": "keyword" },
      "year": { "type": "integer" },
      "genre": { "type": "keyword" }
    }
  }
}
```

```bash
POST /shakespeare/_bulk
{"index":{}}
{"title":"Hamlet","author":"William Shakespeare","year":1600,"genre":"tragedy"}
{"index":{}}
{"title":"Macbeth","author":"William Shakespeare","year":1606,"genre":"tragedy"}
{"index":{}}
{"title":"A Midsummer Nights Dream","author":"William Shakespeare","year":1595,"genre":"comedy"}
{"index":{}}
{"title":"The Tempest","author":"William Shakespeare","year":1611,"genre":"comedy"}
{"index":{}}
{"title":"Romeo and Juliet","author":"William Shakespeare","year":1597,"genre":"tragedy"}
{"index":{}}
{"title":"Othello","author":"William Shakespeare","year":1603,"genre":"tragedy"}
{"index":{}}
{"title":"King Lear","author":"William Shakespeare","year":1606,"genre":"tragedy"}
{"index":{}}
{"title":"The Merchant of Venice","author":"William Shakespeare","year":1596,"genre":"comedy"}
```

---

## Step 2: Create a search template (stored script)

This template uses Mustache optional sections (`{{#genre}}...{{/genre}}`) so that the genre filter is only applied when the parameter is provided.

```bash
POST /_scripts/shakespeare_search
{
  "script": {
    "lang": "mustache",
    "source": "{\"query\":{\"bool\":{\"must\":[{\"match\":{\"title\":\"{{query_text}}\"}}]{{#genre}},\"filter\":[{\"term\":{\"genre\":\"{{genre}}\"}}]{{/genre}}}},\"size\":{{result_size}}}"
  }
}
```

**Verify:**
```bash
GET /_scripts/shakespeare_search
```

---

## Step 3: Create a custom tool

```bash
POST /_plugins/_ml/tools/_create
{
  "name": "ShakespeareSearchTool",
  "description": "Searches Shakespeare plays by title and optionally filters by genre",
  "type": "search_template",
  "search_template_name": "shakespeare_search",
  "params": {
    "query_text": {
      "type": "text",
      "description": "Words to match in the play title",
      "required": true
    },
    "genre": {
      "type": "text",
      "description": "Genre to filter by (tragedy or comedy). Optional.",
      "required": false
    },
    "result_size": {
      "type": "integer",
      "description": "Maximum number of results to return",
      "required": false
    }
  }
}
```

**Response:**
```json
{ "tool_id": "BSwtTpwBuXkmKgT1qr7-" }
```

---

## Step 4: Verify tool in GET and LIST

**GET by name:**
```bash
GET /_plugins/_ml/tools/ShakespeareSearchTool
```
```json
{
  "name": "ShakespeareSearchTool",
  "description": "Searches Shakespeare plays by title and optionally filters by genre",
  "type": "search_template",
  "version": "undefined"
}
```

**LIST all tools (merged built-in + custom):**
```bash
GET /_plugins/_ml/tools
```
Returns 15 tools (14 built-in + 1 custom). The custom tool appears alongside built-in tools like `SearchIndexTool`, `MLModelTool`, etc.

---

## Step 5: Update the custom tool

```bash
PUT /_plugins/_ml/tools/<tool_id>
{
  "description": "Updated: Searches Shakespeare plays by title, optionally filtered by genre"
}
```

**Response:**
```json
{
  "_index": ".plugins-ml-custom-tools",
  "_id": "BSwtTpwBuXkmKgT1qr7-",
  "_version": 2,
  "result": "updated"
}
```

---

## Step 6: Execute the tool via the tool execute API

The tool execute API (`POST /_plugins/_ml/tools/_execute/{tool_name}`) allows testing tools directly without creating an agent.

**Register the tool first** (the tool execute API uses `SearchTemplateTool.Factory.create()` to build the tool from the provided parameters):

### 6a. Render-only mode with genre filter

```bash
POST /_plugins/_ml/tools/_execute/SearchTemplateTool
{
  "parameters": {
    "search_template_name": "shakespeare_search",
    "params": "{\"query_text\": {\"type\": \"text\", \"description\": \"Words to match in play title\", \"required\": true}, \"genre\": {\"type\": \"text\", \"description\": \"Genre filter\", \"required\": false}, \"result_size\": {\"type\": \"integer\", \"description\": \"Max results\", \"required\": false}}",
    "query_text": "hamlet",
    "genre": "tragedy",
    "result_size": "5",
    "execution_mode": "render_only"
  }
}
```

**Response** (genre filter included because `genre` was provided):
```json
{
  "inference_results": [{
    "output": [{
      "name": "response",
      "result": "{\"query\":{\"bool\":{\"must\":[{\"match\":{\"title\":\"hamlet\"}}],\"filter\":[{\"term\":{\"genre\":\"tragedy\"}}]}},\"size\":5}"
    }]
  }]
}
```

### 6b. Render-only mode without genre filter

```bash
POST /_plugins/_ml/tools/_execute/SearchTemplateTool
{
  "parameters": {
    "search_template_name": "shakespeare_search",
    "params": "{\"query_text\": {\"type\": \"text\", \"description\": \"Words to match in play title\", \"required\": true}, \"genre\": {\"type\": \"text\", \"description\": \"Genre filter\", \"required\": false}, \"result_size\": {\"type\": \"integer\", \"description\": \"Max results\", \"required\": false}}",
    "query_text": "hamlet",
    "result_size": "5",
    "execution_mode": "render_only"
  }
}
```

**Response** (genre filter omitted because `genre` was not provided — Mustache `{{#genre}}...{{/genre}}` section excluded):
```json
{
  "inference_results": [{
    "output": [{
      "name": "response",
      "result": "{\"query\":{\"bool\":{\"must\":[{\"match\":{\"title\":\"hamlet\"}}]}},\"size\":5}"
    }]
  }]
}
```

### 6c. Execute mode — returns search results

```bash
POST /_plugins/_ml/tools/_execute/SearchTemplateTool
{
  "parameters": {
    "search_template_name": "shakespeare_search",
    "params": "{\"query_text\": {\"type\": \"text\", \"description\": \"Words to match in play title\", \"required\": true}, \"genre\": {\"type\": \"text\", \"description\": \"Genre filter\", \"required\": false}, \"result_size\": {\"type\": \"integer\", \"description\": \"Max results\", \"required\": false}}",
    "query_text": "hamlet",
    "genre": "tragedy",
    "result_size": "5"
  }
}
```

**Response:**
```json
{
  "inference_results": [{
    "output": [{
      "name": "response",
      "result": "{\"_index\":\"shakespeare\",\"_source\":{\"year\":1600,\"author\":\"William Shakespeare\",\"genre\":\"tragedy\",\"title\":\"Hamlet\"},\"_id\":\"...\",\"_score\":0.8139898}\n"
    }]
  }]
}
```

### 6d. Both mode — returns rendered query AND search results

```bash
POST /_plugins/_ml/tools/_execute/SearchTemplateTool
{
  "parameters": {
    "search_template_name": "shakespeare_search",
    "params": "{\"query_text\": {\"type\": \"text\", \"description\": \"Words to match in play title\", \"required\": true}, \"genre\": {\"type\": \"text\", \"description\": \"Genre filter\", \"required\": false}, \"result_size\": {\"type\": \"integer\", \"description\": \"Max results\", \"required\": false}}",
    "query_text": "hamlet",
    "genre": "tragedy",
    "result_size": "5",
    "execution_mode": "both"
  }
}
```

**Response:**
```json
{
  "inference_results": [{
    "output": [{
      "name": "response",
      "result": "{\"search_results\":\"...\",\"rendered_query\":\"{\\\"query\\\":{\\\"bool\\\":{\\\"must\\\":[{\\\"match\\\":{\\\"title\\\":\\\"hamlet\\\"}}],\\\"filter\\\":[{\\\"term\\\":{\\\"genre\\\":\\\"tragedy\\\"}}]}},\\\"size\\\":5}\"}"
    }]
  }]
}
```

---

## Step 7: Execute the tool via a flow agent

**Register the agent:**
```bash
POST /_plugins/_ml/agents/_register
{
  "name": "Shakespeare Search Agent",
  "type": "flow",
  "tools": [
    {
      "type": "SearchTemplateTool",
      "name": "ShakespeareSearchTool",
      "parameters": {
        "search_template_name": "shakespeare_search",
        "params": "{\"query_text\": {\"type\": \"text\", \"description\": \"Words to match in play title\", \"required\": true}, \"genre\": {\"type\": \"text\", \"description\": \"Genre filter\", \"required\": false}, \"result_size\": {\"type\": \"integer\", \"description\": \"Max results\", \"required\": false}}"
      }
    }
  ]
}
```

### 7a. Execute mode (default) — returns search results

```bash
POST /_plugins/_ml/agents/<agent_id>/_execute
{
  "parameters": {
    "query_text": "hamlet",
    "genre": "tragedy",
    "result_size": "5"
  }
}
```

**Response:**
```json
{
  "inference_results": [{
    "output": [{
      "name": "response",
      "result": "{\"_index\":\"shakespeare\",\"_source\":{\"year\":1600,\"author\":\"William Shakespeare\",\"genre\":\"tragedy\",\"title\":\"Hamlet\"},\"_id\":\"...\",\"_score\":0.8139898}\n"
    }]
  }]
}
```

### 7b. Render-only mode — returns rendered DSL without executing

```bash
POST /_plugins/_ml/agents/<agent_id>/_execute
{
  "parameters": {
    "query_text": "hamlet",
    "genre": "tragedy",
    "result_size": "5",
    "execution_mode": "render_only"
  }
}
```

**Response:**
```json
{
  "inference_results": [{
    "output": [{
      "name": "response",
      "result": "{\"query\":{\"bool\":{\"must\":[{\"match\":{\"title\":\"hamlet\"}}],\"filter\":[{\"term\":{\"genre\":\"tragedy\"}}]}},\"size\":5}"
    }]
  }]
}
```

### 7c. Both mode — returns rendered query AND search results

```bash
POST /_plugins/_ml/agents/<agent_id>/_execute
{
  "parameters": {
    "query_text": "romeo",
    "genre": "tragedy",
    "result_size": "3",
    "execution_mode": "both"
  }
}
```

**Response contains both `rendered_query` and `search_results`:**
```json
{
  "inference_results": [{
    "output": [{
      "name": "response",
      "result": "{\"search_results\":\"...\",\"rendered_query\":\"{...}\"}"
    }]
  }]
}
```

---

## Step 8: Delete the custom tool

```bash
DELETE /_plugins/_ml/tools/<tool_id>
```

**Verify removal:**
- `GET /_plugins/_ml/tools/ShakespeareSearchTool` returns 404
- `GET /_plugins/_ml/tools` returns 14 tools (custom tool gone)

---

## Error cases (all return 400)

| Test | Request | Error Message |
|------|---------|---------------|
| Duplicate name | POST `_create` with existing name | `A custom tool with name 'ShakespeareSearchTool' already exists` |
| Non-existent template | POST `_create` with bad `search_template_name` | `Search template 'this_template_does_not_exist' not found` |
| Name starts with `_` | POST `_create` with `"name": "_badname"` | `Custom tool name cannot start with '_'` |
| Invalid type | POST `_create` with `"type": "invalid_type"` | `Custom tool type must be 'search_template'` |
| Missing required fields | POST `_create` with only `"name"` | `Custom tool description is required` |

---

## Index details

Custom tools are stored in the `.plugins-ml-custom-tools` system index. You can inspect directly:

```bash
GET /.plugins-ml-custom-tools/_search
{
  "query": { "match_all": {} }
}
```

Each document contains: `name`, `description`, `type`, `search_template_name`, `params` (parameter definitions), `tenant_id`, `create_time`, `last_update_time`.
