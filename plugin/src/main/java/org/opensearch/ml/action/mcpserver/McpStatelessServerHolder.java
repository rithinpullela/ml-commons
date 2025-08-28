/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.action.mcpserver;

import org.opensearch.ml.engine.indices.MLIndicesHandler;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.server.McpStatelessAsyncServer;
import lombok.extern.log4j.Log4j2;

/**
 * This class holds the singleton instance of the stateless MCP server.
 * It provides access to the stateless server instance alongside the existing session-based server.
 */
@Log4j2
public class McpStatelessServerHolder {

    private static volatile MLIndicesHandler mlIndicesHandler;
    private static volatile McpToolsHelper mcpToolsHelper;
    private static volatile McpStatelessServerSetup serverSetup;
    private static volatile McpStatelessAsyncServer statelessServer;

    public static void init(MLIndicesHandler mlIndicesHandler, McpToolsHelper mcpToolsHelper) {
        McpStatelessServerHolder.mlIndicesHandler = mlIndicesHandler;
        McpStatelessServerHolder.mcpToolsHelper = mcpToolsHelper;
    }

    public static McpStatelessAsyncServer getStatelessServerInstance() {
        if (statelessServer != null) {
            return statelessServer;
        }
        synchronized (McpStatelessServerHolder.class) {
            if (statelessServer != null) {
                return statelessServer;
            }
            
            // Create server setup and build the server
            serverSetup = new McpStatelessServerSetup(
                McpStatelessServerHolder.mlIndicesHandler,
                McpStatelessServerHolder.mcpToolsHelper,
                new ObjectMapper()
            );
            
            statelessServer = serverSetup.createStatelessServer();

            // Tools are already loaded during server construction
            log.info("Stateless MCP server created and initialized");
            return statelessServer;
        }
    }

    /**
     * Check if stateless server is available
     */
    public static boolean isStatelessServerAvailable() {
        return statelessServer != null;
    }

    /**
     * Get the server setup instance
     */
    public static McpStatelessServerSetup getServerSetup() {
        return serverSetup;
    }

    /**
     * Shutdown the stateless server
     */
    public static void shutdown() {
        if (statelessServer != null) {
            try {
                statelessServer.close();
                log.info("Stateless MCP server shutdown successfully");
            } catch (Exception e) {
                log.error("Error during stateless MCP server shutdown", e);
            }
            statelessServer = null;
        }
    }
} 