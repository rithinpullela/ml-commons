/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.engine.tools;

import static org.opensearch.ml.common.FunctionName.REMOTE;
import static org.opensearch.ml.common.utils.ToolUtils.NO_ESCAPE_PARAMS;
import static org.opensearch.ml.engine.algorithms.agent.AgentUtils.DEFAULT_NO_ESCAPE_PARAMS;
import static org.opensearch.ml.engine.algorithms.agent.AgentUtils.LLM_INTERFACE_BEDROCK_CONVERSE_CLAUDE;
import static org.opensearch.ml.engine.algorithms.agent.AgentUtils.LLM_INTERFACE_GEMINI_V1BETA_GENERATE_CONTENT;
import static org.opensearch.ml.engine.algorithms.agent.AgentUtils.LLM_INTERFACE_OPENAI_V1_CHAT_COMPLETIONS;
import static org.opensearch.ml.engine.algorithms.agent.AgentUtils.TOOLS;
import static org.opensearch.ml.engine.algorithms.agent.AgentUtils.TOOL_TEMPLATE;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.text.StringSubstitutor;
import org.opensearch.core.action.ActionListener;
import org.opensearch.ml.common.dataset.remote.RemoteInferenceInputDataSet;
import org.opensearch.ml.common.input.MLInput;
import org.opensearch.ml.common.output.model.ModelTensorOutput;
import org.opensearch.ml.common.transport.MLTaskResponse;
import org.opensearch.ml.common.transport.prediction.MLPredictionTaskAction;
import org.opensearch.ml.common.transport.prediction.MLPredictionTaskRequest;
import org.opensearch.ml.common.utils.StringUtils;
import org.opensearch.ml.engine.function_calling.FunctionCalling;
import org.opensearch.ml.engine.function_calling.FunctionCallingFactory;
import org.opensearch.transport.client.Client;

import lombok.extern.log4j.Log4j2;

/**
 * Enriches AST-extracted parameter definitions with LLM-generated descriptions.
 * Uses function calling with a forced tool call to get structured output from the LLM.
 *
 * <p>The tool schema is built dynamically: each extracted parameter name becomes a property
 * in the JSON Schema, and the LLM fills in a description string as the value. This ensures
 * the LLM returns exactly one description per parameter with no hallucinated extras.</p>
 */
@Log4j2
public class LLMParameterEnricher {

    static final String SYSTEM_PROMPT = "You are a parameter description enhancer for OpenSearch search templates. "
        + "You will be given a Mustache search template and a list of parameters extracted from it. "
        + "Your job is to provide human-quality descriptions for each parameter.\n\n"
        + "You MUST call the enhance_parameters function with your enhanced parameter definitions. Do not respond with text.\n\n"
        + "For each parameter:\n"
        + "- Provide a clear, concise description (1 sentence) that explains what the parameter does "
        + "in the context of the search query\n"
        + "- Keep the same parameter names exactly as provided\n";

    static final String ENHANCE_PARAMS_TOOL_NAME = "enhance_parameters";
    static final String ENHANCE_PARAMS_TOOL_DESCRIPTION = "Provide enhanced human-quality descriptions for each template parameter";

    private LLMParameterEnricher() {}

    /**
     * Calls the LLM to enrich parameter descriptions, then merges with AST-extracted params.
     * The LLM only provides descriptions; required/optional, defaults, and types stay from AST.
     *
     * @param client         the OpenSearch client
     * @param modelId        the model to call
     * @param llmInterface   the LLM interface identifier (e.g., "bedrock/converse/claude")
     * @param templateSource the raw Mustache template
     * @param astParams      AST-extracted parameters (name -> {type, description, required, default})
     * @param listener       callback with merged parameters
     */
    public static void enrich(
        Client client,
        String modelId,
        String llmInterface,
        String templateSource,
        Map<String, Map<String, Object>> astParams,
        ActionListener<Map<String, Map<String, Object>>> listener
    ) {
        FunctionCalling functionCalling;
        try {
            functionCalling = FunctionCallingFactory.create(llmInterface);
        } catch (Exception e) {
            listener.onFailure(new IllegalArgumentException("Invalid llm_interface '" + llmInterface + "': " + e.getMessage()));
            return;
        }
        if (functionCalling == null) {
            listener.onFailure(new IllegalArgumentException("llm_interface is required for Tier 2 LLM enrichment"));
            return;
        }

        Map<String, String> params = new HashMap<>();

        // Configure function calling (sets tool_template, tool_configs, response paths, etc.)
        functionCalling.configure(params);

        // Override tool_configs to force the LLM to make a tool call
        forceToolCall(llmInterface, params);

        // Build dynamic input_schema: each param name is a property, value is a description string
        String inputSchema = buildDynamicInputSchema(astParams);

        // Render the single tool definition using the tool_template
        String toolTemplate = params.get(TOOL_TEMPLATE);
        Map<String, Object> toolParams = new HashMap<>();
        toolParams.put("name", ENHANCE_PARAMS_TOOL_NAME);
        toolParams.put("description", ENHANCE_PARAMS_TOOL_DESCRIPTION);
        toolParams.put("attributes.input_schema", inputSchema);
        toolParams.put("attributes.strict", "false");
        StringSubstitutor substitutor = new StringSubstitutor(toolParams, "${tool.", "}");
        String renderedTool = substitutor.replace(toolTemplate);
        params.put(TOOLS, renderedTool);

        // Set no-escape params so tool_configs and _tools are not escaped in the connector template
        if (!params.containsKey(NO_ESCAPE_PARAMS)) {
            params.put(NO_ESCAPE_PARAMS, DEFAULT_NO_ESCAPE_PARAMS);
        }

        // Build the user prompt with template source and extracted params
        String userPrompt = buildUserPrompt(templateSource, astParams);
        params.put("system_prompt", SYSTEM_PROMPT);
        params.put("user_prompt", userPrompt);

        // Build and execute the prediction request
        RemoteInferenceInputDataSet inputDataSet = RemoteInferenceInputDataSet.builder().parameters(params).build();
        MLInput mlInput = MLInput.builder().algorithm(REMOTE).inputDataset(inputDataSet).build();
        MLPredictionTaskRequest request = MLPredictionTaskRequest.builder().modelId(modelId).mlInput(mlInput).build();

        client.execute(MLPredictionTaskAction.INSTANCE, request, ActionListener.wrap(response -> {
            try {
                Map<String, Map<String, Object>> merged = parseAndMerge(response, functionCalling, params, astParams);
                listener.onResponse(merged);
            } catch (Exception e) {
                log.warn("Failed to parse LLM enrichment response, falling back to AST params: {}", e.getMessage());
                listener.onResponse(astParams);
            }
        }, e -> {
            log.warn("LLM enrichment call failed, falling back to AST params: {}", e.getMessage());
            listener.onResponse(astParams);
        }));
    }

    /**
     * Builds a dynamic JSON Schema where each param name is a string property.
     * The LLM fills in description strings as values.
     *
     * <p>Example output for params {query_text, genre, result_size}:</p>
     * <pre>{@code
     * {
     *   "type": "object",
     *   "properties": {
     *     "query_text": {"type": "string", "description": "Description for 'query_text' (type: string, required: true)"},
     *     "genre": {"type": "string", "description": "Description for 'genre' (type: string, required: false)"},
     *     "result_size": {"type": "string", "description": "Description for 'result_size' (type: number, required: true)"}
     *   },
     *   "required": ["query_text", "genre", "result_size"]
     * }
     * }</pre>
     */
    static String buildDynamicInputSchema(Map<String, Map<String, Object>> astParams) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"object\",\"properties\":{");

        List<String> propEntries = new ArrayList<>();
        List<String> requiredNames = new ArrayList<>();

        for (Map.Entry<String, Map<String, Object>> entry : astParams.entrySet()) {
            String paramName = entry.getKey();
            Map<String, Object> paramDef = entry.getValue();
            String type = String.valueOf(paramDef.getOrDefault("type", "string"));
            boolean required = Boolean.TRUE.equals(paramDef.get("required"));

            String desc = "Description for the '" + paramName + "' parameter (current type: " + type + ", required: " + required + ")";
            // Escape quotes in the description for JSON embedding
            desc = desc.replace("\"", "\\\"");

            propEntries.add("\"" + paramName + "\":{\"type\":\"string\",\"description\":\"" + desc + "\"}");
            requiredNames.add("\"" + paramName + "\"");
        }

        sb.append(String.join(",", propEntries));
        sb.append("},\"required\":[");
        sb.append(String.join(",", requiredNames));
        sb.append("]}");

        return sb.toString();
    }

    static String buildUserPrompt(String templateSource, Map<String, Map<String, Object>> astParams) {
        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("## Search Template Source\n```\n").append(templateSource).append("\n```\n\n");
        userPrompt.append("## Extracted Parameters\n");
        for (Map.Entry<String, Map<String, Object>> entry : astParams.entrySet()) {
            Map<String, Object> paramDef = entry.getValue();
            userPrompt
                .append("- **")
                .append(entry.getKey())
                .append("**: type=")
                .append(paramDef.get("type"))
                .append(", required=")
                .append(paramDef.get("required"))
                .append(", description=\"")
                .append(paramDef.get("description"))
                .append("\"");
            if (paramDef.containsKey("default")) {
                userPrompt.append(", default=").append(paramDef.get("default"));
            }
            userPrompt.append("\n");
        }
        userPrompt.append("\nCall the enhance_parameters function with improved descriptions for each parameter.");
        return userPrompt.toString();
    }

    /**
     * Overrides tool_configs to force the LLM to make a tool call instead of responding with text.
     */
    static void forceToolCall(String llmInterface, Map<String, String> params) {
        String lower = llmInterface.trim().toLowerCase(Locale.ROOT);
        if (lower.equals(LLM_INTERFACE_OPENAI_V1_CHAT_COMPLETIONS)) {
            params
                .put(
                    "tool_configs",
                    ", \"tools\": [${parameters._tools:-}], \"tool_choice\": \"required\", \"parallel_tool_calls\": false"
                );
        } else if (lower.equals(LLM_INTERFACE_BEDROCK_CONVERSE_CLAUDE) || lower.startsWith("bedrock/converse")) {
            params.put("tool_configs", ", \"toolConfig\": {\"tools\": [${parameters._tools:-}], \"toolChoice\": {\"any\": {}}}");
        } else if (lower.equals(LLM_INTERFACE_GEMINI_V1BETA_GENERATE_CONTENT)) {
            params
                .put(
                    "tool_configs",
                    ", \"tools\": [{\"functionDeclarations\": [${parameters._tools:-}]}], \"toolConfig\": {\"functionCallingConfig\": {\"mode\": \"ANY\"}}"
                );
        }
        // For unknown interfaces, keep the default tool_configs from configure() (auto mode)
    }

    /**
     * Parses the LLM tool call response and merges descriptions into AST params.
     * The tool call returns {param_name: "description", ...} — a flat map of descriptions.
     * We keep AST's type, required, and default values; only override the description.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Map<String, Object>> parseAndMerge(
        MLTaskResponse response,
        FunctionCalling functionCalling,
        Map<String, String> fcParams,
        Map<String, Map<String, Object>> astParams
    ) {
        ModelTensorOutput modelTensorOutput = (ModelTensorOutput) response.getOutput();
        List<Map<String, String>> toolCalls = functionCalling.handle(modelTensorOutput, fcParams);

        if (toolCalls.isEmpty()) {
            log.warn("LLM did not return a tool call, using AST params as-is");
            return astParams;
        }

        // Parse the tool call arguments — flat map of {paramName: "description"}
        Map<String, String> toolCall = toolCalls.get(0);
        String toolInput = toolCall.get("tool_input");
        if (toolInput == null) {
            log.warn("Tool call has no input, using AST params as-is");
            return astParams;
        }

        Map<String, Object> descriptions = StringUtils.gson.fromJson(toolInput, Map.class);
        if (descriptions == null || descriptions.isEmpty()) {
            log.warn("LLM returned empty tool input, using AST params as-is");
            return astParams;
        }

        // Merge: AST owns type, required, default; LLM provides only description
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : astParams.entrySet()) {
            String paramName = entry.getKey();
            Map<String, Object> astDef = entry.getValue();
            Map<String, Object> mergedDef = new LinkedHashMap<>(astDef);

            Object llmDescription = descriptions.get(paramName);
            if (llmDescription instanceof String && !((String) llmDescription).isEmpty()) {
                mergedDef.put("description", llmDescription);
            }

            merged.put(paramName, mergedDef);
        }

        return merged;
    }
}
