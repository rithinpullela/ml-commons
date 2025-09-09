/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.action.mcpserver;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.opensearch.core.action.ActionListener;
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

            McpSchema.ServerCapabilities serverCapabilities = McpSchema.ServerCapabilities.builder()
                .tools(true)
                .logging()
                .resources(false, false)  // We don't support resources
                .prompts(false)           // We don't support prompts
                .build();
            // Build the server using the transport provider WITHOUT pre-loaded tools
            // Tools will be loaded dynamically via the sync job, just like the SSE server
            log.info("Building MCP server without pre-loaded tools (dynamic loading)...");
            McpStatelessAsyncServer server = McpServer
                .async(serverTransport)
                .serverInfo("OpenSearch-MCP-Stateless-Server", "0.1.0")
                .capabilities(serverCapabilities)
                .instructions("OpenSearch MCP Stateless Server - provides access to ML tools without sessions")
                .build();

            log.info("Stateless MCP server created successfully");

            // Start the sync job for dynamic tool loading (same as SSE server)
            statelessToolsHelper.startSyncMcpToolsJob();
            log.info("Started dynamic tool loading sync job");
            
            // Also run the initial tool loading immediately (don't wait for the first scheduled run)
            statelessToolsHelper.autoLoadAllMcpTools(ActionListener.wrap(
                success -> log.info("Initial tool loading completed successfully"),
                error -> log.error("Initial tool loading failed", error)
            ));

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
            mcpStatelessServerTransportProvider = new OpenSearchMcpStatelessServerTransportProvider(new ObjectMapper());
            // initialize the server
            if (mcpStatelessAsyncServer == null) {
                mcpStatelessAsyncServer = createMcpStatelessServer(mcpStatelessServerTransportProvider);
            }
            return mcpStatelessServerTransportProvider;
        }
    }

    public static McpStatelessAsyncServer getMcpStatelessAsyncServerInstance() {
        if (mcpStatelessAsyncServer == null) {
            synchronized (McpStatelessServerHolder.class) {
                getMcpStatelessServerTransportProvider();
            }
        }
        return mcpStatelessAsyncServer;
    }

}
