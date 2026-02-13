/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.engine.agents.v2.tools;

import java.util.HashMap;
import java.util.Map;

import org.opensearch.ml.common.agent.MLAgent;
import org.opensearch.ml.common.agent.MLToolSpec;
import org.opensearch.ml.common.spi.tools.Tool;
import org.opensearch.ml.engine.agents.v2.tools.adapters.LegacyToolAdapter;
import org.opensearch.ml.engine.algorithms.agent.AgentUtils;

import lombok.extern.log4j.Log4j2;

/**
 * Creates a V2 ToolHandler from V1 tool factories and agent configuration.
 * <p>
 * Phase 1: Uses V1 toolFactoryMap exclusively, wraps V1 tools in LegacyToolAdapter.
 * Phase 2: Will add V2-native tool creation + MCP tool loading.
 */
@Log4j2
public class ToolCreationHelper {

    /**
     * Create a ToolHandler with all tools defined in the agent's toolSpecs.
     * Uses V1 factories to create tools, then wraps them in LegacyToolAdapter.
     */
    public static ToolHandler createToolHandler(Map<String, Tool.Factory> toolFactories, MLAgent agent, Map<String, String> params) {
        ToolHandler toolHandler = new ToolHandler();

        if (agent.getTools() == null || agent.getTools().isEmpty()) {
            log.info("No tools configured for V2 agent: {}", agent.getName());
            return toolHandler;
        }

        for (MLToolSpec toolSpec : agent.getTools()) {
            try {
                // Skip MCP tools in Phase 1
                if ("McpSseTool".equals(toolSpec.getType()) || "McpStreamableHttpTool".equals(toolSpec.getType())) {
                    log.info("Skipping MCP tool '{}' in V2 Phase 1", toolSpec.getName());
                    continue;
                }

                // Build tool parameters (merges agent params, tool params, config, etc.)
                Map<String, String> toolParams = new HashMap<>(params);
                if (toolSpec.getParameters() != null) {
                    toolParams.putAll(toolSpec.getParameters());
                }

                // Create V1 tool via factory
                Tool v1Tool = AgentUtils.createTool(toolFactories, toolParams, toolSpec);

                // Copy attributes from toolSpec if not already set
                if (toolSpec.getAttributes() != null) {
                    if (v1Tool.getAttributes() == null) {
                        Map<String, Object> attributes = new HashMap<>(toolSpec.getAttributes());
                        v1Tool.setAttributes(attributes);
                    } else {
                        v1Tool.getAttributes().putAll(toolSpec.getAttributes());
                    }
                }

                // Wrap in adapter and register
                LegacyToolAdapter adapter = new LegacyToolAdapter(v1Tool);
                toolHandler.register(adapter);
                log.debug("V2 tool created: {} (type: {}, via V1 factory + LegacyToolAdapter)", v1Tool.getName(), v1Tool.getType());

            } catch (Exception e) {
                log.error("Failed to create V2 tool from spec: {}", toolSpec.getType(), e);
                // Continue with remaining tools
            }
        }

        log.info("V2 ToolHandler created with {} tools for agent: {}", toolHandler.getToolSpecs().size(), agent.getName());
        return toolHandler;
    }
}
