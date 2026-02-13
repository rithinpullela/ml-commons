/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.engine.agents.v2.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.opensearch.core.action.ActionListener;
import org.opensearch.ml.common.agent.MLAgent;
import org.opensearch.ml.common.agent.MLToolSpec;
import org.opensearch.ml.common.agent.v2.AgentToolV2;
import org.opensearch.ml.common.agent.v2.AgentToolV2Factory;
import org.opensearch.ml.common.spi.tools.Tool;
import org.opensearch.ml.common.utils.StringUtils;
import org.opensearch.ml.engine.agents.v2.tools.adapters.LegacyToolAdapter;
import org.opensearch.ml.engine.algorithms.agent.AgentUtils;
import org.opensearch.ml.engine.encryptor.Encryptor;
import org.opensearch.remote.metadata.client.SdkClient;
import org.opensearch.transport.client.Client;

import io.modelcontextprotocol.client.McpSyncClient;
import lombok.extern.log4j.Log4j2;

/**
 * Creates a V2 ToolHandler from tool factories and agent configuration.
 * <p>
 * Resolution order:
 * 1. V2 native factories (v2ToolFactories) — creates native AgentToolV2
 * 2. V1 tool factories (toolFactories) — wraps in LegacyToolAdapter
 * 3. MCP connectors — discovers MCP tools async, wraps as McpTool
 */
@Log4j2
public class ToolCreationHelper {

    /**
     * Create a ToolHandler with all tools defined in the agent's toolSpecs.
     * This is async because MCP tool discovery requires network calls.
     *
     * @param v2ToolFactories V2 native tool factory map (may be null)
     * @param v1ToolFactories V1 tool factory map
     * @param agent           Agent configuration
     * @param params          Runtime parameters
     * @param client          OpenSearch client (needed for MCP connector resolution)
     * @param sdkClient       SDK client (needed for MCP connector resolution)
     * @param encryptor       Encryptor (needed for MCP credential decryption)
     * @param listener        Callback with the assembled ToolHandler
     */
    public static void createToolHandler(
        Map<String, AgentToolV2Factory> v2ToolFactories,
        Map<String, Tool.Factory> v1ToolFactories,
        MLAgent agent,
        Map<String, String> params,
        Client client,
        SdkClient sdkClient,
        Encryptor encryptor,
        ActionListener<ToolHandler> listener
    ) {
        ToolHandler toolHandler = new ToolHandler();

        // 1. Register tools from agent's tool specs (V2-first, then V1 fallback)
        if (agent.getTools() != null) {
            for (MLToolSpec toolSpec : agent.getTools()) {
                try {
                    // Skip MCP tools here — they're handled via mcp_connectors param below
                    if ("McpSseTool".equals(toolSpec.getType()) || "McpStreamableHttpTool".equals(toolSpec.getType())) {
                        log.debug("Skipping MCP tool spec '{}' — MCP tools loaded via mcp_connectors", toolSpec.getName());
                        continue;
                    }

                    AgentToolV2 tool = createSingleTool(v2ToolFactories, v1ToolFactories, toolSpec, params);
                    if (tool != null) {
                        toolHandler.register(tool);
                    }
                } catch (Exception e) {
                    log.error("Failed to create tool from spec: {} (type: {})", toolSpec.getName(), toolSpec.getType(), e);
                }
            }
        }

        // 2. Discover and register MCP tools (async)
        loadMcpTools(agent, client, sdkClient, encryptor, ActionListener.wrap(mcpTools -> {
            for (AgentToolV2 mcpTool : mcpTools) {
                toolHandler.register(mcpTool);
            }
            log.info("V2 ToolHandler created with {} tools for agent: {}", toolHandler.getToolSpecs().size(), agent.getName());
            listener.onResponse(toolHandler);
        }, e -> {
            // MCP discovery failure is non-fatal — we still have the non-MCP tools
            log.warn("MCP tool discovery failed, continuing with {} non-MCP tools", toolHandler.getToolSpecs().size(), e);
            listener.onResponse(toolHandler);
        }));
    }

    /**
     * Create a single tool from a tool spec, trying V2 factory first, then V1 fallback.
     */
    private static AgentToolV2 createSingleTool(
        Map<String, AgentToolV2Factory> v2ToolFactories,
        Map<String, Tool.Factory> v1ToolFactories,
        MLToolSpec toolSpec,
        Map<String, String> params
    ) {
        String type = toolSpec.getType();

        // Try V2 factory first
        if (v2ToolFactories != null && v2ToolFactories.containsKey(type)) {
            AgentToolV2Factory factory = v2ToolFactories.get(type);
            Map<String, Object> factoryParams = new HashMap<>();
            if (toolSpec.getParameters() != null) {
                factoryParams.putAll(toolSpec.getParameters());
            }
            if (toolSpec.getName() != null) {
                factoryParams.put("name", toolSpec.getName());
            }
            if (toolSpec.getDescription() != null) {
                factoryParams.put("description", toolSpec.getDescription());
            }
            AgentToolV2 tool = factory.create(factoryParams);
            log.debug("Created native V2 tool: {} (type: {})", tool.getName(), type);
            return tool;
        }

        // Fall back to V1 factory + LegacyToolAdapter
        if (v1ToolFactories != null && v1ToolFactories.containsKey(type)) {
            Map<String, String> toolParams = new HashMap<>(params);
            if (toolSpec.getParameters() != null) {
                toolParams.putAll(toolSpec.getParameters());
            }

            Tool v1Tool = AgentUtils.createTool(v1ToolFactories, toolParams, toolSpec);

            // Copy attributes from toolSpec
            if (toolSpec.getAttributes() != null) {
                if (v1Tool.getAttributes() == null) {
                    v1Tool.setAttributes(new HashMap<>(toolSpec.getAttributes()));
                } else {
                    v1Tool.getAttributes().putAll(toolSpec.getAttributes());
                }
            }

            LegacyToolAdapter adapter = new LegacyToolAdapter(v1Tool);
            log.debug("Created V1-adapted tool: {} (type: {}, via LegacyToolAdapter)", v1Tool.getName(), type);
            return adapter;
        }

        log.warn("No factory found for tool type: {} (tool name: {})", type, toolSpec.getName());
        return null;
    }

    /**
     * Load MCP tools from connectors defined in agent parameters.
     * Uses the existing V1 AgentUtils.getMcpToolSpecs() to discover MCP tools,
     * then wraps them as native V2 McpTool instances.
     */
    @SuppressWarnings("unchecked")
    private static void loadMcpTools(
        MLAgent agent,
        Client client,
        SdkClient sdkClient,
        Encryptor encryptor,
        ActionListener<List<AgentToolV2>> listener
    ) {
        // Check if MCP connectors are configured
        String mcpConfig = (agent.getParameters() != null) ? agent.getParameters().get("mcp_connectors") : null;
        if (mcpConfig == null || sdkClient == null) {
            listener.onResponse(Collections.emptyList());
            return;
        }

        // Reuse V1 MCP discovery to get MLToolSpecs with McpSyncClient in runtime resources
        AgentUtils.getMcpToolSpecs(agent, client, sdkClient, encryptor, ActionListener.wrap(mcpToolSpecs -> {
            List<AgentToolV2> mcpTools = new ArrayList<>();
            for (MLToolSpec spec : mcpToolSpecs) {
                try {
                    // Extract McpSyncClient from runtime resources
                    Object mcpClientObj = spec.getRuntimeResource("MCP_SYNC_CLIENT");
                    if (mcpClientObj == null) {
                        log.warn("MCP tool spec '{}' has no McpSyncClient, skipping", spec.getName());
                        continue;
                    }

                    // Extract input schema from attributes
                    Map<String, Object> inputSchema = null;
                    if (spec.getAttributes() != null) {
                        Object schemaObj = spec.getAttributes().get("input_schema");
                        if (schemaObj instanceof String schemaStr) {
                            inputSchema = StringUtils.fromJson(schemaStr, "input_schema");
                        }
                    }

                    McpTool mcpTool = new McpTool(spec.getName(), spec.getDescription(), inputSchema, (McpSyncClient) mcpClientObj);
                    mcpTools.add(mcpTool);
                    log.debug("Created V2 McpTool: {}", spec.getName());
                } catch (Exception e) {
                    log.error("Failed to create McpTool from spec: {}", spec.getName(), e);
                }
            }
            listener.onResponse(mcpTools);
        }, listener::onFailure));
    }
}
