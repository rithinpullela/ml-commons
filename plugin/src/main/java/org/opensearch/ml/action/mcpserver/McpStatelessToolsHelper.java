/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.action.mcpserver;

import static org.opensearch.common.xcontent.json.JsonXContent.jsonXContent;
import static org.opensearch.core.xcontent.XContentParserUtils.ensureExpectedToken;
import static org.opensearch.ml.plugin.MachineLearningPlugin.GENERAL_THREAD_POOL;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.opensearch.OpenSearchException;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.common.collect.Tuple;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.common.xcontent.LoggingDeprecationHandler;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.index.query.MatchAllQueryBuilder;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.ml.common.CommonValue;
import org.opensearch.ml.common.MLIndex;
import org.opensearch.ml.common.spi.tools.Tool;
import org.opensearch.ml.common.transport.mcpserver.requests.McpToolBaseInput;
import org.opensearch.ml.common.transport.mcpserver.requests.register.McpToolRegisterInput;
import org.opensearch.ml.common.utils.StringUtils;
import org.opensearch.ml.rest.mcpserver.ToolFactoryWrapper;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.client.Client;

import com.google.common.collect.ImmutableMap;

import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.log4j.Log4j2;
import reactor.core.publisher.Mono;

/**
 * Helper for creating stateless MCP tool specifications.
 * This is completely independent of McpToolsHelper with duplicated code.
 */
@Log4j2
public class McpStatelessToolsHelper {
    public static final int MAX_TOOL_NUMBER = 1000;
    private static final int SYNC_MCP_TOOLS_JOB_INTERVAL = 10;

    private final Client client;
    private final ThreadPool threadPool;
    private final ToolFactoryWrapper toolFactoryWrapper;

    // Track tools in memory (similar to McpAsyncServerHolder.IN_MEMORY_MCP_TOOLS)
    private final Map<String, Long> inMemoryTools = new ConcurrentHashMap<>();

    public McpStatelessToolsHelper(Client client, ThreadPool threadPool, ToolFactoryWrapper toolFactoryWrapper) {
        this.client = client;
        this.threadPool = threadPool;
        this.toolFactoryWrapper = toolFactoryWrapper;
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
     * Get the thread pool for external use
     */
    public ThreadPool getThreadPool() {
        return threadPool;
    }

    /**
     * Track tool in memory (similar to McpAsyncServerHolder.IN_MEMORY_MCP_TOOLS)
     */
    public void trackTool(String toolName, long version) {
        inMemoryTools.put(toolName, version);
    }

    /**
     * Get tracked tools in memory
     */
    public Map<String, Long> getInMemoryTools() {
        return inMemoryTools;
    }

    /**
     * Search all tools from the MCP tools index
     * This duplicates the logic from McpToolsHelper.searchAllTools()
     */
    public void searchAllTools(ActionListener<List<McpToolRegisterInput>> listener) {
        try (ThreadContext.StoredContext context = client.threadPool().getThreadContext().stashContext()) {
            ActionListener<List<McpToolRegisterInput>> restoreListener = ActionListener.runBefore(listener, context::restore);
            ActionListener<SearchResponse> actionListener = ActionListener.wrap(r -> {
                List<McpToolRegisterInput> mcpTools = new ArrayList<>();
                Arrays.stream(Objects.requireNonNull(r.getHits().getHits())).forEach(x -> {
                    try {
                        McpToolRegisterInput mcpTool = parseMcpTool(x.getSourceAsString());
                        mcpTools.add(mcpTool);
                    } catch (IOException e) {
                        listener.onFailure(e);
                    }
                });
                restoreListener.onResponse(mcpTools);
            }, e -> {
                String errMsg = String.format(Locale.ROOT, "Failed to search mcp tools index with error: %s", e.getMessage());
                log.error(errMsg, e);
                restoreListener.onFailure(new OpenSearchException(errMsg));
            });

            client.search(buildSearchRequest(), actionListener);
        } catch (Exception e) {
            log.error("Failed to search mcp tools index", e);
            listener.onFailure(e);
        }
    }

    /**
     * Start the sync job for auto-reloading MCP tools
     * This duplicates the logic from McpToolsHelper.startSyncMcpToolsJob()
     */
    public void startSyncMcpToolsJob() {
        ActionListener<Boolean> listener = ActionListener
            .wrap(r -> { log.debug("Auto reload mcp tools schedule job run successfully!"); }, e -> {
                log.error(e.getMessage(), e);
            });
        threadPool
            .schedule(() -> autoLoadAllMcpTools(listener), TimeValue.timeValueSeconds(SYNC_MCP_TOOLS_JOB_INTERVAL), GENERAL_THREAD_POOL);
    }

    /**
     * Auto-load all MCP tools from the index
     * This duplicates the logic from McpToolsHelper.autoLoadAllMcpTools()
     */
    public void autoLoadAllMcpTools(ActionListener<Boolean> listener) {
        try (ThreadContext.StoredContext context = client.threadPool().getThreadContext().stashContext()) {
            ActionListener<Boolean> restoreListener = ActionListener.runBefore(listener, context::restore);
            ActionListener<List<McpToolRegisterInput>> searchListener = ActionListener.wrap(tools -> {
                try {
                    tools.forEach(tool -> {
                        String toolName = Optional.ofNullable(tool.getName()).orElse(tool.getType());
                        trackTool(toolName, System.currentTimeMillis());
                    });
                    log.debug("Successfully reloaded {} MCP tools for stateless server", tools.size());
                    restoreListener.onResponse(true);
                } catch (Exception e) {
                    log.error("Failed to process reloaded tools", e);
                    restoreListener.onFailure(e);
                }
            }, e -> {
                log.error("Failed to reload MCP tools for stateless server", e);
                restoreListener.onFailure(e);
            });
            searchAllTools(searchListener);
        } catch (Exception e) {
            log.error("Failed to auto-reload MCP tools for stateless server", e);
            listener.onFailure(e);
        }
    }

    /**
     * Build search request for all tools
     * This duplicates the logic from McpToolsHelper.buildSearchRequest()
     */
    private SearchRequest buildSearchRequest() {
        SearchRequest searchRequest = new SearchRequest();
        searchRequest.indices(MLIndex.MCP_TOOLS.getIndexName());

        MatchAllQueryBuilder queryBuilder = QueryBuilders.matchAllQuery();
        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        searchSourceBuilder.version(true);
        searchSourceBuilder.query(queryBuilder);
        searchRequest.source(searchSourceBuilder);
        searchRequest.source().size(MAX_TOOL_NUMBER);
        return searchRequest;
    }

    /**
     * Parse MCP tool from JSON string
     * This duplicates the logic from McpToolsHelper.parseMcpTool()
     */
    private McpToolRegisterInput parseMcpTool(String input) throws IOException {
        try (XContentParser parser = jsonXContent.createParser(NamedXContentRegistry.EMPTY, LoggingDeprecationHandler.INSTANCE, input)) {
            ensureExpectedToken(XContentParser.Token.START_OBJECT, parser.nextToken(), parser);
            return McpToolRegisterInput.parse(parser);
        } catch (IOException e) {
            log.error("Failed to parse mcp tools configuration: {}", input);
            throw e;
        }
    }
}