/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.engine.agents.v2.tools;

import static org.opensearch.ml.common.CommonValue.ML_CONNECTOR_INDEX;
import static org.opensearch.ml.common.CommonValue.ML_MODEL_GROUP_INDEX;
import static org.opensearch.ml.common.CommonValue.ML_MODEL_INDEX;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.common.xcontent.LoggingDeprecationHandler;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.ml.common.agent.v2.AgentToolV2;
import org.opensearch.ml.common.agent.v2.AgentToolV2Factory;
import org.opensearch.ml.common.agent.v2.ToolCallResult;
import org.opensearch.ml.common.agent.v2.ToolExecutionContext;
import org.opensearch.ml.common.agent.v2.ToolSpec;
import org.opensearch.ml.common.transport.connector.MLConnectorSearchAction;
import org.opensearch.ml.common.transport.model.MLModelSearchAction;
import org.opensearch.ml.common.transport.model_group.MLModelGroupSearchAction;
import org.opensearch.ml.common.utils.StringUtils;
import org.opensearch.search.SearchHit;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.transport.client.Client;

import lombok.extern.log4j.Log4j2;

/**
 * Native V2 search index tool.
 * Accepts structured Map&lt;String,Object&gt; arguments directly from LLM function calls —
 * the query object comes as a pre-parsed Map, eliminating JSON string parsing overhead.
 */
@Log4j2
public class SearchIndexToolV2 implements AgentToolV2 {

    public static final String TYPE = "SearchIndexToolV2";
    private static final String DEFAULT_DESCRIPTION =
        "Use this tool to search an OpenSearch index. Provide 'index' (the index name) and 'query' (a valid OpenSearch DSL query object). Returns matching documents.";

    private static final Map<String, Object> INPUT_SCHEMA = Map
        .of(
            "type",
            "object",
            "properties",
            Map
                .of(
                    "index",
                    Map.of("type", "string", "description", "OpenSearch index name to search"),
                    "query",
                    Map
                        .of(
                            "type",
                            "object",
                            "description",
                            "OpenSearch DSL query object (e.g. {\"query\":{\"match\":{\"field\":\"value\"}},\"size\":10})"
                        )
                ),
            "required",
            List.of("index", "query")
        );

    private static final ToolSpec DEFAULT_TOOL_SPEC = new ToolSpec(TYPE, DEFAULT_DESCRIPTION, INPUT_SCHEMA);

    private final Client client;
    private final NamedXContentRegistry xContentRegistry;
    private String name = TYPE;
    private String description = DEFAULT_DESCRIPTION;

    public SearchIndexToolV2(Client client, NamedXContentRegistry xContentRegistry) {
        this.client = client;
        this.xContentRegistry = xContentRegistry;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public ToolSpec getToolSpec() {
        return new ToolSpec(name, description, INPUT_SCHEMA);
    }

    @Override
    public String validateInput(Map<String, Object> arguments) {
        if (arguments == null || !arguments.containsKey("index") || !arguments.containsKey("query")) {
            return "Both 'index' and 'query' parameters are required";
        }
        return null;
    }

    @Override
    public void execute(Map<String, Object> arguments, ToolExecutionContext context, ActionListener<ToolCallResult> listener) {
        try {
            String index = (String) arguments.get("index");
            Object queryObj = arguments.get("query");

            if (index == null || index.isEmpty()) {
                listener.onResponse(ToolCallResult.error(context.getToolCallId(), name, "Index name is required"));
                return;
            }

            // Convert query object to JSON string for SearchSourceBuilder parsing
            String queryJson;
            if (queryObj instanceof Map) {
                queryJson = StringUtils.toJson(queryObj);
            } else if (queryObj instanceof String) {
                queryJson = (String) queryObj;
            } else {
                listener.onResponse(ToolCallResult.error(context.getToolCallId(), name, "Query must be a JSON object or string"));
                return;
            }

            SearchRequest searchRequest = buildSearchRequest(index, queryJson);

            ActionListener<SearchResponse> searchListener = ActionListener.wrap(response -> {
                SearchHit[] hits = response.getHits().getHits();
                StringBuilder result = new StringBuilder();
                if (hits != null && hits.length > 0) {
                    for (SearchHit hit : hits) {
                        Map<String, Object> doc = new HashMap<>();
                        doc.put("_index", hit.getIndex());
                        doc.put("_id", hit.getId());
                        doc.put("_score", hit.getScore());
                        doc.put("_source", hit.getSourceAsMap());
                        result.append(StringUtils.toJson(doc)).append("\n");
                    }
                }
                listener.onResponse(ToolCallResult.success(context.getToolCallId(), name, result.toString()));
            }, e -> {
                log.error("SearchIndexToolV2 search failed for index: {}", index, e);
                listener.onResponse(ToolCallResult.error(context.getToolCallId(), name, "Search failed: " + e.getMessage()));
            });

            // Route protected indices through their transport actions
            if (Objects.equals(index, ML_CONNECTOR_INDEX)) {
                client.execute(MLConnectorSearchAction.INSTANCE, searchRequest, searchListener);
            } else if (Objects.equals(index, ML_MODEL_INDEX)) {
                client.execute(MLModelSearchAction.INSTANCE, searchRequest, searchListener);
            } else if (Objects.equals(index, ML_MODEL_GROUP_INDEX)) {
                client.execute(MLModelGroupSearchAction.INSTANCE, searchRequest, searchListener);
            } else {
                client.search(searchRequest, searchListener);
            }

        } catch (Exception e) {
            log.error("SearchIndexToolV2 execution failed", e);
            listener.onResponse(ToolCallResult.error(context.getToolCallId(), name, "Execution error: " + e.getMessage()));
        }
    }

    private SearchRequest buildSearchRequest(String index, String queryJson) throws IOException {
        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        XContentParser queryParser = XContentType.JSON
            .xContent()
            .createParser(xContentRegistry, LoggingDeprecationHandler.INSTANCE, queryJson);
        searchSourceBuilder.parseXContent(queryParser);
        return new SearchRequest().source(searchSourceBuilder).indices(index);
    }

    /**
     * V2 Factory for SearchIndexToolV2.
     */
    public static class Factory implements AgentToolV2Factory {

        private Client client;
        private NamedXContentRegistry xContentRegistry;
        private static Factory INSTANCE;

        public static Factory getInstance() {
            if (INSTANCE != null) {
                return INSTANCE;
            }
            synchronized (Factory.class) {
                if (INSTANCE != null) {
                    return INSTANCE;
                }
                INSTANCE = new Factory();
                return INSTANCE;
            }
        }

        public void init(Client client, NamedXContentRegistry xContentRegistry) {
            this.client = client;
            this.xContentRegistry = xContentRegistry;
        }

        @Override
        public AgentToolV2 create(Map<String, Object> params) {
            SearchIndexToolV2 tool = new SearchIndexToolV2(client, xContentRegistry);
            if (params != null && params.containsKey("name")) {
                tool.name = (String) params.get("name");
            }
            if (params != null && params.containsKey("description")) {
                tool.description = (String) params.get("description");
            }
            return tool;
        }

        @Override
        public String getDefaultType() {
            return TYPE;
        }

        @Override
        public ToolSpec getDefaultToolSpec() {
            return DEFAULT_TOOL_SPEC;
        }
    }
}
