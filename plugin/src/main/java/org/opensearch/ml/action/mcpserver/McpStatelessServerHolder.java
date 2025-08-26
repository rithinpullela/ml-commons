/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.action.mcpserver;

import org.opensearch.ml.engine.indices.MLIndicesHandler;
import org.opensearch.transport.client.Client;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.server.McpStatelessAsyncServer;
import lombok.extern.log4j.Log4j2;

/**
 * Singleton holder for the stateless MCP server and transport provider.
 */
@Log4j2
public class McpStatelessServerHolder {

    private static volatile MLIndicesHandler mlIndicesHandler;
    private static volatile McpToolsHelper mcpToolsHelper;
    private static volatile Client client;
    private static volatile McpStatelessServerSetup serverSetup;
    private static volatile McpStatelessAsyncServer statelessServer;
    private static volatile OpenSearchMcpStatelessServerTransportProvider transportProvider;

    public static void init(MLIndicesHandler mlIndicesHandler, McpToolsHelper mcpToolsHelper, Client client) {
        McpStatelessServerHolder.mlIndicesHandler = mlIndicesHandler;
        McpStatelessServerHolder.mcpToolsHelper = mcpToolsHelper;
        McpStatelessServerHolder.client = client;
    }

    public static McpStatelessAsyncServer getStatelessServerInstance() {
        if (statelessServer != null) {
            log.debug("Returning existing stateless MCP server instance");
            return statelessServer;
        }
        synchronized (McpStatelessServerHolder.class) {
            if (statelessServer != null) {
                log.debug("Returning existing stateless MCP server instance (double-checked)");
                return statelessServer;
            }

            log.info("Creating new stateless MCP server instance...");
            
            // Create server setup and build the server
            serverSetup = new McpStatelessServerSetup(
                    mlIndicesHandler, mcpToolsHelper, new ObjectMapper(), client);
            log.info("Server setup created successfully");

            // Create the server first, which will initialize the transport provider
            statelessServer = serverSetup.createStatelessServer();
            log.info("Stateless MCP server created and initialized");

            // Now get the transport provider reference after it's been initialized
            transportProvider = serverSetup.getTransportProvider();
            log.info("Transport provider retrieved from setup after server initialization");

            return statelessServer;
        }
    }

    public static OpenSearchMcpStatelessServerTransportProvider getTransportProvider() {
        if (transportProvider != null) {
            return transportProvider;
        }
        synchronized (McpStatelessServerHolder.class) {
            if (transportProvider != null) {
                return transportProvider;
            }
            
            // Initialize the server if not already done
            getStatelessServerInstance();
            return transportProvider;
        }
    }

    public static boolean isStatelessServerAvailable() {
        return statelessServer != null;
    }

    public static void shutdown() {
        if (statelessServer != null) {
            try {
                statelessServer.close();
                log.info("Stateless MCP server shutdown successfully");
            } catch (Exception e) {
                log.error("Error during stateless MCP server shutdown", e);
            }
            statelessServer = null;
            transportProvider = null;
        }
    }
}