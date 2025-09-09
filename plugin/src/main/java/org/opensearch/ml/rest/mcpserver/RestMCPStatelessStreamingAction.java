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
import org.opensearch.core.common.bytes.BytesArray;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.ml.action.mcpserver.McpStatelessServerHolder;
import org.opensearch.ml.common.settings.MLFeatureEnabledSetting;
import org.opensearch.rest.BaseRestHandler;
import org.opensearch.rest.BytesRestResponse;
import org.opensearch.rest.RestChannel;
import org.opensearch.rest.RestRequest;
import org.opensearch.transport.client.node.NodeClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.log4j.Log4j2;

@Log4j2
public class RestMCPStatelessStreamingAction extends BaseRestHandler {

    private static final String ML_STATELESS_MCP_ACTION = "ml_stateless_mcp_action";
    public static final String STATELESS_ENDPOINT = "/_plugins/_ml/mcp/stream";

    private final MLFeatureEnabledSetting mlFeatureEnabledSetting;
    private final ObjectMapper objectMapper;

    public RestMCPStatelessStreamingAction(MLFeatureEnabledSetting mlFeatureEnabledSetting) {
        this.mlFeatureEnabledSetting = mlFeatureEnabledSetting;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public List<Route> routes() {
        return List.of(new Route(POST, STATELESS_ENDPOINT));
    }

    @Override
    public String getName() {
        return ML_STATELESS_MCP_ACTION;
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) throws IOException {
        if (!mlFeatureEnabledSetting.isMcpServerEnabled()) {
            throw new OpenSearchException(ML_COMMONS_MCP_SERVER_DISABLED_MESSAGE);
        }

        return channel -> {
            try {
                final String requestBody = request.content().utf8ToString();
                log.info("Received stateless MCP request: {}", requestBody);

                var transportProvider = McpStatelessServerHolder.getMcpStatelessServerTransportProvider();
                if (transportProvider == null || !transportProvider.isHandlerReady()) {
                    log.error("MCP transport provider not ready - server may not be properly initialized");
                    sendErrorResponse(channel, "1", "MCP handler not ready - server initialization failed");
                    return;
                }

                log.info("MCP transport provider ready, handling request");

                // Parse just enough to tell request vs notification
                McpSchema.JSONRPCMessage msg;
                try {
                    msg = McpSchema.deserializeJsonRpcMessage(objectMapper, requestBody);
                } catch (Exception e) {
                    log.error("Invalid JSON-RPC message", e);
                    sendErrorResponse(channel, "1", "Invalid JSON-RPC message: " + e.getMessage());
                    return;
                }

                if (msg instanceof McpSchema.JSONRPCNotification) {
                    // 1) Immediately acknowledge with 202 and NO BODY
                    channel.sendResponse(new BytesRestResponse(RestStatus.ACCEPTED, "application/json", BytesArray.EMPTY));

                    // 2) Process asynchronously; don't attempt to write to channel again
                    transportProvider
                        .handleRequest(requestBody)
                        .subscribe(
                            ignored -> { /* no-op; notifications don’t produce responses */ },
                            err -> log.error("Notification handling failed", err)
                        );
                    return;
                }

                // Regular JSON-RPC request: await response and return 200 with body
                transportProvider.handleRequest(requestBody).subscribe(response -> {
                    try {
                        String responseJson = objectMapper.writeValueAsString(response);
                        channel.sendResponse(new BytesRestResponse(RestStatus.OK, "application/json", responseJson));
                    } catch (Exception e) {
                        log.error("Failed to send response", e);
                        sendErrorResponse(channel, "1", "Failed to send response: " + e.getMessage());
                    }
                }, error -> {
                    log.error("Failed to handle MCP request", error);
                    sendErrorResponse(channel, "1", "Internal server error: " + error.getMessage());
                });

            } catch (Exception e) {
                log.error("Failed to handle stateless MCP request", e);
                sendErrorResponse(channel, "1", "Internal server error: " + e.getMessage());
            }
        };
    }

    private void sendErrorResponse(RestChannel channel, String id, String errorMessage) {
        try {
            Map<String, Object> response = Map.of("jsonrpc", "2.0", "id", id, "error", Map.of("code", -32603, "message", errorMessage));

            String responseJson = objectMapper.writeValueAsString(response);
            channel.sendResponse(new BytesRestResponse(RestStatus.OK, "application/json", responseJson));
        } catch (Exception e) {
            log.error("Failed to send error response", e);
        }
    }
}
