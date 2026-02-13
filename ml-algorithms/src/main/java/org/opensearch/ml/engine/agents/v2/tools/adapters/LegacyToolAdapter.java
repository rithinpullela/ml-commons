/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.engine.agents.v2.tools.adapters;

import static org.opensearch.ml.common.CommonValue.TOOL_INPUT_SCHEMA_FIELD;

import java.util.HashMap;
import java.util.Map;

import org.opensearch.core.action.ActionListener;
import org.opensearch.ml.common.agent.v2.ToolCallResult;
import org.opensearch.ml.common.agent.v2.ToolSpec;
import org.opensearch.ml.common.spi.tools.Tool;
import org.opensearch.ml.common.utils.StringUtils;
import org.opensearch.ml.engine.agents.v2.tools.AgentToolV2;
import org.opensearch.ml.engine.agents.v2.tools.ToolExecutionContext;

import lombok.extern.log4j.Log4j2;

/**
 * Adapter that wraps a V1 Tool SPI implementation as a V2 AgentToolV2.
 * <p>
 * Handles the type conversion:
 * - V2 input: Map&lt;String, Object&gt; (structured, preserves types from LLM JSON)
 * - V1 input: Map&lt;String, String&gt; (flat string params)
 * <p>
 * The adapter flattens Object values to Strings for V1 compatibility.
 * V1 tools that need structured input (like JSON strings) will receive
 * the JSON-serialized form of Object values.
 */
@Log4j2
public class LegacyToolAdapter implements AgentToolV2 {

    private final Tool v1Tool;
    private final ToolSpec toolSpec;

    public LegacyToolAdapter(Tool v1Tool) {
        this.v1Tool = v1Tool;
        this.toolSpec = buildToolSpec(v1Tool);
    }

    @Override
    public String getName() {
        return v1Tool.getName();
    }

    @Override
    public String getDescription() {
        return v1Tool.getDescription();
    }

    @Override
    public String getType() {
        return v1Tool.getType();
    }

    @Override
    public ToolSpec getToolSpec() {
        return toolSpec;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void execute(Map<String, Object> arguments, ToolExecutionContext context, ActionListener<ToolCallResult> listener) {
        try {
            // Flatten Map<String, Object> to Map<String, String> for V1 tools
            Map<String, String> v1Params = flattenToStringMap(arguments);

            // Add the "input" param as a JSON string of all arguments
            // (Many V1 tools expect a single "input" parameter with JSON)
            if (!v1Params.containsKey("input")) {
                v1Params.put("input", StringUtils.toJson(arguments));
            }

            v1Tool.run(v1Params, ActionListener.wrap(result -> {
                String content = result != null ? result.toString() : "";
                listener.onResponse(ToolCallResult.success(context.getToolCallId(), v1Tool.getName(), content));
            }, e -> {
                log.error("V1 tool execution failed: {}", v1Tool.getName(), e);
                listener.onResponse(ToolCallResult.error(context.getToolCallId(), v1Tool.getName(), "Tool error: " + e.getMessage()));
            }));
        } catch (Exception e) {
            log.error("Failed to invoke V1 tool: {}", v1Tool.getName(), e);
            listener
                .onResponse(ToolCallResult.error(context.getToolCallId(), v1Tool.getName(), "Failed to invoke tool: " + e.getMessage()));
        }
    }

    /**
     * Flatten a Map&lt;String, Object&gt; to Map&lt;String, String&gt;.
     * Objects are converted to their JSON string representation or toString().
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> flattenToStringMap(Map<String, Object> args) {
        Map<String, String> result = new HashMap<>();
        if (args == null) {
            return result;
        }
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                result.put(entry.getKey(), null);
            } else if (value instanceof String) {
                result.put(entry.getKey(), (String) value);
            } else if (value instanceof Map || value instanceof java.util.List) {
                // Serialize complex types to JSON
                result.put(entry.getKey(), StringUtils.toJson(value));
            } else {
                result.put(entry.getKey(), value.toString());
            }
        }
        return result;
    }

    /**
     * Build a ToolSpec from V1 tool attributes.
     * V1 tools store their schema in attributes["input_schema"] as a JSON string.
     */
    @SuppressWarnings("unchecked")
    private ToolSpec buildToolSpec(Tool v1Tool) {
        Map<String, Object> inputSchema = null;
        if (v1Tool.getAttributes() != null) {
            Object schemaObj = v1Tool.getAttributes().get(TOOL_INPUT_SCHEMA_FIELD);
            if (schemaObj instanceof String schemaStr) {
                try {
                    inputSchema = StringUtils.fromJson(schemaStr, TOOL_INPUT_SCHEMA_FIELD);
                } catch (Exception e) {
                    log.warn("Failed to parse input_schema for tool {}: {}", v1Tool.getName(), e.getMessage());
                }
            } else if (schemaObj instanceof Map) {
                inputSchema = (Map<String, Object>) schemaObj;
            }
        }

        // Fallback: create a generic schema that accepts "input" as a string
        if (inputSchema == null) {
            inputSchema = Map
                .of(
                    "type",
                    "object",
                    "properties",
                    Map
                        .of(
                            "input",
                            Map
                                .of(
                                    "type",
                                    "string",
                                    "description",
                                    v1Tool.getDescription() != null ? v1Tool.getDescription() : v1Tool.getName()
                                )
                        ),
                    "required",
                    java.util.List.of("input")
                );
        }

        return new ToolSpec(v1Tool.getName(), v1Tool.getDescription(), inputSchema);
    }
}
