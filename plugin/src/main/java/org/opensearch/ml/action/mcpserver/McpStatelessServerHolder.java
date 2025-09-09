/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.action.mcpserver;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.opensearch.rest.StreamingRestChannel;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessAsyncServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.log4j.Log4j2;

/**
 * Singleton holder for the stateless MCP server and transport provider.
 */
@Log4j2
public class McpStatelessServerHolder {

    public static Map<String, StreamingRestChannel> CHANNELS = new ConcurrentHashMap<>();
    public static Map<String, Long> IN_MEMORY_MCP_TOOLS = new ConcurrentHashMap<>();
    private static volatile McpStatelessToolsHelper statelessToolsHelper;
    private static volatile McpStatelessAsyncServer mcpStatelessAsyncServer;
    private static volatile OpenSearchMcpStatelessServerTransportProvider mcpStatelessServerTransportProvider;

    public static void init(McpStatelessToolsHelper statelessToolsHelper) {
        McpStatelessServerHolder.statelessToolsHelper = statelessToolsHelper;
    }

    private static McpStatelessAsyncServer createMcpStatelessServer(OpenSearchMcpStatelessServerTransportProvider serverTransport) {
        try {
            log.info("Starting to create stateless MCP server...");

            // Load tools from existing infrastructure
            log.info("Loading tools from existing infrastructure...");
            List<McpStatelessServerFeatures.AsyncToolSpecification> tools = statelessToolsHelper.loadToolsFromInfrastructure();
            log.info("Loaded {} tools from infrastructure", tools.size());

            McpSchema.ServerCapabilities serverCapabilities = McpSchema.ServerCapabilities.builder().tools(true).logging().build();
            // Build the server using the transport provider AND add the tools
            // The MCP framework will automatically call setMcpHandler on the transport provider
            log.info("Building MCP server with {} tools...", tools.size());
            McpStatelessAsyncServer server = McpServer
                .async(serverTransport)
                .serverInfo("OpenSearch-MCP-Stateless-Server", "0.1.0")
                .capabilities(serverCapabilities)
                .tools(tools)
                .instructions("OpenSearch MCP Stateless Server - provides access to ML tools without sessions")
                .build();

            log.info("Stateless MCP server created and initialized with {} tools", tools.size());

            // Verify that the transport provider now has a handler
            if (serverTransport.isHandlerReady()) {
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

    public static OpenSearchMcpStatelessServerTransportProvider getMcpStatelessServerTransportProvider() {
        if (mcpStatelessServerTransportProvider != null) {
            return mcpStatelessServerTransportProvider;
        }
        synchronized (McpStatelessServerHolder.class) {
            if (mcpStatelessServerTransportProvider != null) {
                return mcpStatelessServerTransportProvider;
            }
            mcpStatelessServerTransportProvider = new OpenSearchMcpStatelessServerTransportProvider(
                new ObjectMapper()
            );
            // initialize the server
            if (mcpStatelessAsyncServer == null) {
                mcpStatelessAsyncServer = createMcpStatelessServer(mcpStatelessServerTransportProvider);
            }
            return mcpStatelessServerTransportProvider;
        }
    }

    public static McpStatelessAsyncServer getMcpStatelessAsyncServerInstance() {
        if (mcpStatelessAsyncServer == null) {
            synchronized (McpAsyncServerHolder.class) {
                getMcpStatelessServerTransportProvider();
            }
        }
        return mcpStatelessAsyncServer;
    }

}
