/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.engine.tools;

import static org.opensearch.ml.common.CommonValue.*;
import static org.opensearch.ml.common.CommonValue.ML_MODEL_GROUP_INDEX;
import static org.opensearch.ml.common.settings.MLCommonsSettings.ML_COMMONS_AGENTIC_SEARCH_DISABLED_MESSAGE;
import static org.opensearch.ml.common.utils.StringUtils.gson;
import static org.opensearch.ml.engine.algorithms.agent.AgentUtils.getCurrentDateTime;
import static org.opensearch.ml.engine.tools.QueryPlanningPromptTemplate.DEFAULT_QUERY;
import static org.opensearch.ml.engine.tools.QueryPlanningPromptTemplate.DEFAULT_QUERY_PLANNING_SYSTEM_PROMPT;
import static org.opensearch.ml.engine.tools.QueryPlanningPromptTemplate.DEFAULT_QUERY_PLANNING_USER_PROMPT;
import static org.opensearch.ml.engine.tools.QueryPlanningPromptTemplate.DEFAULT_SEARCH_TEMPLATE;
import static org.opensearch.ml.engine.tools.QueryPlanningPromptTemplate.TEMPLATE_SELECTION_SYSTEM_PROMPT;
import static org.opensearch.ml.engine.tools.QueryPlanningPromptTemplate.TEMPLATE_SELECTION_USER_PROMPT;
import org.opensearch.action.search.SearchRequest;

import java.io.IOException;
import java.util.*;

import org.apache.commons.text.StringSubstitutor;
import org.opensearch.OpenSearchException;
import org.opensearch.action.admin.cluster.storedscripts.GetStoredScriptRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.xcontent.LoggingDeprecationHandler;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.ml.common.settings.MLFeatureEnabledSetting;
import org.opensearch.ml.common.spi.tools.ToolAnnotation;
import org.opensearch.ml.common.spi.tools.WithModelTool;
import org.opensearch.ml.common.transport.connector.MLConnectorSearchAction;
import org.opensearch.ml.common.transport.model.MLModelSearchAction;
import org.opensearch.ml.common.transport.model_group.MLModelGroupSearchAction;
import org.opensearch.ml.common.utils.ToolUtils;
import org.opensearch.search.SearchHit;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.transport.client.Client;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;

/**
 * This tool supports different types of query planning,
 * llmGenerated, systemSearchTemplates or userSearchTemplates.
 * //TODO only support llmGenerated for now.
 * //TODO to add in systemSearchTemplates or userSearchTemplates when searchTemplatesTool is implemented.
 */

@Log4j2
@ToolAnnotation(QueryPlanningTool.TYPE)
public class QueryPlanningTool implements WithModelTool {
    public static final String TYPE = "QueryPlanningTool";
    public static final String MODEL_ID_FIELD = "model_id";
    private final MLModelTool queryGenerationTool;
    public static final String SYSTEM_PROMPT_FIELD = "query_planner_system_prompt";
    public static final String USER_PROMPT_FIELD = "query_planner_user_prompt";
    public static final String INDEX_MAPPING_FIELD = "index_mapping";
    public static final String QUERY_FIELDS_FIELD = "query_fields";
    private static final String GENERATION_TYPE_FIELD = "generation_type";
    private static final String LLM_GENERATED_TYPE_FIELD = "llmGenerated";
    private static final String USER_SEARCH_TEMPLATES_TYPE_FIELD = "user_templates";
    private static final String SEARCH_TEMPLATES_FIELD = "search_templates";
    private static final String SAMPLE_DOCUMENT_FIELD = "sample_document";
    private static final String CURRENT_TIME_FIELD = "current_time";
    public static final String TEMPLATE_FIELD = "template";
    public static final String STRICT_FIELD = "strict";
    public static final String QUESTION_FIELD = "question";
    public static final String INDEX_NAME_FIELD = "index_name";
    @Getter
    private final String generationType;
    @Getter
    private final String searchTemplates;
    @Setter
    @Getter
    private String name = TYPE;
    @Getter
    @Setter
    private Map<String, Object> attributes;
    static String DEFAULT_DESCRIPTION = "Use this tool to generate OpenSearch Query DSL from natural language queries. "
        + "Provide a 'question' parameter containing the complete natural language query with all necessary context, requirements, filters, and constraints. "
        + "The question should be self-contained with all information needed to generate the OpenSearch DSL. "
        + "Provide 'index_name' to help generate more accurate queries based on the index structure. "
        + "The tool will return a valid OpenSearch query that can be used to search your data.";

    public static final String DEFAULT_INPUT_SCHEMA = "{"
        + "\"type\":\"object\","
        + "\"properties\":{"
        + "\"question\":{\"type\":\"string\",\"description\":\"Complete natural language query with all necessary context to generate OpenSearch DSL. Include the question, any specific requirements, filters, or constraints. Examples: 'Find all products with price greater than 100 dollars', 'Show me documents about machine learning published in 2023', 'Search for users with status active and age between 25 and 35'\"},"
        + "\"index_name\":{\"type\":\"string\",\"description\":\"the name of the index against which the query needs to be generated.\"}" +
            ""
        + "},"
        + "\"required\":[\"question\", \"index_name\"],"
        + "\"additionalProperties\":false"
        + "}";

    public static final Map<String, Object> DEFAULT_ATTRIBUTES = Map.of(TOOL_INPUT_SCHEMA_FIELD, DEFAULT_INPUT_SCHEMA, STRICT_FIELD, false);
    @Getter
    @Setter
    private String description = DEFAULT_DESCRIPTION;
    private final Client client;
    private final ClusterService clusterService;
    private NamedXContentRegistry xContentRegistry;

    public QueryPlanningTool(
        String generationType,
        MLModelTool queryGenerationTool,
        Client client,
        String searchTemplates,
        ClusterService clusterService,
        NamedXContentRegistry xContentRegistry
    ) {
        this.generationType = generationType;
        this.queryGenerationTool = queryGenerationTool;
        this.client = client;
        this.searchTemplates = searchTemplates;
        this.attributes = new HashMap<>(DEFAULT_ATTRIBUTES);
        this.clusterService = clusterService;
        this.xContentRegistry = xContentRegistry;
    }

    @Override
    public <T> void run(Map<String, String> originalParameters, ActionListener<T> listener) {
        try {
            Map<String, String> parameters = ToolUtils.extractInputParameters(originalParameters, attributes);
            if (!validate(parameters)) {
                listener.onFailure(new IllegalArgumentException("Empty parameters for QueryPlanningTool: " + parameters));
                return;
            }

            if (!generationType.equals(USER_SEARCH_TEMPLATES_TYPE_FIELD)) {
                // Use default search template, skip template selection
                parameters.put(TEMPLATE_FIELD, DEFAULT_SEARCH_TEMPLATE);
                executeQueryPlanning(parameters, listener);
                return;
            }

            // Template Selection, replace user and system prompts
            Map<String, String> templateSelectionParameters = new HashMap<>(parameters);
            templateSelectionParameters.put(SYSTEM_PROMPT_FIELD, TEMPLATE_SELECTION_SYSTEM_PROMPT);
            templateSelectionParameters.put(USER_PROMPT_FIELD, TEMPLATE_SELECTION_USER_PROMPT);
            templateSelectionParameters.put(SEARCH_TEMPLATES_FIELD, searchTemplates);

            ActionListener<T> templateSelectionListener = ActionListener.wrap(r -> {
                try {
                    String templateId = (String) r;
                    if (templateId == null || templateId.isBlank() || templateId.equals("null")) {
                        // Default search template if LLM does not choose
                        parameters.put(TEMPLATE_FIELD, DEFAULT_SEARCH_TEMPLATE);
                        executeQueryPlanning(parameters, listener);
                    } else {
                        // Retrieve search template by ID
                        GetStoredScriptRequest getStoredScriptRequest = new GetStoredScriptRequest(templateId);
                        client.admin().cluster().getStoredScript(getStoredScriptRequest, ActionListener.wrap(getStoredScriptResponse -> {
                            parameters.put(TEMPLATE_FIELD, gson.toJson(getStoredScriptResponse.getSource().getSource()));
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
            log.error("Failed to run SearchIndexTool", e);
            listener.onFailure(e);
        }

    }

    private <T> void executeQueryPlanning(Map<String, String> parameters, ActionListener<T> listener) {
        try {
            // Execute Query Planning, replace System and User prompt fields
            if (!parameters.containsKey(SYSTEM_PROMPT_FIELD)) {
                parameters.put(SYSTEM_PROMPT_FIELD, DEFAULT_QUERY_PLANNING_SYSTEM_PROMPT);
            }
            if (!parameters.containsKey(USER_PROMPT_FIELD)) {
                parameters.put(USER_PROMPT_FIELD, DEFAULT_QUERY_PLANNING_USER_PROMPT);
            }

            String indexMapping = getIndexMapping(parameters.get(INDEX_NAME_FIELD));
            parameters.put(INDEX_MAPPING_FIELD, gson.toJson(indexMapping));

            String sampleDoc = getSampleDoc(parameters.get(INDEX_NAME_FIELD));
            parameters.put(SAMPLE_DOCUMENT_FIELD,  gson.toJson(sampleDoc));

            String currentDateTime = getCurrentDateTime("");
            parameters.put(CURRENT_TIME_FIELD,  gson.toJson(currentDateTime));

            if (parameters.containsKey(QUERY_FIELDS_FIELD)) {
                parameters.put(QUERY_FIELDS_FIELD, gson.toJson(parameters.get(QUERY_FIELDS_FIELD)));
            }
            ActionListener<T> modelListener = ActionListener.wrap(r -> {
                try {
                    String queryString = (String) r;
                    if (queryString == null || queryString.isBlank() || queryString.equals("null")) {
                        StringSubstitutor substitutor = new StringSubstitutor(parameters, "${parameters.", "}");
                        String defaultQueryString = substitutor.replace(DEFAULT_QUERY);
                        listener.onResponse((T) defaultQueryString);
                    } else {
                        listener.onResponse((T) queryString);
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
        } catch (Exception e) {
            log.error("Failed to run SearchIndexTool", e);
            listener.onFailure(e);
        }

    }

    private String getSampleDoc(String indexName) throws IOException {
        String query = "{\"size\":1,\"query\":{\"match_all\":{}}}";
        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        XContentParser queryParser = XContentType.JSON.xContent().createParser(xContentRegistry, LoggingDeprecationHandler.INSTANCE, query);
        searchSourceBuilder.parseXContent(queryParser);
        SearchRequest searchRequest = new SearchRequest().source(searchSourceBuilder).indices(indexName);

        try {
            SearchResponse searchResponse;
            
            // Execute the search request based on index type
            if (Objects.equals(indexName, ML_CONNECTOR_INDEX)) {
                searchResponse = client.execute(MLConnectorSearchAction.INSTANCE, searchRequest).actionGet();
            } else if (Objects.equals(indexName, ML_MODEL_INDEX)) {
                searchResponse = client.execute(MLModelSearchAction.INSTANCE, searchRequest).actionGet();
            } else if (Objects.equals(indexName, ML_MODEL_GROUP_INDEX)) {
                searchResponse = client.execute(MLModelGroupSearchAction.INSTANCE, searchRequest).actionGet();
            } else {
                searchResponse = client.search(searchRequest).actionGet();
            }

            SearchHit[] hits = searchResponse.getHits().getHits();
            if (hits != null && hits.length > 0) {
                // Return only the document content (_source field) as JSON string
                Map<String, Object> sourceMap = hits[0].getSourceAsMap();
                if (sourceMap != null && !sourceMap.isEmpty()) {
                    return gson.toJson(sourceMap);
                }
            }
            
            // No hits found, return empty string
            return "";
        } catch (Exception e) {
            log.error("Failed to get sample document from index: {}", indexName, e);
            throw new IOException("Failed to get sample document from index: " + indexName, e);
        }
    }

    private String getIndexMapping(String indexName) {
        try {
            IndexMetadata indexMetadata = clusterService.state().metadata().index(indexName);
            if (indexMetadata == null) {
                log.warn("Index '{}' does not exist or is not available", indexName);
                throw new IllegalStateException("Index '" + indexName + "' does not exist or is not available");
            }
            return indexMetadata.mapping().source().toString();
        } catch (Exception e) {
            log.warn("Failed to extract index mapping for index '{}'", indexName, e);
            throw new IllegalStateException("Failed to extract index mapping for index '" + indexName + "'", e);
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
        private static volatile Factory INSTANCE;
        private static MLFeatureEnabledSetting mlFeatureEnabledSetting;
        private ClusterService clusterService;
        private NamedXContentRegistry xContentRegistry;

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

        public void init(Client client, MLFeatureEnabledSetting mlFeatureEnabledSetting, ClusterService clusterService, NamedXContentRegistry xContentRegistry) {
            this.client = client;
            this.mlFeatureEnabledSetting = mlFeatureEnabledSetting;
            this.clusterService = clusterService;
            this.xContentRegistry = xContentRegistry;
        }

        @Override
        public QueryPlanningTool create(Map<String, Object> map) {

            if (!mlFeatureEnabledSetting.isAgenticSearchEnabled()) {
                throw new OpenSearchException(ML_COMMONS_AGENTIC_SEARCH_DISABLED_MESSAGE);
            }

            MLModelTool queryGenerationTool = MLModelTool.Factory.getInstance().create(map);

            String type = (String) map.get(GENERATION_TYPE_FIELD);

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

            // Parse search templates if generation type is user_templates
            String searchTemplates = null;
            if (USER_SEARCH_TEMPLATES_TYPE_FIELD.equals(type)) {
                if (!map.containsKey(SEARCH_TEMPLATES_FIELD)) {
                    throw new IllegalArgumentException("search_templates field is required when generation_type is 'user_templates'");
                } else {
                    // array is parsed as a json string
                    searchTemplates = gson.toJson((String) map.get(SEARCH_TEMPLATES_FIELD));

                }
            }

            return new QueryPlanningTool(type, queryGenerationTool, client, searchTemplates, clusterService, xContentRegistry);
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
