/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.action.mcpserver;

import java.util.List;

import org.opensearch.ml.engine.indices.MLIndicesHandler;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessAsyncServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;

/**
 * Sets up the stateless MCP server with tools loaded from existing infrastructure.
 */
@Log4j2
public class McpStatelessServerSetup {

    private final MLIndicesHandler mlIndicesHandler;
    private final McpStatelessToolsHelper statelessToolsHelper;
    @Getter
    private OpenSearchMcpStatelessServerTransportProvider transportProvider;

    public McpStatelessServerSetup(MLIndicesHandler mlIndicesHandler, McpStatelessToolsHelper statelessToolsHelper) {
        this.mlIndicesHandler = mlIndicesHandler;
        this.statelessToolsHelper = statelessToolsHelper;
    }

    private McpSchema.ServerCapabilities createServerCapabilities() {
        return McpSchema.ServerCapabilities.builder().tools(true).logging().build();
    }

}
