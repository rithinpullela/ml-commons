# Stateless MCP Server Examples

This document shows how to use the new stateless MCP endpoints alongside the existing SSE-based MCP server.

## **New Endpoint**

- **Stateless MCP**: `POST /_plugins/_ml/mcp/stream`
- **Existing SSE MCP**: `GET /_plugins/_ml/mcp/sse` and `POST /_plugins/_ml/mcp/sse/message`

## **1. Initialize Connection**

```bash
curl -X POST http://localhost:9200/_plugins/_ml/mcp/stream \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": "1",
    "method": "initialize",
    "params": {
      "protocolVersion": "2024-11-05",
      "capabilities": {},
      "clientInfo": {
        "name": "test-client",
        "version": "1.0.0"
      }
    }
  }'
```

**Expected Response:**
```json
{
  "jsonrpc": "2.0",
  "id": "1",
  "result": {
    "protocolVersion": "2024-11-05",
    "capabilities": {
      "tools": {"enabled": true},
      "logging": {"enabled": true}
    },
    "serverInfo": {
      "name": "OpenSearch-MCP-Stateless-Server",
      "version": "0.1.0"
    }
  }
}
```

## **2. List Available Tools**

```bash
curl -X POST http://localhost:9200/_plugins/_ml/mcp/stream \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": "2",
    "method": "tools/list",
    "params": {}
  }'
```

**Expected Response:**
```json
{
  "jsonrpc": "2.0",
  "id": "2",
  "result": {
    "tools": [
      {
        "name": "example_tool",
        "description": "Example tool for stateless MCP",
        "inputSchema": {}
      }
    ]
  }
}
```

## **3. Execute a Tool**

```bash
curl -X POST http://localhost:9200/_plugins/_ml/mcp/stream \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": "3",
    "method": "tools/call",
    "params": {
      "name": "example_tool",
      "arguments": {
        "param1": "value1",
        "param2": "value2"
      }
    }
  }'
```

**Expected Response:**
```json
{
  "jsonrpc": "2.0",
  "id": "3",
  "result": {
    "content": [
      {
        "type": "text",
        "text": "Tool 'example_tool' executed successfully with arguments: {param1=value1, param2=value2}"
      }
    ],
    "isError": false
  }
}
```

## **4. Error Handling**

If an unsupported method is called:

```bash
curl -X POST http://localhost:9200/_plugins/_ml/mcp/stream \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": "4",
    "method": "unsupported_method",
    "params": {}
  }'
```

**Expected Response:**
```json
{
  "jsonrpc": "2.0",
  "id": "4",
  "error": {
    "code": -32603,
    "message": "Unsupported method: unsupported_method"
  }
}
```

## **Comparison with Existing SSE Approach**

### **Stateless (New)**
- ✅ **Simple HTTP POST requests**
- ✅ **No session management**
- ✅ **Direct tool execution**
- ✅ **Easy to test with curl**
- ✅ **Stateless - each request is independent**

### **SSE (Existing)**
- ✅ **Persistent connections**
- ✅ **Session state management**
- ✅ **Real-time streaming**
- ✅ **Complex workflows with state**
- ✅ **Tool context preservation**

## **Use Cases**

### **Use Stateless When:**
- Simple tool execution
- Testing and development
- Stateless workflows
- HTTP-native clients
- Quick tool queries

### **Use SSE When:**
- Complex multi-step workflows
- Real-time streaming
- Session state needed
- Long-running operations
- Tool context preservation

## **Next Steps**

1. **Test the basic endpoints** with the examples above
2. **Integrate with existing tools** by connecting to `McpToolsHelper`
3. **Add authentication** if needed
4. **Implement actual tool execution** using existing infrastructure
5. **Add more MCP methods** as needed

## **Integration with Existing Tools**

The stateless server is designed to reuse all existing tool infrastructure:

- **Tool Registration**: Uses same indices and management
- **Tool Execution**: Leverages existing `McpToolsHelper`
- **Tool Sync**: Benefits from same 10-second sync mechanism
- **Tool Updates**: Automatically gets new tools via existing processes

This means you get all the benefits of the existing complex tool system without any changes to it! 