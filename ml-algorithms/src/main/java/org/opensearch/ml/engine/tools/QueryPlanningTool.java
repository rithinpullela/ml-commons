/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.engine.tools;

import static org.opensearch.action.support.clustermanager.ClusterManagerNodeRequest.DEFAULT_CLUSTER_MANAGER_NODE_TIMEOUT;
import static org.opensearch.ml.common.CommonValue.TOOL_INPUT_SCHEMA_FIELD;
import static org.opensearch.ml.common.utils.StringUtils.gson;
import static org.opensearch.ml.engine.algorithms.agent.AgentUtils.AGENT_LLM_MODEL_ID;
import static org.opensearch.ml.engine.algorithms.agent.AgentUtils.DEFAULT_DATETIME_FORMAT;
import static org.opensearch.ml.engine.algorithms.agent.AgentUtils.getCurrentDateTime;
import static org.opensearch.ml.engine.tools.QueryPlanningPromptTemplate.DEFAULT_QUERY;
import static org.opensearch.ml.engine.tools.QueryPlanningPromptTemplate.DEFAULT_QUERY_PLANNING_SYSTEM_PROMPT;
import static org.opensearch.ml.engine.tools.QueryPlanningPromptTemplate.DEFAULT_QUERY_PLANNING_USER_PROMPT;
import static org.opensearch.ml.engine.tools.QueryPlanningPromptTemplate.DEFAULT_SEARCH_TEMPLATE;
import static org.opensearch.ml.engine.tools.QueryPlanningPromptTemplate.DEFAULT_TEMPLATE_SELECTION_SYSTEM_PROMPT;
import static org.opensearch.ml.engine.tools.QueryPlanningPromptTemplate.DEFAULT_TEMPLATE_SELECTION_USER_PROMPT;
import static org.opensearch.ml.engine.tools.QueryPlanningPromptTemplate.GROUP_SELECTION_SYSTEM_PROMPT;
import static org.opensearch.ml.engine.tools.QueryPlanningPromptTemplate.TOOL_SELECTION_SYSTEM_PROMPT;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.text.StringSubstitutor;
import org.opensearch.action.admin.cluster.storedscripts.GetStoredScriptRequest;
import org.opensearch.action.admin.indices.get.GetIndexRequest;
import org.opensearch.action.admin.indices.get.GetIndexResponse;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.action.support.IndicesOptions;
import org.opensearch.cluster.metadata.MappingMetadata;
import org.opensearch.core.action.ActionListener;
import org.opensearch.index.IndexNotFoundException;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.ml.common.FunctionName;
import org.opensearch.ml.common.dataset.remote.RemoteInferenceInputDataSet;
import org.opensearch.ml.common.input.MLInput;
import org.opensearch.ml.common.output.model.ModelTensorOutput;
import org.opensearch.ml.common.spi.tools.Parser;
import org.opensearch.ml.common.spi.tools.Tool;
import org.opensearch.ml.common.spi.tools.ToolAnnotation;
import org.opensearch.ml.common.spi.tools.WithModelTool;
import org.opensearch.ml.common.transport.prediction.MLPredictionTaskAction;
import org.opensearch.ml.common.transport.prediction.MLPredictionTaskRequest;
import org.opensearch.ml.common.utils.ToolUtils;
import org.opensearch.ml.engine.algorithms.agent.AgentUtils;
import org.opensearch.ml.engine.function_calling.FunctionCalling;
import org.opensearch.ml.engine.function_calling.FunctionCallingFactory;
import org.opensearch.ml.engine.processor.ProcessorChain;
import org.opensearch.ml.engine.tools.parser.ToolParser;
import org.opensearch.search.SearchHit;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.transport.client.Client;

import com.google.gson.reflect.TypeToken;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;

/**
 * This tool supports different types of query planning,
 * llmGenerated, systemSearchTemplates or userSearchTemplates.
 */
@Log4j2
@ToolAnnotation(QueryPlanningTool.TYPE)
public class QueryPlanningTool implements WithModelTool {
    public static final String TYPE = "QueryPlanningTool";
    public static final String MODEL_ID_FIELD = "model_id";
    private final MLModelTool queryGenerationTool;
    public static final String SYSTEM_PROMPT_FIELD = "system_prompt";
    public static final String USER_PROMPT_FIELD = "user_prompt";
    public static final String QUERY_PLANNER_SYSTEM_PROMPT_FIELD = "query_planner_system_prompt";
    public static final String QUERY_PLANNER_USER_PROMPT_FIELD = "query_planner_user_prompt";
    public static final String TEMPLATE_SELECTION_SYSTEM_PROMPT_FIELD = "template_selection_system_prompt";
    public static final String TEMPLATE_SELECTION_USER_PROMPT_FIELD = "template_selection_user_prompt";
    public static final String INDEX_MAPPING_FIELD = "index_mapping";
    public static final String QUERY_FIELDS_FIELD = "query_fields";
    public static final String GENERATION_TYPE_FIELD = "generation_type";
    public static final String LLM_GENERATED_TYPE_FIELD = "llmGenerated";
    public static final String USER_SEARCH_TEMPLATES_TYPE_FIELD = "user_templates";
    public static final String SEARCH_TEMPLATES_FIELD = "search_templates";
    public static final String TOOL_GROUPS_FIELD = "tool_groups";
    public static final String GROUP_DESCRIPTION_FIELD = "group_description";
    public static final String SEARCHTEMPLATE_TOOLS_FIELD = "searchTemplate_tools";
    public static final String GROUP_SELECTION_SYSTEM_PROMPT_FIELD = "group_selection_system_prompt";
    public static final String GROUP_SELECTION_USER_PROMPT_FIELD = "group_selection_user_prompt";
    public static final String LLM_INTERFACE_FIELD = "llm_interface";
    public static final String SAMPLE_DOCUMENT_FIELD = "sample_document";
    private static final String CURRENT_TIME_FIELD = "current_time";
    public static final String TEMPLATE_FIELD = "template";
    public static final String STRICT_FIELD = "strict";
    public static final String QUESTION_FIELD = "question";
    private static final String TEMPLATE_ID_FIELD = "template_id";
    private static final String TEMPLATE_DESCRIPTION_FIELD = "template_description";
    public static final String INDEX_NAME_FIELD = "index_name";
    private static final int MAX_TRUNCATE_CHARS = 250;
    private static final String TRUNC_PREFIX = "[truncated]";
    // Agent context parameter keys to ignore
    private static final String CHAT_HISTORY_FIELD = "_chat_history";
    private static final String TOOLS_FIELD = "_tools";
    private static final String INTERACTIONS_FIELD = "_interactions";
    private static final String TOOL_CONFIGS_FIELD = "tool_configs";
    private static final Set<String> AGENT_CONTEXT_EXCLUDED_PARAMS = Set
        .of(CHAT_HISTORY_FIELD, TOOLS_FIELD, INTERACTIONS_FIELD, TOOL_CONFIGS_FIELD);

    @Getter
    private final String generationType;
    @Getter
    private final String searchTemplates;
    @Getter
    private final List<ToolGroup> toolGroups; // Null for llmGenerated/search_templates modes

    private final Map<String, Tool.Factory> toolFactories; // For creating tool instances at runtime
    private final String modelId; // Model ID for direct LLM calls (custom tools mode)
    @Setter
    @Getter
    private String name = TYPE;
    @Getter
    @Setter
    private Map<String, Object> attributes;
    static String DEFAULT_DESCRIPTION = "Use this tool to generate OpenSearch Query DSL from natural language queries."
        + "Provide a 'question' parameter containing the complete natural language query with all necessary context, requirements, filters, and constraints."
        + "The question should be self-contained with all information needed to generate the OpenSearch DSL."
        + "Provide 'index_name' to help generate more accurate queries based on the index structure."
        + "Optionally provide embedding model ID to be used for neural search "
        + "The tool will return a valid OpenSearch query that can be used to search your data.";

    public static final String DEFAULT_INPUT_SCHEMA = "{"
        + "\"type\":\"object\","
        + "\"properties\":{"
        + "\"question\":{\"type\":\"string\",\"description\":\"Complete natural language query with all necessary context to generate OpenSearch DSL. Include the question, any specific requirements, filters, or constraints. Examples: 'Find all products with price greater than 100 dollars', 'Show me documents about machine learning published in 2023', 'Search for users with status active and age between 25 and 35'\"},"
        + "\"index_name\":{\"type\":\"string\",\"description\":\"the name of the index against which the query needs to be generated.\"},"
        + "\"embedding_model_id\":{\"type\":\"string\",\"description\":\"the model id to perform neural search.\"}"
        + "},"
        + "\"required\":[\"question\", \"index_name\"],"
        + "\"additionalProperties\":false"
        + "}";

    public static final Map<String, Object> DEFAULT_ATTRIBUTES = Map.of(TOOL_INPUT_SCHEMA_FIELD, DEFAULT_INPUT_SCHEMA, STRICT_FIELD, false);

    @Getter
    @Setter
    private String description = DEFAULT_DESCRIPTION;
    private final Client client;

    @Setter
    @Getter
    private Parser outputParser;

    @Getter
    private static class ToolGroup {
        private final String groupDescription;
        private final List<String> searchTemplateTools;

        public ToolGroup(String groupDescription, List<String> searchTemplateTools) {
            this.groupDescription = groupDescription;
            this.searchTemplateTools = searchTemplateTools;
        }
    }

    public QueryPlanningTool(
        String generationType,
        MLModelTool queryGenerationTool,
        Client client,
        String searchTemplates,
        List<ToolGroup> toolGroups,
        Map<String, Tool.Factory> toolFactories,
        String modelId
    ) {
        this.generationType = generationType;
        this.queryGenerationTool = queryGenerationTool;
        this.client = client;
        this.searchTemplates = searchTemplates;
        this.toolGroups = toolGroups;
        this.toolFactories = toolFactories;
        this.modelId = modelId;
        this.attributes = new HashMap<>(DEFAULT_ATTRIBUTES);
    }

    private Map<String, String> stripAgentContextParameters(Map<String, String> originalParameters) {
        // Drop agent-specific metadata that can bias or slow query planning; keep all other non-null params.
        // This enables using the same LLM for both the agent and the Query Planning Tool.
        // Excluded keys: _chat_history, _tools, _interactions, tool_configs

        return originalParameters
            .entrySet()
            .stream()
            .filter(entry -> entry.getValue() != null && !AGENT_CONTEXT_EXCLUDED_PARAMS.contains(entry.getKey()))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Override
    public <T> void run(Map<String, String> originalParameters, ActionListener<T> listener) {
        try {
            Map<String, String> parameters = stripAgentContextParameters(ToolUtils.extractInputParameters(originalParameters, attributes));
            if (!validate(parameters)) {
                listener
                    .onFailure(
                        new IllegalArgumentException(
                            String
                                .format(
                                    "Validation error: missing or empty required parameters — %s, %s.",
                                    INDEX_NAME_FIELD,
                                    QUESTION_FIELD
                                )
                        )
                    );
                return;
            }

            if (!generationType.equals(USER_SEARCH_TEMPLATES_TYPE_FIELD)) {
                // llmGenerated mode: use default search template
                parameters.put(TEMPLATE_FIELD, DEFAULT_SEARCH_TEMPLATE);
                executeQueryPlanning(parameters, listener);
                return;
            }

            // user_templates mode: check sub-mode
            if (toolGroups != null && !toolGroups.isEmpty()) {
                // Custom tools mode: load tools → route by group → function calling → execute
                executeCustomToolsPlanning(parameters, listener);
                return;
            }

            // Existing search_templates mode continues unchanged...
            // Template Selection, replace user and system prompts
            Map<String, String> templateSelectionParameters = new HashMap<>(parameters);
            templateSelectionParameters
                .put(
                    SYSTEM_PROMPT_FIELD,
                    templateSelectionParameters
                        .getOrDefault(TEMPLATE_SELECTION_SYSTEM_PROMPT_FIELD, DEFAULT_TEMPLATE_SELECTION_SYSTEM_PROMPT)
                );

            templateSelectionParameters
                .put(
                    USER_PROMPT_FIELD,
                    templateSelectionParameters.getOrDefault(TEMPLATE_SELECTION_USER_PROMPT_FIELD, DEFAULT_TEMPLATE_SELECTION_USER_PROMPT)
                );

            templateSelectionParameters.put(SEARCH_TEMPLATES_FIELD, searchTemplates);

            ActionListener<T> templateSelectionListener = ActionListener.wrap(r -> {
                // Default search template if LLM does not choose or if returned search template is null
                parameters.put(TEMPLATE_FIELD, DEFAULT_SEARCH_TEMPLATE);
                try {
                    String templateId = (String) r;
                    if (templateId == null || templateId.isBlank() || templateId.equals("null")) {
                        executeQueryPlanning(parameters, listener);
                    } else {
                        // Retrieve search template by ID
                        GetStoredScriptRequest getStoredScriptRequest = new GetStoredScriptRequest(templateId);
                        client.admin().cluster().getStoredScript(getStoredScriptRequest, ActionListener.wrap(getStoredScriptResponse -> {
                            if (getStoredScriptResponse.getSource() != null) {
                                parameters.put(TEMPLATE_FIELD, gson.toJson(getStoredScriptResponse.getSource().getSource()));
                            }
                            executeQueryPlanning(parameters, listener);
                        }, e -> { listener.onFailure(e); }));
                    }
                } catch (Exception e) {
                    IllegalArgumentException parsingException = new IllegalArgumentException(
                        "Error processing search template: " + r + ". Try using response_filter in agent registration if needed.",
                        e
                    );
                    listener.onFailure(parsingException);
                }
            }, listener::onFailure);
            queryGenerationTool.run(templateSelectionParameters, templateSelectionListener);
        } catch (Exception e) {
            log.error("Failed to run QueryPlannerTool", e);
            listener.onFailure(e);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void executeQueryPlanning(Map<String, String> parameters, ActionListener<T> listener) {
        try {
            // Execute Query Planning, replace System and User prompt fields
            parameters
                .put(SYSTEM_PROMPT_FIELD, parameters.getOrDefault(QUERY_PLANNER_SYSTEM_PROMPT_FIELD, DEFAULT_QUERY_PLANNING_SYSTEM_PROMPT));

            parameters.put(USER_PROMPT_FIELD, parameters.getOrDefault(QUERY_PLANNER_USER_PROMPT_FIELD, DEFAULT_QUERY_PLANNING_USER_PROMPT));

            if (parameters.containsKey(QUERY_FIELDS_FIELD)) {
                parameters.put(QUERY_FIELDS_FIELD, gson.toJson(parameters.get(QUERY_FIELDS_FIELD)));
            }

            String currentDateTime = getCurrentDateTime(DEFAULT_DATETIME_FORMAT);
            parameters.put(CURRENT_TIME_FIELD, gson.toJson(currentDateTime));

            // async chain: getIndexMapping -> getSampleDoc -> call model
            getIndexMappingAsync(parameters.get(INDEX_NAME_FIELD), ActionListener.wrap(indexMapping -> {
                parameters.put(INDEX_MAPPING_FIELD, gson.toJson(indexMapping));
                getSampleDocAsync(parameters.get(INDEX_NAME_FIELD), ActionListener.wrap(sampleDoc -> {
                    parameters.put(SAMPLE_DOCUMENT_FIELD, gson.toJson(sampleDoc));

                    // Now call the model
                    ActionListener<T> modelListener = ActionListener.wrap(r -> {
                        try {
                            String queryString = (String) r;
                            if (queryString == null || queryString.isBlank() || queryString.equals("null")) {
                                log.debug("Model failed to generate the DSL query, returning the Default match all query");
                                StringSubstitutor substitutor = new StringSubstitutor(parameters, "${parameters.", "}");
                                String defaultQueryString = substitutor.replace(DEFAULT_QUERY);
                                listener.onResponse((T) defaultQueryString);
                            } else {
                                listener.onResponse((T) (outputParser != null ? outputParser.parse(queryString) : queryString));
                            }
                        } catch (Exception e) {
                            IllegalArgumentException parsingException = new IllegalArgumentException(
                                "Error processing query string: " + r + ". Try using response_filter in agent registration if needed.",
                                e
                            );
                            listener.onFailure(parsingException);
                        }
                    }, listener::onFailure);
                    queryGenerationTool.run(parameters, modelListener);

                }, listener::onFailure));
            }, listener::onFailure));
        } catch (Exception e) {
            log.error("Failed to run QueryPlannerTool", e);
            listener.onFailure(e);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void executeCustomToolsPlanning(Map<String, String> parameters, ActionListener<T> listener) {
        try {
            log.info("[LATENCY] Custom tools planning started at {}", System.currentTimeMillis());
            log.info("Executing custom tools planning mode with {} tool group(s)", toolGroups.size());
            if (toolGroups.size() == 1) {
                // Single group: skip group selection, go directly to tool selection
                ToolGroup group = toolGroups.get(0);
                log.info("Single group mode, using group: {}", group.getGroupDescription());
                loadToolsAndExecuteFunctionCalling(group.getSearchTemplateTools(), parameters, listener);
            } else {
                // Multiple groups: first select the group via LLM
                log.info("Multiple groups mode, will select group first");
                selectGroupThenExecute(parameters, listener);
            }
        } catch (Exception e) {
            log.error("Failed to execute custom tools planning", e);
            listener.onFailure(e);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void loadToolsAndExecuteFunctionCalling(
        List<String> toolNames,
        Map<String, String> parameters,
        ActionListener<T> listener
    ) {
        log.info("[LATENCY] Tool loading started at {}", System.currentTimeMillis());
        log.info("Loading {} custom tools: {}", toolNames.size(), toolNames);
        // Create resolver for custom tools (handles RBAC)
        CustomToolResolver resolver = new CustomToolResolver(client);

        // Sequential tool loading - follows AgentUtils.createToolAtIndex pattern
        List<Map<String, Object>> loadedToolDefs = new ArrayList<>();
        loadToolAtIndex(resolver, toolNames, 0, loadedToolDefs, ActionListener.wrap(toolDefs -> {
            log.info("[LATENCY] Tool loading completed at {}", System.currentTimeMillis());
            log.info("Successfully loaded {} custom tool definitions", toolDefs.size());
            // All tools loaded, now call LLM for tool selection + param filling
            callLLMForToolSelection(toolDefs, parameters, listener);
        }, listener::onFailure));
    }

    private void loadToolAtIndex(
        CustomToolResolver resolver,
        List<String> toolNames,
        int index,
        List<Map<String, Object>> loadedToolDefs,
        ActionListener<List<Map<String, Object>>> listener
    ) {
        if (index >= toolNames.size()) {
            // All tools loaded
            listener.onResponse(loadedToolDefs);
            return;
        }

        String toolName = toolNames.get(index);
        log.info("[LATENCY] Resolving custom tool '{}' (index {}) at {}", toolName, index, System.currentTimeMillis());
        resolver.resolve(toolName, ActionListener.wrap(toolDef -> {
            log.info("[LATENCY] Resolved tool '{}' at {}", toolName, System.currentTimeMillis());
            loadedToolDefs.add(toolDef);
            loadToolAtIndex(resolver, toolNames, index + 1, loadedToolDefs, listener);
        }, e -> {
            log.error("Failed to resolve custom tool: {}", toolName, e);
            listener.onFailure(new IllegalArgumentException("Custom tool not found or access denied: " + toolName, e));
        }));
    }

    @SuppressWarnings("unchecked")
    private <T> void callLLMForToolSelection(
        List<Map<String, Object>> toolDefs,
        Map<String, String> parameters,
        ActionListener<T> listener
    ) {
        log.info("[LATENCY] LLM tool selection preparation started at {}", System.currentTimeMillis());
        try {
            // Get llm_interface for FunctionCalling
            String llmInterface = parameters.get(LLM_INTERFACE_FIELD);
            if (llmInterface == null || llmInterface.isBlank()) {
                listener.onFailure(new IllegalArgumentException("llm_interface parameter required for custom tools mode"));
                return;
            }

            // Create FunctionCalling instance
            FunctionCalling functionCalling = FunctionCallingFactory.create(llmInterface);
            if (functionCalling == null) {
                listener.onFailure(new IllegalArgumentException("Unsupported llm_interface: " + llmInterface));
                return;
            }

            // Build function definitions from custom tool schemas using standard format
            // (works across all FunctionCalling implementations: Bedrock, OpenAI, Gemini, etc.)
            List<Map<String, Object>> functionDefs = new ArrayList<>();
            for (Map<String, Object> toolDef : toolDefs) {
                Map<String, Object> functionDef = new HashMap<>();
                functionDef.put("name", toolDef.get("name"));
                functionDef.put("description", toolDef.get("description"));

                // Convert flat params to JSON schema (reuse SearchTemplateTool pattern)
                Map<String, Object> paramsFlat = (Map<String, Object>) toolDef.get("params");
                Map<String, Object> schema = convertParamsToJsonSchema(paramsFlat);
                log.info("Converted tool '{}' params to schema: {}", toolDef.get("name"), gson.toJson(schema));

                // Use standard format: attributes.input_schema (keep as Map, not JSON string)
                Map<String, Object> attributes = new HashMap<>();
                attributes.put("input_schema", schema);
                functionDef.put("attributes", attributes);

                functionDefs.add(functionDef);
            }
            log.info("[LATENCY] Schema conversion completed at {}", System.currentTimeMillis());
            log.info("Built {} function definitions for custom tools", functionDefs.size());

            // Prepare function calling parameters
            Map<String, String> fcParams = new HashMap<>(parameters);

            // Configure function calling first (sets TOOL_TEMPLATE and tool_configs)
            functionCalling.configure(fcParams);
            String toolTemplate = fcParams.get(AgentUtils.TOOL_TEMPLATE);
            log.info("[LATENCY] Function calling configured at {}", System.currentTimeMillis());
            log.info("Tool template from function calling: {}", toolTemplate);

            // Transform each tool using the TOOL_TEMPLATE (following AgentUtils.addToolsToFunctionCalling pattern)
            List<String> transformedTools = new ArrayList<>();
            for (Map<String, Object> functionDef : functionDefs) {
                Map<String, Object> toolParams = new HashMap<>();
                toolParams.put("tool.name", functionDef.get("name"));
                toolParams.put("tool.description", functionDef.get("description"));

                // Add attributes (like input_schema)
                Map<String, Object> attributes = (Map<String, Object>) functionDef.get("attributes");
                if (attributes != null) {
                    for (Map.Entry<String, Object> entry : attributes.entrySet()) {
                        toolParams.put("tool.attributes." + entry.getKey(), gson.toJson(entry.getValue()));
                    }
                }

                // Apply template transformation
                StringSubstitutor substitutor = new StringSubstitutor(toolParams);
                String transformedTool = substitutor.replace(toolTemplate);
                transformedTools.add(transformedTool);
            }

            // Join transformed tools and put in _tools parameter
            String toolsForRequest = String.join(", ", transformedTools);
            fcParams.put(AgentUtils.TOOLS, toolsForRequest);
            log.info("[LATENCY] Tool transformation completed at {}", System.currentTimeMillis());
            log.info("Transformed tools for request: {}", toolsForRequest);
            log.info("Configured function calling, tool_configs: {}", fcParams.get("tool_configs"));

            // Override tool_choice to force tool call
            fcParams.put("tool_choice", "required");

            // Set prompts required by connector (always override user input for function calling)
            fcParams.put(SYSTEM_PROMPT_FIELD, TOOL_SELECTION_SYSTEM_PROMPT);
            fcParams.put(USER_PROMPT_FIELD, "User query: " + parameters.get(QUESTION_FIELD));
            log.info("Calling LLM for tool selection with {} tools", functionDefs.size());

            // Build and execute the prediction request directly
            log.info("[LATENCY] LLM request started at {}", System.currentTimeMillis());
            RemoteInferenceInputDataSet inputDataSet = RemoteInferenceInputDataSet.builder().parameters(fcParams).build();
            MLInput mlInput = MLInput.builder().algorithm(FunctionName.REMOTE).inputDataset(inputDataSet).build();
            MLPredictionTaskRequest request = MLPredictionTaskRequest.builder().modelId(modelId).mlInput(mlInput).build();

            client.execute(MLPredictionTaskAction.INSTANCE, request, ActionListener.wrap(response -> {
                try {
                    log.info("[LATENCY] LLM response received at {}", System.currentTimeMillis());

                    // Extract ModelTensorOutput from response
                    ModelTensorOutput modelOutput = (ModelTensorOutput) response.getOutput();
                    log.info("Received LLM response for tool selection");

                    // Check if LLM produced text alongside tool call (adds latency)
                    if (modelOutput != null && modelOutput.getMlModelOutputs() != null && !modelOutput.getMlModelOutputs().isEmpty()) {
                        Map<String, ?> dataAsMap = modelOutput.getMlModelOutputs().get(0).getMlModelTensors().get(0).getDataAsMap();
                        if (dataAsMap != null && dataAsMap.containsKey("response")) {
                            String responseText = String.valueOf(dataAsMap.get("response"));
                            if (responseText != null && !responseText.trim().isEmpty() && !responseText.equals("null")) {
                                log
                                    .warn(
                                        "LLM produced text response alongside tool call (wastes tokens/latency): '{}'",
                                        responseText.length() > 100 ? responseText.substring(0, 100) + "..." : responseText
                                    );
                            }
                        }
                    }

                    // Parse tool call using FunctionCalling.handle() - handles provider-specific formats
                    List<Map<String, String>> toolCalls = functionCalling.handle(modelOutput, fcParams);
                    log.info("[LATENCY] Tool call parsing completed at {}", System.currentTimeMillis());
                    log.info("FunctionCalling.handle() returned {} tool calls", toolCalls != null ? toolCalls.size() : 0);

                    if (toolCalls == null || toolCalls.isEmpty()) {
                        listener.onFailure(new IllegalArgumentException("LLM did not return a tool call"));
                        return;
                    }

                    // Get first tool call (QPT only uses the first tool, others are ignored)
                    Map<String, String> toolCall = toolCalls.get(0);
                    String selectedToolName = toolCall.get("tool_name");
                    String toolInputJson = toolCall.get("tool_input");
                    log.info("LLM selected tool: '{}' with input: {}", selectedToolName, toolInputJson);

                    if (selectedToolName == null || toolInputJson == null) {
                        listener.onFailure(new IllegalArgumentException("Tool call missing tool_name or tool_input"));
                        return;
                    }

                    // Find selected tool definition
                    Map<String, Object> selectedToolDef = toolDefs
                        .stream()
                        .filter(t -> selectedToolName.equals(t.get("name")))
                        .findFirst()
                        .orElse(null);

                    if (selectedToolDef == null) {
                        listener.onFailure(new IllegalArgumentException("LLM selected unknown tool: " + selectedToolName));
                        return;
                    }

                    log.info("Custom tool selected via function calling: {}", selectedToolName);

                    // Execute the selected tool (black box delegation)
                    executeSelectedCustomTool(selectedToolDef, toolInputJson, listener);

                } catch (Exception e) {
                    log.error("Failed to parse function calling response", e);
                    listener.onFailure(new IllegalArgumentException("Failed to parse tool call from LLM response", e));
                }
            }, e -> {
                log.error("[LATENCY] LLM call failed at {}: {}", System.currentTimeMillis(), e.getMessage());
                listener.onFailure(e);
            }));

        } catch (Exception e) {
            log.error("Failed to call LLM for tool selection", e);
            listener.onFailure(e);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void executeSelectedCustomTool(Map<String, Object> toolDef, String toolInputJson, ActionListener<T> listener) {
        log.info("[LATENCY] Tool execution started at {}", System.currentTimeMillis());
        try {
            String toolName = (String) toolDef.get("name");
            log.info("Executing selected custom tool: {}", toolName);

            // Parse LLM-filled parameters
            Map<String, String> filledParams = gson.fromJson(toolInputJson, new TypeToken<Map<String, String>>() {
            }.getType());
            log.info("Parsed LLM-filled parameters: {}", filledParams);

            // QPT is a query planning tool - always render templates without executing
            // SearchTemplateTool.run() enforces this at line 145-152
            filledParams.put(SearchTemplateTool.EXECUTION_MODE_FIELD, SearchTemplateTool.EXECUTION_MODE_RENDER_ONLY);

            // Get tool factory (SearchTemplateTool for all custom tools)
            Tool.Factory<?> toolFactory = toolFactories.get(SearchTemplateTool.TYPE);
            log.info("Using SearchTemplateTool factory for custom tool: {}", toolDef.get("name"));

            if (toolFactory == null) {
                listener.onFailure(new IllegalArgumentException("Tool factory not found for type: " + SearchTemplateTool.TYPE));
                return;
            }

            // Build factory params - follows AgentUtils.createToolAtIndex pattern (lines 1034-1042)
            Map<String, Object> factoryParams = new HashMap<>(filledParams);
            factoryParams.put(SearchTemplateTool.SEARCH_TEMPLATE_NAME_FIELD, toolDef.get("search_template_name"));
            factoryParams.put(SearchTemplateTool.PARAMS_FIELD, toolDef.get("params"));

            // Index name required by SearchTemplateTool factory (even for render_only mode)
            if (toolDef.get("index") != null) {
                factoryParams.put(SearchTemplateTool.INDEX_FIELD, toolDef.get("index"));
            }

            // Create tool instance (lazy instantiation - follows agent pattern)
            Tool tool = toolFactory.create(factoryParams);
            log.info("[LATENCY] Tool instance created at {}", System.currentTimeMillis());

            // Set description from stored custom tool (AgentUtils pattern line 1044-1050)
            // Purpose: tool metadata for logging, introspection, future extensibility
            String description = (String) toolDef.get("description");
            if (description != null) {
                tool.setDescription(description);
            }

            // Validate parameters
            if (!tool.validate(filledParams)) {
                listener.onFailure(new IllegalArgumentException("Invalid parameters for tool: " + toolDef.get("name")));
                return;
            }
            log.info("[LATENCY] Tool validation completed at {}", System.currentTimeMillis());

            // Execute tool - returns rendered DSL only (execution_mode="render_only")
            // SearchTemplateTool.run() branches at line 152 to respondWithResult(renderedQuery)
            log.info("[LATENCY] Tool.run() started at {}", System.currentTimeMillis());
            log.info("Calling SearchTemplateTool.run() with execution_mode=render_only");
            tool.run(filledParams, ActionListener.wrap(result -> {
                log.info("[LATENCY] Tool.run() completed at {}", System.currentTimeMillis());
                log.info("SearchTemplateTool returned rendered query DSL");
                // Return DSL string (no search results, just the query)
                listener.onResponse((T) (outputParser != null ? outputParser.parse(result) : result));
            }, listener::onFailure));

        } catch (Exception e) {
            log.error("Failed to execute custom tool", e);
            listener.onFailure(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> convertParamsToJsonSchema(Map<String, Object> paramsFlat) {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        List<String> required = new ArrayList<>();

        for (Map.Entry<String, Object> entry : paramsFlat.entrySet()) {
            String paramName = entry.getKey();
            Map<String, Object> paramDef = (Map<String, Object>) entry.getValue();

            // Build property definition
            Map<String, Object> prop = new HashMap<>();
            prop.put("type", paramDef.getOrDefault("type", "string"));
            if (paramDef.containsKey("description")) {
                prop.put("description", paramDef.get("description"));
            }
            properties.put(paramName, prop);

            // Track required params
            Object reqObj = paramDef.get("required");
            boolean isRequired = reqObj instanceof Boolean ? (Boolean) reqObj : Boolean.parseBoolean(String.valueOf(reqObj));
            if (isRequired) {
                required.add(paramName);
            }
        }

        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }

        return schema;
    }

    @SuppressWarnings("unchecked")
    private <T> void selectGroupThenExecute(Map<String, String> parameters, ActionListener<T> listener) {
        log.info("[LATENCY] Group selection started at {}", System.currentTimeMillis());
        try {
            // Get llm_interface for FunctionCalling
            String llmInterface = parameters.get(LLM_INTERFACE_FIELD);
            FunctionCalling functionCalling = FunctionCallingFactory.create(llmInterface);

            // Build a simple "select_group" function to force structured output
            Map<String, Object> selectGroupFunction = new HashMap<>();
            selectGroupFunction.put("name", "select_group");
            selectGroupFunction.put("description", "Select the most relevant tool group for the user's query");

            Map<String, Object> schema = new HashMap<>();
            schema.put("type", "object");
            Map<String, Object> props = new HashMap<>();
            props
                .put(
                    "group_index",
                    Map
                        .of(
                            "type",
                            "integer",
                            "description",
                            "Index of the selected group (0-" + (toolGroups.size() - 1) + ")",
                            "minimum",
                            0,
                            "maximum",
                            toolGroups.size() - 1
                        )
                );
            schema.put("properties", props);
            schema.put("required", List.of("group_index"));
            selectGroupFunction.put("parameters", schema);

            // Configure function calling
            Map<String, String> fcParams = new HashMap<>(parameters);
            fcParams.put(AgentUtils.TOOLS, gson.toJson(List.of(selectGroupFunction)));
            functionCalling.configure(fcParams);
            fcParams.put("tool_choice", "required"); // Force tool call

            // Build prompt with group descriptions
            StringBuilder groupsList = new StringBuilder();
            for (int i = 0; i < toolGroups.size(); i++) {
                groupsList.append(i).append(". ").append(toolGroups.get(i).getGroupDescription()).append("\n");
            }

            // Set prompts required by connector (always override user input for function calling)
            fcParams.put(SYSTEM_PROMPT_FIELD, GROUP_SELECTION_SYSTEM_PROMPT);
            fcParams.put(USER_PROMPT_FIELD, "User query: " + parameters.get(QUESTION_FIELD) + "\n\nAvailable tool groups:\n" + groupsList);

            // Build and execute the prediction request directly
            log.info("[LATENCY] Group selection LLM request started at {}", System.currentTimeMillis());
            RemoteInferenceInputDataSet inputDataSet = RemoteInferenceInputDataSet.builder().parameters(fcParams).build();
            MLInput mlInput = MLInput.builder().algorithm(FunctionName.REMOTE).inputDataset(inputDataSet).build();
            MLPredictionTaskRequest request = MLPredictionTaskRequest.builder().modelId(modelId).mlInput(mlInput).build();

            client.execute(MLPredictionTaskAction.INSTANCE, request, ActionListener.wrap(response -> {
                try {
                    log.info("[LATENCY] Group selection LLM response received at {}", System.currentTimeMillis());

                    // Extract ModelTensorOutput from response
                    ModelTensorOutput modelOutput = (ModelTensorOutput) response.getOutput();
                    log.info("Received LLM response for group selection");

                    // Check if LLM produced text alongside tool call (adds latency)
                    if (modelOutput != null && modelOutput.getMlModelOutputs() != null && !modelOutput.getMlModelOutputs().isEmpty()) {
                        Map<String, ?> dataAsMap = modelOutput.getMlModelOutputs().get(0).getMlModelTensors().get(0).getDataAsMap();
                        if (dataAsMap != null && dataAsMap.containsKey("response")) {
                            String responseText = String.valueOf(dataAsMap.get("response"));
                            if (responseText != null && !responseText.trim().isEmpty() && !responseText.equals("null")) {
                                log
                                    .warn(
                                        "LLM produced text response alongside tool call in group selection (wastes tokens/latency): '{}'",
                                        responseText.length() > 100 ? responseText.substring(0, 100) + "..." : responseText
                                    );
                            }
                        }
                    }

                    List<Map<String, String>> toolCalls = functionCalling.handle(modelOutput, fcParams);

                    if (toolCalls == null || toolCalls.isEmpty()) {
                        listener.onFailure(new IllegalArgumentException("LLM did not call select_group function"));
                        return;
                    }

                    // Parse group_index from tool call
                    String toolInputJson = toolCalls.get(0).get("tool_input");
                    Map<String, Object> input = gson.fromJson(toolInputJson, new TypeToken<Map<String, Object>>() {
                    }.getType());
                    int selectedIndex = ((Number) input.get("group_index")).intValue();

                    if (selectedIndex < 0 || selectedIndex >= toolGroups.size()) {
                        listener
                            .onFailure(
                                new IllegalArgumentException(
                                    "Invalid group index: " + selectedIndex + " (valid range: 0-" + (toolGroups.size() - 1) + ")"
                                )
                            );
                        return;
                    }

                    ToolGroup selectedGroup = toolGroups.get(selectedIndex);
                    log.info("[LATENCY] Group selection completed at {}", System.currentTimeMillis());
                    log.info("Group selected via function calling: {} (index {})", selectedGroup.getGroupDescription(), selectedIndex);

                    loadToolsAndExecuteFunctionCalling(selectedGroup.getSearchTemplateTools(), parameters, listener);

                } catch (Exception e) {
                    listener.onFailure(new IllegalArgumentException("Failed to parse group selection", e));
                }
            }, listener::onFailure));

        } catch (Exception e) {
            log.error("Failed to execute group selection", e);
            listener.onFailure(e);
        }
    }

    private void getSampleDocAsync(String indexName, ActionListener<String> listener) {
        try {
            SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder()
                .size(1)
                .query(QueryBuilders.matchAllQuery())
                .trackTotalHits(false)
                .fetchSource(true)
                .explain(false)
                .profile(false)
                .sort("_doc");
            SearchRequest searchRequest = new SearchRequest().source(searchSourceBuilder).indices(indexName);

            ActionListener<SearchResponse> searchListener = new ActionListener<>() {
                @Override
                public void onResponse(SearchResponse searchResponse) {
                    try {
                        SearchHit[] hits = searchResponse.getHits().getHits();
                        if (hits == null || hits.length == 0) {
                            listener.onResponse(null);
                            return;
                        }

                        Map<String, Object> sourceMap = hits[0].getSourceAsMap();
                        if (sourceMap == null || sourceMap.isEmpty()) {
                            listener.onResponse(null);
                            return;
                        }

                        Map<String, String> truncatedSourceMap = new HashMap<>();
                        for (Map.Entry<String, Object> entry : sourceMap.entrySet()) {
                            String key = entry.getKey();
                            String value = String.valueOf(entry.getValue());
                            // safely process strings with special chars
                            int cpCount = value.codePointCount(0, value.length());
                            if (cpCount > MAX_TRUNCATE_CHARS) {
                                int end = value.offsetByCodePoints(0, MAX_TRUNCATE_CHARS);
                                truncatedSourceMap.put(key, TRUNC_PREFIX + value.substring(0, end));
                            } else {
                                truncatedSourceMap.put(key, value);
                            }
                        }
                        listener.onResponse(gson.toJson(truncatedSourceMap));
                    } catch (Exception e) {
                        log.error("Failed to process sample document");
                        listener.onFailure(new IOException("Failed to process sample document", e));
                    }
                }

                @Override
                public void onFailure(Exception e) {
                    log.error("Failed to get sample document");
                    listener.onFailure(new IOException("Failed to get sample document", e));
                }
            };

            client.search(searchRequest, searchListener);
        } catch (Exception e) {
            log.error("Failed to get sample document");
            listener.onFailure(new IOException("Failed to get sample document", e));
        }
    }

    private void getIndexMappingAsync(String indexName, ActionListener<String> listener) {
        try {
            GetIndexRequest getIndexRequest = new GetIndexRequest()
                .indices(indexName)
                .indicesOptions(IndicesOptions.strictExpand())
                .local(false)
                .clusterManagerNodeTimeout(DEFAULT_CLUSTER_MANAGER_NODE_TIMEOUT);

            client.admin().indices().getIndex(getIndexRequest, new ActionListener<GetIndexResponse>() {
                @Override
                public void onResponse(GetIndexResponse getIndexResponse) {
                    try {
                        MappingMetadata mapping = getIndexResponse.mappings().get(indexName);
                        listener.onResponse(mapping.source().toString());
                    } catch (Exception e) {
                        listener.onFailure(new IllegalStateException("Failed to extract index mapping", e));
                    }
                }

                @Override
                public void onFailure(Exception e) {
                    if (e instanceof IndexNotFoundException) {
                        log.warn("Index does not exist or is not available");
                        listener.onFailure(new IllegalArgumentException("Index does not exist or is not available", e));
                    } else {
                        log.warn("Failed to extract index mapping");
                        listener.onFailure(new IllegalStateException("Failed to extract index mapping", e));
                    }
                }
            });
        } catch (Exception e) {
            log.warn("Failed to extract index mapping");
            listener.onFailure(new IllegalStateException("Failed to extract index mapping", e));
        }
    }

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public String getVersion() {
        return null;
    }

    @Override
    public boolean validate(Map<String, String> parameters) {
        if (parameters == null
            || parameters.size() == 0
            || !parameters.containsKey(QUESTION_FIELD)
            || !parameters.containsKey(INDEX_NAME_FIELD)) {
            return false;
        }
        return true;
    }

    public static class Factory implements WithModelTool.Factory<QueryPlanningTool> {
        private Client client;
        private Map<String, Tool.Factory> toolFactories;
        private static volatile Factory INSTANCE;

        public void setToolFactories(Map<String, Tool.Factory> toolFactories) {
            this.toolFactories = toolFactories;
        }

        public static Factory getInstance() {
            if (INSTANCE != null) {
                return INSTANCE;
            }
            synchronized (QueryPlanningTool.class) {
                if (INSTANCE != null) {
                    return INSTANCE;
                }
                INSTANCE = new Factory();
                return INSTANCE;
            }
        }

        public void init(Client client) {
            this.client = client;
        }

        @Override
        public QueryPlanningTool create(Map<String, Object> params) {
            // Use agent's Agent model_id if tool doesn't have its own model_id
            if (!params.containsKey(MODEL_ID_FIELD) && params.containsKey(AGENT_LLM_MODEL_ID)) {
                params.put(MODEL_ID_FIELD, params.get(AGENT_LLM_MODEL_ID));
            }

            MLModelTool queryGenerationTool = MLModelTool.Factory.getInstance().create(params);

            String type = (String) params.get(GENERATION_TYPE_FIELD);

            // defaulted to llmGenerated
            if (type == null || type.isEmpty()) {
                type = LLM_GENERATED_TYPE_FIELD;
            }

            // type validation
            if (!(LLM_GENERATED_TYPE_FIELD.equals(type) || USER_SEARCH_TEMPLATES_TYPE_FIELD.equals(type))) {
                throw new IllegalArgumentException(
                    "Invalid generation type: " + type + ". The current supported types are llmGenerated and user_templates."
                );
            }

            // Parse search templates or tool groups if generation type is user_templates
            String searchTemplates = null;
            List<ToolGroup> toolGroups = null;

            if (USER_SEARCH_TEMPLATES_TYPE_FIELD.equals(type)) {
                boolean hasSearchTemplates = params.containsKey(SEARCH_TEMPLATES_FIELD);
                boolean hasToolGroups = params.containsKey(TOOL_GROUPS_FIELD);

                // Mutual exclusion validation
                if (hasSearchTemplates && hasToolGroups) {
                    throw new IllegalArgumentException("Cannot specify both 'search_templates' and 'tool_groups'");
                }

                if (!hasSearchTemplates && !hasToolGroups) {
                    throw new IllegalArgumentException(
                        "generation_type 'user_templates' requires either 'search_templates' or 'tool_groups'"
                    );
                }

                if (hasSearchTemplates) {
                    // Existing search_templates mode
                    String searchTemplatesJson = (String) params.get(SEARCH_TEMPLATES_FIELD);
                    validateSearchTemplates(searchTemplatesJson);
                    searchTemplates = gson.toJson(searchTemplatesJson);
                } else {
                    // New tool_groups mode - validate llm_interface required
                    if (!params.containsKey(LLM_INTERFACE_FIELD) || ((String) params.get(LLM_INTERFACE_FIELD)).isBlank()) {
                        throw new IllegalArgumentException("llm_interface is required when using tool_groups mode for function calling");
                    }
                    toolGroups = parseAndValidateToolGroups(params.get(TOOL_GROUPS_FIELD));
                }
            }

            // Extract modelId for direct LLM calls (custom tools mode)
            String modelId = (String) params.get(MODEL_ID_FIELD);

            QueryPlanningTool queryPlanningTool = new QueryPlanningTool(
                type,
                queryGenerationTool,
                client,
                searchTemplates,
                toolGroups,
                this.toolFactories,
                modelId
            );

            // Create parser with default extract_json processor + any custom processors
            queryPlanningTool.setOutputParser(createParserWithDefaultExtractJson(params));

            return queryPlanningTool;
        }

        /**
         * Create a parser with a default extract_json processor prepended to any custom processors.
         * This ensures that JSON is extracted from the LLM response before applying any custom processing.
         * 
         * @param params Tool parameters that may contain custom output_processors
         * @return Parser with extract_json as first processor, followed by any custom processors
         */
        private Parser createParserWithDefaultExtractJson(Map<String, Object> params) {
            // Extract any existing custom processors from params
            List<Map<String, Object>> customProcessorConfigs = ProcessorChain.extractProcessorConfigs(params);

            // Create the default extract_json processor config
            Map<String, Object> extractJsonConfig = new HashMap<>();
            extractJsonConfig.put("type", "extract_json");
            extractJsonConfig.put("extract_type", "object"); // Extract JSON objects only
            extractJsonConfig.put("default", DEFAULT_QUERY); // Return default match all query if no JSON found

            // Combine: default extract_json first, then any custom processors
            List<Map<String, Object>> combinedProcessorConfigs = new ArrayList<>();
            combinedProcessorConfigs.add(extractJsonConfig);
            combinedProcessorConfigs.addAll(customProcessorConfigs);

            // Create parser using the combined processor configs
            return ToolParser.createProcessingParser(null, combinedProcessorConfigs);
        }

        private void validateSearchTemplates(Object searchTemplatesObj) {
            List<Map<String, String>> templates = gson.fromJson(searchTemplatesObj.toString(), new TypeToken<List<Map<String, String>>>() {
            }.getType());

            for (Map<String, String> template : templates) {
                validateTemplateFields(template);
            }
        }

        private void validateTemplateFields(Map<String, String> template) {
            // Validate templateId
            String templateId = template.get(TEMPLATE_ID_FIELD);
            if (templateId == null || templateId.isBlank()) {
                throw new IllegalArgumentException("search_templates field entries must have a template_id");
            }

            // Validate templateDescription
            String templateDescription = template.get(TEMPLATE_DESCRIPTION_FIELD);
            if (templateDescription == null || templateDescription.isBlank()) {
                throw new IllegalArgumentException("search_templates field entries must have a template_description");
            }
        }

        @SuppressWarnings("unchecked")
        private List<ToolGroup> parseAndValidateToolGroups(Object toolGroupsObj) {
            List<Map<String, Object>> groupsJson = gson.fromJson(toolGroupsObj.toString(), new TypeToken<List<Map<String, Object>>>() {
            }.getType());

            if (groupsJson == null || groupsJson.isEmpty()) {
                throw new IllegalArgumentException("tool_groups cannot be empty");
            }

            List<ToolGroup> toolGroups = new ArrayList<>();
            for (int i = 0; i < groupsJson.size(); i++) {
                Map<String, Object> group = groupsJson.get(i);

                String description = (String) group.get(GROUP_DESCRIPTION_FIELD);
                if (description == null || description.isBlank()) {
                    throw new IllegalArgumentException("tool_groups[" + i + "] missing 'group_description'");
                }

                List<String> tools = (List<String>) group.get(SEARCHTEMPLATE_TOOLS_FIELD);
                if (tools == null || tools.isEmpty()) {
                    throw new IllegalArgumentException("tool_groups[" + i + "] missing 'searchTemplate_tools' array");
                }

                toolGroups.add(new ToolGroup(description, tools));
            }

            return toolGroups;
        }

        @Override
        public String getDefaultDescription() {
            return DEFAULT_DESCRIPTION;
        }

        @Override
        public String getDefaultType() {
            return TYPE;
        }

        @Override
        public String getDefaultVersion() {
            return null;
        }

        @Override
        public List<String> getAllModelKeys() {
            return List.of(MODEL_ID_FIELD);
        }
    }
}
