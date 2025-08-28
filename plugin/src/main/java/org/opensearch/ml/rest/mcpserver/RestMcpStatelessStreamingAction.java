/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.rest.mcpserver;

import static org.opensearch.ml.common.settings.MLCommonsSettings.ML_COMMONS_MCP_SERVER_DISABLED_MESSAGE;
import static org.opensearch.rest.RestRequest.Method.POST;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.opensearch.OpenSearchException;
import org.opensearch.common.annotation.ExperimentalApi;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.ml.action.mcpserver.McpStatelessServerHolder;
import org.opensearch.ml.common.settings.MLFeatureEnabledSetting;
import org.opensearch.rest.BaseRestHandler;
import org.opensearch.rest.BytesRestResponse;
import org.opensearch.rest.RestChannel;
import org.opensearch.rest.RestRequest;
import org.opensearch.transport.client.node.NodeClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

import lombok.extern.log4j.Log4j2;

/**
 * This class handles stateless MCP requests via HTTP POST.
 * It provides direct access to MCP tools without session management, following the same pattern
 * as the existing SSE-based MCP implementation.
 */
@Log4j2
@ExperimentalApi
public class RestMcpStatelessStreamingAction extends BaseRestHandler {

    private static final String ML_STATELESS_MCP_ACTION = "ml_stateless_mcp_action";
    public static final String STATELESS_ENDPOINT = "/_plugins/_ml/mcp/stream";

    private final MLFeatureEnabledSetting mlFeatureEnabledSetting;
    private final ObjectMapper objectMapper;

    public RestMcpStatelessStreamingAction(MLFeatureEnabledSetting mlFeatureEnabledSetting) {
        this.mlFeatureEnabledSetting = mlFeatureEnabledSetting;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String getName() {
        return ML_STATELESS_MCP_ACTION;
    }

    @Override
    public List<Route> routes() {
        return ImmutableList.of(new Route(POST, STATELESS_ENDPOINT));
    }

    @Override
    public RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) throws IOException {
        if (!mlFeatureEnabledSetting.isMcpServerEnabled()) {
            throw new OpenSearchException(ML_COMMONS_MCP_SERVER_DISABLED_MESSAGE);
        }

        return channel -> {
            try {
                // Read request body using the same pattern as SSE implementation
                String requestBody = request.content().utf8ToString();
                log.debug("Received stateless MCP request: {}", requestBody);

                // Get the stateless server instance following the same pattern as SSE implementation
                var statelessServer = McpStatelessServerHolder.getStatelessServerInstance();
                if (statelessServer == null) {
                    sendErrorResponse(channel, "1", "Stateless MCP server not available");
                    return;
                }

                // Get the transport provider and handle the request using the same pattern as SSE
                var transportProvider = statelessServer.getTransportProvider();
                if (transportProvider == null || !transportProvider.isHandlerReady()) {
                    sendErrorResponse(channel, "1", "MCP handler not ready");
                    return;
                }

                // Handle the request using the MCP framework following the same pattern as SSE implementation
                transportProvider.handleRequest(requestBody)
                    .subscribe(
                        response -> {
                            try {
                                String responseJson = objectMapper.writeValueAsString(response);
                                channel.sendResponse(new BytesRestResponse(RestStatus.OK, "application/json", responseJson));
                            } catch (Exception e) {
                                log.error("Failed to send response", e);
                                sendErrorResponse(channel, "1", "Failed to send response: " + e.getMessage());
                            }
                        },
                        error -> {
                            log.error("Failed to handle MCP request", error);
                            sendErrorResponse(channel, "1", "Internal server error: " + error.getMessage());
                        }
                    );

            } catch (Exception e) {
                log.error("Failed to handle stateless MCP request", e);
                sendErrorResponse(channel, "1", "Internal server error: " + e.getMessage());
            }
        };
    }

    /**
     * Send error response following the same pattern as SSE implementation
     */
    private void sendErrorResponse(RestChannel channel, String id, String errorMessage) {
        try {
            Map<String, Object> response = Map.of(
                "jsonrpc", "2.0",
                "id", id,
                "error", Map.of(
                    "code", -32603,
                    "message", errorMessage
                )
            );

            String responseJson = objectMapper.writeValueAsString(response);
            channel.sendResponse(new BytesRestResponse(RestStatus.OK, "application/json", responseJson));
        } catch (Exception e) {
            log.error("Failed to send error response", e);
        }
    }
} 