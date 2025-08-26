/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.action.mcpserver;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.opensearch.ml.common.CommonValue;
import org.opensearch.ml.common.spi.tools.Tool;
import org.opensearch.ml.common.transport.mcpserver.requests.McpToolBaseInput;
import org.opensearch.ml.common.utils.StringUtils;
import org.opensearch.ml.rest.mcpserver.ToolFactoryWrapper;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.core.action.ActionListener;

import com.google.common.collect.ImmutableMap;

import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.log4j.Log4j2;
import reactor.core.publisher.Mono;

/**
 * Helper for creating stateless MCP tool specifications.
 * This replicates McpToolsHelper behavior but for stateless servers.
 */
@Log4j2
public class McpStatelessToolsHelper {

    private final ToolFactoryWrapper toolFactoryWrapper;
    private final ThreadPool threadPool;
    private final McpToolsHelper mcpToolsHelper;

    // Track tools in memory (similar to McpAsyncServerHolder.IN_MEMORY_MCP_TOOLS)
    private final Map<String, Long> inMemoryTools = new ConcurrentHashMap<>();

    private static final int SYNC_MCP_TOOLS_JOB_INTERVAL = 10;

    public McpStatelessToolsHelper(ToolFactoryWrapper toolFactoryWrapper, ThreadPool threadPool, McpToolsHelper mcpToolsHelper) {
        this.toolFactoryWrapper = toolFactoryWrapper;
        this.threadPool = threadPool;
        this.mcpToolsHelper = mcpToolsHelper;
    }

    /**
     * Create stateless MCP tool specification from existing tool definition.
     * This replicates the exact logic from McpToolsHelper.createToolSpecification()
     */
    public McpStatelessServerFeatures.AsyncToolSpecification createStatelessToolSpecification(McpToolBaseInput tool) {
        String toolName = Optional.ofNullable(tool.getName()).orElse(tool.getType());
        Tool.Factory factory = toolFactoryWrapper.getToolsFactories().get(tool.getType());
        if (factory == null) {
            throw new RuntimeException("Failed to find tool factory for tool type: " + tool.getType());
        }

        Tool actualTool = factory.create(Optional.ofNullable(tool.getParameters()).orElse(ImmutableMap.of()));

        // MCP server doesn't allow null schema - same logic as McpToolsHelper
        String schema = Optional
                .ofNullable(tool.getAttributes())
                .map(x -> StringUtils.gson.toJson(x.get(CommonValue.TOOL_INPUT_SCHEMA_FIELD)))
                .orElse(
                        Optional.ofNullable(actualTool.getAttributes()).map(x -> (String) x.get(CommonValue.TOOL_INPUT_SCHEMA_FIELD)).orElse("{}")
                );

        String description = Optional.ofNullable(tool.getDescription()).orElse(factory.getDefaultDescription());

        return new McpStatelessServerFeatures.AsyncToolSpecification(
                new McpSchema.Tool(toolName, String.valueOf(description), schema),
                (ctx, request) -> Mono.create(sink -> {
                    // ✅ EXACT SAME EXECUTION LOGIC as McpToolsHelper
                    ActionListener<String> actionListener = ActionListener
                            .wrap(r -> sink.success(new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(r)), false)),
                                    e -> {
                                        log.error("Failed to execute tool, tool name: {}", toolName, e);
                                        sink.error(e);
                                    });

                    // ✅ EXACT SAME actualTool.run() CALL
                    actualTool.run(StringUtils.getParameterMap(request.arguments()), actionListener);
                })
        );
    }

    /**
     * Get the tool factory wrapper for external use
     */
    public ToolFactoryWrapper getToolFactoryWrapper() {
        return toolFactoryWrapper;
    }

    /**
     * Track tool in memory (similar to McpAsyncServerHolder.IN_MEMORY_MCP_TOOLS)
     */
    public void trackTool(String toolName, long version) {
        inMemoryTools.put(toolName, version);
    }

    /**
     * Check if tool exists in memory
     */
    public boolean hasTool(String toolName) {
        return inMemoryTools.containsKey(toolName);
    }

    /**
     * Get tool version from memory
     */
    public Long getToolVersion(String toolName) {
        return inMemoryTools.get(toolName);
    }

    /**
     * Start sync job for auto-reloading tools
     */
    public void startSyncMcpToolsJob() {
        ActionListener<Boolean> listener = ActionListener
                .wrap(r -> { log.debug("Auto reload mcp tools schedule job run successfully!"); }, e -> {
                    log.error(e.getMessage(), e);
                });
        threadPool
                .schedule(() -> autoLoadAllMcpTools(listener), TimeValue.timeValueSeconds(SYNC_MCP_TOOLS_JOB_INTERVAL),
                        org.opensearch.ml.plugin.MachineLearningPlugin.GENERAL_THREAD_POOL);
    }

    /**
     * Auto-reload all MCP tools (similar to McpToolsHelper.autoLoadAllMcpTools)
     */
    public void autoLoadAllMcpTools(ActionListener<Boolean> listener) {
        try {
            // Use the existing searchAllTools method from mcpToolsHelper
            // This will reload tools from the system index and update our in-memory tracking
            mcpToolsHelper.searchAllTools(new org.opensearch.core.action.ActionListener<List<org.opensearch.ml.common.transport.mcpserver.requests.register.McpToolRegisterInput>>() {
                @Override
                public void onResponse(List<org.opensearch.ml.common.transport.mcpserver.requests.register.McpToolRegisterInput> tools) {
                    try {
                        // Update our in-memory tracking
                        tools.forEach(tool -> {
                            String toolName = Optional.ofNullable(tool.getName()).orElse(tool.getType());
                            // For now, just track with current timestamp
                            // In a full implementation, you might want to track version numbers
                            trackTool(toolName, System.currentTimeMillis());
                        });
                        
                        log.debug("Successfully reloaded {} MCP tools for stateless server", tools.size());
                        listener.onResponse(true);
                    } catch (Exception e) {
                        log.error("Failed to process reloaded tools", e);
                        listener.onFailure(e);
                    }
                }

                @Override
                public void onFailure(Exception e) {
                    log.error("Failed to reload MCP tools for stateless server", e);
                    listener.onFailure(e);
                }
            });
        } catch (Exception e) {
            log.error("Failed to auto-reload MCP tools for stateless server", e);
            listener.onFailure(e);
        }
    }
}