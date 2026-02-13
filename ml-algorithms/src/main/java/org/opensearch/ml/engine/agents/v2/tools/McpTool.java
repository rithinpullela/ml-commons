/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.engine.agents.v2.tools;

import java.util.Map;

import org.opensearch.core.action.ActionListener;
import org.opensearch.ml.common.agent.v2.AgentToolV2;
import org.opensearch.ml.common.agent.v2.ToolCallResult;
import org.opensearch.ml.common.agent.v2.ToolExecutionContext;
import org.opensearch.ml.common.agent.v2.ToolSpec;
import org.opensearch.ml.common.utils.StringUtils;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.log4j.Log4j2;

/**
 * V2-native MCP tool implementation.
 * <p>
 * Unlike V1 McpSseTool which goes through flat Map&lt;String,String&gt;,
 * this accepts Map&lt;String,Object&gt; directly from LLM function calls
 * and passes them to the MCP client without string flattening.
 */
@Log4j2
public class McpTool implements AgentToolV2 {

    private final String name;
    private final String description;
    private final ToolSpec toolSpec;
    private final McpSyncClient mcpClient;

    /**
     * Create an MCP tool from the MCP tool listing metadata.
     *
     * @param name Tool name from MCP listing
     * @param description Tool description from MCP listing
     * @param inputSchema JSON Schema from MCP listing (already a Map)
     * @param mcpClient The MCP sync client for this connector
     */
    public McpTool(String name, String description, Map<String, Object> inputSchema, McpSyncClient mcpClient) {
        this.name = name;
        this.description = description;
        this.toolSpec = new ToolSpec(name, description, inputSchema);
        this.mcpClient = mcpClient;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public String getType() {
        return "McpTool";
    }

    @Override
    public ToolSpec getToolSpec() {
        return toolSpec;
    }

    @Override
    public void execute(Map<String, Object> arguments, ToolExecutionContext context, ActionListener<ToolCallResult> listener) {
        try {
            // Call MCP tool directly with structured args — no flattening needed
            McpSchema.CallToolResult result = mcpClient.callTool(new McpSchema.CallToolRequest(name, arguments));
            String resultJson = StringUtils.toJson(result.content());
            listener.onResponse(ToolCallResult.success(context.getToolCallId(), name, resultJson));
        } catch (Exception e) {
            log.error("McpTool execution failed: {}", name, e);
            listener.onResponse(ToolCallResult.error(context.getToolCallId(), name, "MCP tool error: " + e.getMessage()));
        }
    }
}
