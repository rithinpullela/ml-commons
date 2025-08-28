/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.action.mcpserver;

import java.io.IOException;
import java.util.Map;

import org.opensearch.ml.engine.indices.MLIndicesHandler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.server.McpTransportContext;
import io.modelcontextprotocol.server.DefaultMcpTransportContext;
import io.modelcontextprotocol.util.Assert;
import lombok.extern.log4j.Log4j2;
import reactor.core.publisher.Mono;

/**
 * This class implements McpStatelessServerTransport for stateless HTTP requests.
 * It handles MCP requests directly without maintaining sessions, following the same pattern
 * as the existing SSE implementation.
 */
@Log4j2
public class OpenSearchMcpStatelessServerTransportProvider implements McpStatelessServerTransport {

    private final ObjectMapper objectMapper;
    private final MLIndicesHandler mlIndicesHandler;
    private final McpToolsHelper mcpToolsHelper;
    
    // This will be set by the MCP framework
    private McpStatelessServerHandler mcpHandler;

    public OpenSearchMcpStatelessServerTransportProvider(
        MLIndicesHandler mlIndicesHandler,
        McpToolsHelper mcpToolsHelper,
        ObjectMapper objectMapper
    ) {
        Assert.notNull(objectMapper, "ObjectMapper must not be null");
        this.mlIndicesHandler = mlIndicesHandler;
        this.mcpToolsHelper = mcpToolsHelper;
        this.objectMapper = objectMapper;
    }

    @Override
    public void setMcpHandler(McpStatelessServerHandler mcpHandler) {
        this.mcpHandler = mcpHandler;
        log.debug("MCP handler set for stateless transport provider");
    }

    @Override
    public Mono<Void> closeGracefully() {
        log.debug("Closing stateless MCP transport provider gracefully");
        return Mono.empty();
    }

    /**
     * Handle incoming MCP requests by delegating to the MCP framework.
     * This follows the same pattern as the SSE implementation's handleMessage method.
     */
    public Mono<McpSchema.JSONRPCMessage> handleRequest(String requestBody) {
        return Mono.just(requestBody).flatMap(body -> {
            try {
                // Parse the incoming request using the same pattern as SSE implementation
                McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(objectMapper, body);
                
                if (message instanceof McpSchema.JSONRPCRequest request) {
                    // Create transport context using the same approach as SSE
                    McpTransportContext transportContext = createTransportContext();
                    
                    // Delegate to the MCP framework handler
                    return mcpHandler.handleRequest(transportContext, request)
                        .map(response -> (McpSchema.JSONRPCMessage) response);
                } else if (message instanceof McpSchema.JSONRPCNotification notification) {
                    // Create transport context using the same approach as SSE
                    McpTransportContext transportContext = createTransportContext();
                    
                    // Handle notification
                    return mcpHandler.handleNotification(transportContext, notification)
                        .then(Mono.empty());
                } else {
                    return Mono.error(new RuntimeException("Unknown message type"));
                }
                
            } catch (IllegalArgumentException | IOException e) {
                log.error("Failed to deserialize message: {}", e.getMessage());
                return Mono.error(new RuntimeException("Invalid message format"));
            }
        }).onErrorResume(Mono::error);
    }

    /**
     * Create transport context following the same pattern as the SSE implementation.
     * This provides the necessary context for tool execution.
     */
    private McpTransportContext createTransportContext() {
        // Create transport context with the same approach as SSE implementation
        // The SSE implementation doesn't create custom transport contexts, so we use the default
        return new DefaultMcpTransportContext(
            "localhost", // remote address - same as SSE default
            "OpenSearch-MCP-Stateless-Client", // user agent - following SSE naming pattern
            "none" // authorization - following SSE pattern
        );
    }

    /**
     * Check if the MCP handler is ready
     */
    @VisibleForTesting
    public boolean isHandlerReady() {
        return mcpHandler != null;
    }
} 