/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.action.mcpserver;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.ml.engine.indices.MLIndicesHandler;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.server.McpStatelessAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import lombok.extern.log4j.Log4j2;
import reactor.core.publisher.Mono;

/**
 * Sets up the stateless MCP server with tools loaded from existing infrastructure.
 */
@Log4j2
public class McpStatelessServerSetup {

    private final MLIndicesHandler mlIndicesHandler;
    private final McpToolsHelper mcpToolsHelper;
    private final McpStatelessToolsHelper statelessToolsHelper;
    private final ObjectMapper objectMapper;
    private OpenSearchMcpStatelessServerTransportProvider transportProvider;

    public McpStatelessServerSetup(
            MLIndicesHandler mlIndicesHandler,
            McpToolsHelper mcpToolsHelper,
            ObjectMapper objectMapper
    ) {
        this.mlIndicesHandler = mlIndicesHandler;
        this.mcpToolsHelper = mcpToolsHelper;
        this.statelessToolsHelper = new McpStatelessToolsHelper(
                mcpToolsHelper.getToolFactoryWrapper(),
                mcpToolsHelper.getThreadPool(),
                mcpToolsHelper);
        this.objectMapper = objectMapper;
    }

    /**
     * Create and configure the stateless MCP server
     */
    public McpStatelessAsyncServer createStatelessServer() {
        try {
            log.info("Starting to create stateless MCP server...");
            
            // Create the transport provider
            this.transportProvider = new OpenSearchMcpStatelessServerTransportProvider(
                    mlIndicesHandler, mcpToolsHelper, objectMapper);
            log.info("Transport provider created successfully");

            // Load tools from existing infrastructure
            log.info("Loading tools from existing infrastructure...");
            List<McpStatelessServerFeatures.AsyncToolSpecification> tools = loadToolsFromInfrastructure();
            log.info("Loaded {} tools from infrastructure", tools.size());

            // Build the server using the transport provider AND add the tools
            // The MCP framework will automatically call setMcpHandler on the transport provider
            log.info("Building MCP server with {} tools...", tools.size());
            McpStatelessAsyncServer server = McpServer.async(transportProvider)
                    .serverInfo("OpenSearch-MCP-Stateless-Server", "0.1.0")
                    .capabilities(createServerCapabilities())
                    .tools(tools)
                    .instructions("OpenSearch MCP Stateless Server - provides access to ML tools without sessions")
                    .build();

            log.info("Stateless MCP server created and initialized with {} tools", tools.size());
            
            // Verify that the transport provider now has a handler
            if (transportProvider.isHandlerReady()) {
                log.info("Transport provider handler is ready - server initialization successful");
            } else {
                log.warn("Transport provider handler is not ready - this may indicate an issue");
            }
            
            return server;

        } catch (Exception e) {
            log.error("Failed to create stateless MCP server", e);
            throw new RuntimeException("Failed to create stateless MCP server", e);
        }
    }

    /**
     * Get the transport provider for external use
     */
    public OpenSearchMcpStatelessServerTransportProvider getTransportProvider() {
        return transportProvider;
    }

    private McpSchema.ServerCapabilities createServerCapabilities() {
        return McpSchema.ServerCapabilities.builder()
                .tools(true)
                .logging()
                .build();
    }

    /**
     * Load tools from existing infrastructure using the same logic as SSE server
     */
    private List<McpStatelessServerFeatures.AsyncToolSpecification> loadToolsFromInfrastructure() {
        log.debug("Loading tools from existing infrastructure for stateless MCP server");

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<List<McpStatelessServerFeatures.AsyncToolSpecification>> toolsRef =
                new AtomicReference<>();
        AtomicReference<Exception> errorRef = new AtomicReference<>();

        // Use the existing searchAllTools method - same as SSE server
        mcpToolsHelper.searchAllTools(new org.opensearch.core.action.ActionListener<List<org.opensearch.ml.common.transport.mcpserver.requests.register.McpToolRegisterInput>>() {
            @Override
            public void onResponse(List<org.opensearch.ml.common.transport.mcpserver.requests.register.McpToolRegisterInput> tools) {
                try {
                    // Convert existing tools to STATELESS MCP format using our new helper
                    List<McpStatelessServerFeatures.AsyncToolSpecification> mcpTools = tools.stream()
                            .map(tool -> statelessToolsHelper.createStatelessToolSpecification(tool))
                            .toList();

                    toolsRef.set(mcpTools);
                    log.info("Successfully loaded {} tools from existing infrastructure", mcpTools.size());

                    // Start sync job for auto-reloading
                    statelessToolsHelper.startSyncMcpToolsJob();

                } catch (Exception e) {
                    errorRef.set(e);
                    log.error("Failed to convert tools to MCP format", e);
                } finally {
                    latch.countDown();
                }
            }

            @Override
            public void onFailure(Exception e) {
                errorRef.set(e);
                log.error("Failed to load tools from infrastructure", e);
                latch.countDown();
            }
        });

        try {
            // Wait for tools to load
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new RuntimeException("Timeout waiting for tools to load");
            }

            if (errorRef.get() != null) {
                throw errorRef.get();
            }

            List<McpStatelessServerFeatures.AsyncToolSpecification> tools = toolsRef.get();
            if (tools == null || tools.isEmpty()) {
                log.warn("No tools loaded from infrastructure, creating test tool");
                return List.of(createTestTool());
            }

            return tools;

        } catch (Exception e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while loading tools", e);
        }
    }

    /**
     * Create a test tool for fallback
     */
    private McpStatelessServerFeatures.AsyncToolSpecification createTestTool() {
        // Use the same constructor pattern as McpStatelessServerFeatures.AsyncToolSpecification
        return new McpStatelessServerFeatures.AsyncToolSpecification(
                new McpSchema.Tool("test_tool", "A test tool for validation", "{}"),
                (ctx, request) -> {
                    String result = "Test tool executed successfully with args: " + request.arguments().toString();
                    return Mono.just(new McpSchema.CallToolResult(
                            List.of(new McpSchema.TextContent(result)),
                            false
                    ));
                }
        );
    }
}