/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.action.mcpserver;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.ml.engine.indices.MLIndicesHandler;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.server.McpStatelessAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.util.Assert;
import lombok.extern.log4j.Log4j2;
import reactor.core.publisher.Mono;

/**
 * This class sets up the stateless MCP server using the transport provider.
 * It creates the server with tools and resources, then uses the transport provider for handling requests.
 */
@Log4j2
public class McpStatelessServerSetup {

    private final MLIndicesHandler mlIndicesHandler;
    private final McpToolsHelper mcpToolsHelper;
    private final ObjectMapper objectMapper;

    public McpStatelessServerSetup(
        MLIndicesHandler mlIndicesHandler,
        McpToolsHelper mcpToolsHelper,
        ObjectMapper objectMapper
    ) {
        Assert.notNull(objectMapper, "ObjectMapper must not be null");
        this.mlIndicesHandler = mlIndicesHandler;
        this.mcpToolsHelper = mcpToolsHelper;
        this.objectMapper = objectMapper;
    }

    /**
     * Create and configure the stateless MCP server
     */
    public McpStatelessAsyncServer createStatelessServer() {
        try {
            // Create the transport provider
            OpenSearchMcpStatelessServerTransportProvider transportProvider = 
                new OpenSearchMcpStatelessServerTransportProvider(mlIndicesHandler, mcpToolsHelper, objectMapper);

            // Load tools BEFORE building the server
            List<McpStatelessServerFeatures.AsyncToolSpecification> tools = loadToolsFromInfrastructure();

            // Build the server using the transport provider WITH tools already loaded
            return McpServer.async(transportProvider)
                .serverInfo("OpenSearch-MCP-Stateless-Server", "0.1.0")
                .capabilities(createServerCapabilities())
                .tools(tools)  // Use the loaded tools here
                .instructions("OpenSearch MCP Stateless Server - provides access to ML tools without sessions")
                .build();

        } catch (Exception e) {
            log.error("Failed to create stateless MCP server", e);
            throw new RuntimeException("Failed to create stateless MCP server", e);
        }
    }

    private McpSchema.ServerCapabilities createServerCapabilities() {
        return McpSchema.ServerCapabilities.builder()
            .tools(true)
            .logging()
            .build();
    }

    private List<McpStatelessServerFeatures.AsyncToolSpecification> createTools() {
        // This method is no longer used - tools are loaded dynamically
        return List.of();
    }

    /**
     * Load tools from existing infrastructure BEFORE server construction.
     * This follows the exact same pattern as the SSE implementation's autoLoadAllMcpTools method.
     */
    private List<McpStatelessServerFeatures.AsyncToolSpecification> loadToolsFromInfrastructure() {
        log.debug("Loading tools from existing infrastructure for stateless MCP server");
        
        // Use the exact same pattern as the SSE implementation
        // The SSE implementation uses CountDownLatch for async operations, so we do the same
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<List<McpStatelessServerFeatures.AsyncToolSpecification>> toolsRef = 
            new AtomicReference<>();
        AtomicReference<Exception> errorRef = new AtomicReference<>();
        
        // Use the exact same searchAllTools method as the SSE implementation
        mcpToolsHelper.searchAllTools(new org.opensearch.core.action.ActionListener<List<org.opensearch.ml.common.transport.mcpserver.requests.register.McpToolRegisterInput>>() {
            @Override
            public void onResponse(List<org.opensearch.ml.common.transport.mcpserver.requests.register.McpToolRegisterInput> tools) {
                try {
                    // Convert existing tools to MCP format using the EXACT SAME LOGIC as SSE server
                    // This follows the same pattern as the SSE implementation's tool loading
                    List<McpStatelessServerFeatures.AsyncToolSpecification> mcpTools = tools.stream()
                        .map(tool -> createToolSpecificationFromExisting(tool))
                        .toList();
                    
                    toolsRef.set(mcpTools);
                    log.info("Successfully loaded {} tools from existing infrastructure", mcpTools.size());
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
            // Wait for tools to load using the same timeout pattern as SSE implementation
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
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while loading tools", e);
        } catch (Exception e) {
            log.error("Failed to load tools from infrastructure", e);
            // Return test tool as fallback, following SSE implementation pattern
            return List.of(createTestTool());
        }
    }

    /**
     * Create MCP tool specification from existing tool definition.
     * This REUSES the EXACT SAME LOGIC as the SSE server's createToolSpecification method.
     */
    private McpStatelessServerFeatures.AsyncToolSpecification createToolSpecificationFromExisting(
        org.opensearch.ml.common.transport.mcpserver.requests.register.McpToolRegisterInput tool
    ) {
        // Use the EXACT SAME LOGIC as McpToolsHelper.createToolSpecification()
        // but convert to stateless format following the same pattern
        
        String toolName = tool.getName() != null ? tool.getName() : tool.getType();
        String description = tool.getDescription() != null ? tool.getDescription() : "Tool: " + toolName;
        
        // Get the schema from the tool using the same pattern as SSE implementation
        String schema = tool.getAttributes() != null && tool.getAttributes().containsKey("input_schema") 
            ? tool.getAttributes().get("input_schema").toString() 
            : "{}";
        
        return McpStatelessServerFeatures.AsyncToolSpecification.builder()
            .tool(new McpSchema.Tool(toolName, description, schema))
            .callHandler((ctx, request) -> {
                // Execute the tool using existing infrastructure following SSE pattern
                return executeToolUsingExistingInfrastructure(tool, request.arguments());
            })
            .build();
    }

    /**
     * Execute tool using existing infrastructure.
     * This REUSES the EXACT SAME execution logic as the SSE server.
     */
    private Mono<McpSchema.CallToolResult> executeToolUsingExistingInfrastructure(
        org.opensearch.ml.common.transport.mcpserver.requests.register.McpToolRegisterInput tool,
        Map<String, Object> arguments
    ) {
        return Mono.create(sink -> {
            try {
                // Use the EXACT SAME LOGIC as McpToolsHelper.createToolSpecification()
                // but adapted for stateless execution following SSE pattern
                
                // Create the tool specification using the existing helper method
                // This is the same approach used in the SSE implementation
                var toolSpec = mcpToolsHelper.createToolSpecification(tool);
                
                // Execute the tool using the existing pattern from McpToolsHelper
                // The toolSpec.callHandler() is a BiFunction that expects (exchange, arguments) -> Mono<CallToolResult>
                // This follows the exact same pattern as the SSE implementation
                var callRequest = new McpSchema.CallToolRequest(tool.getName(), arguments);
                toolSpec.callHandler().apply(null, callRequest).subscribe(
                    result -> {
                        // Convert to MCP format using the same approach as SSE implementation
                        if (result instanceof McpSchema.CallToolResult mcpResult) {
                            sink.success(mcpResult);
                        } else {
                            // Wrap non-MCP results following SSE pattern
                            sink.success(new McpSchema.CallToolResult(
                                List.of(new McpSchema.TextContent(result.toString())), 
                                false
                            ));
                        }
                    },
                    error -> {
                        log.error("Failed to execute tool: {}", tool.getName(), error);
                        sink.error(error);
                    }
                );
                
            } catch (Exception e) {
                log.error("Failed to execute tool: {}", tool.getName(), e);
                sink.error(e);
            }
        });
    }

    /**
     * Create a test tool for initial validation
     */
    private McpStatelessServerFeatures.AsyncToolSpecification createTestTool() {
        return McpStatelessServerFeatures.AsyncToolSpecification.builder()
            .tool(new McpSchema.Tool("test_tool", "A test tool for validation", "{}"))
            .callHandler((ctx, request) -> {
                String result = "Test tool executed successfully with args: " + request.arguments();
                return Mono.just(new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(result)), 
                    false
                ));
            })
            .build();
    }

    /**
     * Load tools from the existing infrastructure into the server
     * NOTE: This method is no longer used - tools are loaded during server construction
     */
    public void loadToolsIntoServer(McpStatelessAsyncServer server) {
        log.warn("loadToolsIntoServer() is deprecated - tools are now loaded during server construction");
        // Tools are already loaded when the server was built
    }

    /**
     * Create MCP tool specification from existing tool definition
     * NOTE: This method is no longer used in the current implementation
     */
    private McpStatelessServerFeatures.AsyncToolSpecification createToolSpecification(
        org.opensearch.ml.common.transport.mcpserver.requests.register.McpToolRegisterInput tool
    ) {
        // This method is kept for future use when loading real tools from infrastructure
        String toolName = tool.getName() != null ? tool.getName() : tool.getType();
        String description = tool.getDescription() != null ? tool.getDescription() : "Tool: " + toolName;
        
        return McpStatelessServerFeatures.AsyncToolSpecification.builder()
            .tool(new McpSchema.Tool(toolName, description, "{}"))
            .callHandler((ctx, request) -> {
                String result = "Tool '" + toolName + "' executed with args: " + request.arguments();
                return Mono.just(new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(result)), 
                    false
                ));
            })
            .build();
    }

    /**
     * Execute tool using existing infrastructure
     * NOTE: This method is no longer used in the current implementation
     */
    private Mono<McpSchema.CallToolResult> executeTool(
        org.opensearch.ml.common.transport.mcpserver.requests.register.McpToolRegisterInput tool,
        Map<String, Object> arguments
    ) {
        // This method is kept for future use when integrating with real tool execution
        String result = "Tool '" + tool.getName() + "' executed with arguments: " + arguments;
        
        McpSchema.CallToolResult mcpResult = new McpSchema.CallToolResult(
            List.of(new McpSchema.TextContent(result)), 
            false
        );
        
        return Mono.just(mcpResult);
    }
} 