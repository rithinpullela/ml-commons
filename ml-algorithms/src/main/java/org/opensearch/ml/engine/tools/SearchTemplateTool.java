/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.engine.tools;

import static org.opensearch.ml.common.CommonValue.TOOL_INPUT_SCHEMA_FIELD;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.common.xcontent.json.JsonXContent;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.ml.common.spi.tools.Parser;
import org.opensearch.ml.common.spi.tools.Tool;
import org.opensearch.ml.common.spi.tools.ToolAnnotation;
import org.opensearch.ml.engine.tools.parser.ToolParser;
import org.opensearch.script.Script;
import org.opensearch.script.ScriptService;
import org.opensearch.script.ScriptType;
import org.opensearch.script.TemplateScript;
import org.opensearch.search.SearchHit;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.transport.client.Client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;

/**
 * A tool that executes searches using pre-defined search templates (stored scripts).
 * <p>
 * The tool uses OpenSearch's {@code ScriptService} to compile and render stored Mustache templates,
 * providing full Mustache template rendering including sections ({{#param}}...{{/param}}),
 * inverted sections, and list iteration — without requiring a direct dependency on lang-mustache.
 * <p>
 * It supports three execution modes:
 * <ul>
 *   <li>{@code execute} (default) - renders the template and executes the search, returning results</li>
 *   <li>{@code render_only} - renders the template and returns the rendered query without executing</li>
 *   <li>{@code both} - renders the template, executes the search, and returns both the rendered query and results</li>
 * </ul>
 */
@Getter
@Setter
@Log4j2
@ToolAnnotation(SearchTemplateTool.TYPE)
public class SearchTemplateTool implements Tool {

    public static final String TYPE = "SearchTemplateTool";
    public static final String SEARCH_TEMPLATE_NAME_FIELD = "search_template_name";
    public static final String INDEX_FIELD = "index";
    public static final String PARAMS_FIELD = "params";
    public static final String EXECUTION_MODE_FIELD = "execution_mode";
    public static final String EXECUTION_MODE_EXECUTE = "execute";
    public static final String EXECUTION_MODE_RENDER_ONLY = "render_only";
    public static final String EXECUTION_MODE_BOTH = "both";

    private static final String DEFAULT_DESCRIPTION = "Use this tool to execute a search using a pre-defined search template. "
        + "Provide the required template parameters and the tool will render and execute the search.";

    private static final Gson GSON = new GsonBuilder().serializeSpecialFloatingPointValues().create();

    private String name = TYPE;
    private String description = DEFAULT_DESCRIPTION;
    private Map<String, Object> attributes;
    private Client client;
    private ScriptService scriptService;
    private NamedXContentRegistry xContentRegistry;
    private String searchTemplateName;
    private String index;
    private Map<String, Object> paramDefinitions;

    @Setter
    @Getter
    private Parser outputParser;

    public SearchTemplateTool(
        Client client,
        ScriptService scriptService,
        NamedXContentRegistry xContentRegistry,
        String searchTemplateName,
        String index,
        Map<String, Object> paramDefinitions
    ) {
        this.client = client;
        this.scriptService = scriptService;
        this.xContentRegistry = xContentRegistry;
        this.searchTemplateName = searchTemplateName;
        this.index = index;
        this.paramDefinitions = paramDefinitions;
        this.attributes = buildAttributesFromParamDefinitions(paramDefinitions);
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
        if (parameters == null || parameters.isEmpty()) {
            return false;
        }
        if (paramDefinitions == null) {
            return true;
        }
        for (Map.Entry<String, Object> entry : paramDefinitions.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> paramDef = (Map<String, Object>) entry.getValue();
            Object reqObj = paramDef.get("required");
            boolean required = reqObj instanceof Boolean ? (Boolean) reqObj : Boolean.parseBoolean(String.valueOf(reqObj));
            if (required && !parameters.containsKey(entry.getKey())) {
                log.error("Required parameter '{}' is missing", entry.getKey());
                return false;
            }
        }
        return true;
    }

    @Override
    public <T> void run(Map<String, String> parameters, ActionListener<T> listener) {
        try {
            if (!validate(parameters)) {
                listener.onFailure(new IllegalArgumentException("Missing required parameters for SearchTemplateTool"));
                return;
            }

            String executionMode = parameters.getOrDefault(EXECUTION_MODE_FIELD, EXECUTION_MODE_EXECUTE);
            Map<String, Object> scriptParams = buildScriptParams(parameters);

            // Render the stored Mustache template via ScriptService (uses MustacheScriptEngine)
            String renderedQuery = renderTemplate(scriptParams);
            log.info("SearchTemplateTool rendered template '{}', mode={}", searchTemplateName, executionMode);

            if (EXECUTION_MODE_RENDER_ONLY.equals(executionMode)) {
                respondWithResult(renderedQuery, listener);
            } else if (EXECUTION_MODE_BOTH.equals(executionMode)) {
                executeAndReturnBoth(renderedQuery, listener);
            } else {
                executeSearch(renderedQuery, listener);
            }
        } catch (Exception e) {
            log.error("Failed to run SearchTemplateTool", e);
            listener.onFailure(e);
        }
    }

    /**
     * Render a stored Mustache template using ScriptService.
     * The MustacheScriptEngine (from the lang-mustache module) is registered with ScriptService
     * at startup, so we get full Mustache support without a direct dependency on lang-mustache.
     */
    private String renderTemplate(Map<String, Object> scriptParams) {
        Script script = new Script(ScriptType.STORED, null, searchTemplateName, Collections.emptyMap());
        TemplateScript templateScript = scriptService.compile(script, TemplateScript.CONTEXT).newInstance(scriptParams);
        return templateScript.execute();
    }

    /**
     * Execute mode (default): execute the rendered query and return search results.
     */
    @SuppressWarnings("unchecked")
    private <T> void executeSearch(String renderedQuery, ActionListener<T> listener) {
        try {
            SearchRequest searchRequest = buildSearchRequest(renderedQuery);
            client.search(searchRequest, ActionListener.wrap(searchResponse -> {
                try {
                    String result = processSearchResponse(searchResponse);
                    respondWithResult(result, listener);
                } catch (Exception e) {
                    log.error("Failed to process search response", e);
                    listener.onFailure(e);
                }
            }, e -> {
                log.error("Failed to execute search", e);
                listener.onFailure(e);
            }));
        } catch (Exception e) {
            log.error("Failed to build search request from rendered template", e);
            listener.onFailure(e);
        }
    }

    /**
     * Both mode: return both the rendered query and search results.
     */
    @SuppressWarnings("unchecked")
    private <T> void executeAndReturnBoth(String renderedQuery, ActionListener<T> listener) {
        try {
            SearchRequest searchRequest = buildSearchRequest(renderedQuery);
            client.search(searchRequest, ActionListener.wrap(searchResponse -> {
                try {
                    Map<String, Object> combined = new HashMap<>();
                    combined.put("rendered_query", renderedQuery);
                    combined.put("search_results", processSearchResponse(searchResponse));
                    String result = GSON.toJson(combined);
                    respondWithResult(result, listener);
                } catch (Exception e) {
                    log.error("Failed to process search response", e);
                    listener.onFailure(e);
                }
            }, e -> {
                log.error("Failed to execute search", e);
                listener.onFailure(e);
            }));
        } catch (Exception e) {
            log.error("Failed to build search request from rendered template", e);
            listener.onFailure(e);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void respondWithResult(String result, ActionListener<T> listener) {
        if (outputParser != null) {
            listener.onResponse((T) outputParser.parse(result));
        } else {
            listener.onResponse((T) result);
        }
    }

    /**
     * Parse a rendered query JSON string into a SearchRequest.
     */
    private SearchRequest buildSearchRequest(String renderedQuery) throws Exception {
        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        XContentParser parser = JsonXContent.jsonXContent.createParser(xContentRegistry, null, renderedQuery);
        searchSourceBuilder.parseXContent(parser);
        SearchRequest searchRequest = new SearchRequest().source(searchSourceBuilder);
        if (index != null && !index.isEmpty()) {
            searchRequest.indices(index);
        }
        return searchRequest;
    }

    /**
     * Build script params from string parameters, converting types based on paramDefinitions.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildScriptParams(Map<String, String> parameters) {
        Map<String, Object> scriptParams = new HashMap<>();
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            if (EXECUTION_MODE_FIELD.equals(entry.getKey())) {
                continue;
            }
            if (paramDefinitions != null && paramDefinitions.containsKey(entry.getKey())) {
                Map<String, Object> def = (Map<String, Object>) paramDefinitions.get(entry.getKey());
                String type = (String) def.getOrDefault("type", "text");
                scriptParams.put(entry.getKey(), convertValue(entry.getValue(), type));
            } else {
                scriptParams.put(entry.getKey(), entry.getValue());
            }
        }
        return scriptParams;
    }

    /**
     * Process a SearchResponse into a newline-delimited JSON string of hit documents,
     * following the same pattern as SearchIndexTool.
     */
    private String processSearchResponse(SearchResponse searchResponse) {
        if (searchResponse == null) {
            return "";
        }
        SearchHit[] hits = searchResponse.getHits().getHits();
        if (hits != null && hits.length > 0) {
            StringBuilder sb = new StringBuilder();
            for (SearchHit hit : hits) {
                Map<String, Object> doc = new HashMap<>();
                doc.put("_index", hit.getIndex());
                doc.put("_id", hit.getId());
                doc.put("_score", hit.getScore());
                doc.put("_source", hit.getSourceAsMap());
                sb.append(GSON.toJson(doc)).append("\n");
            }
            return sb.toString();
        }
        return "";
    }

    /**
     * Convert a string value to the appropriate Java type based on the type descriptor
     * from the parameter definition.
     */
    private Object convertValue(String value, String type) {
        if (value == null) {
            return null;
        }
        try {
            switch (type) {
                case "integer":
                    return Integer.parseInt(value);
                case "long":
                    return Long.parseLong(value);
                case "float":
                    return Float.parseFloat(value);
                case "double":
                    return Double.parseDouble(value);
                case "boolean":
                    return Boolean.parseBoolean(value);
                default:
                    return value;
            }
        } catch (NumberFormatException e) {
            log.warn("Failed to convert '{}' to type '{}', using string value", value, type);
            return value;
        }
    }

    /**
     * Build the tool attributes (including input_schema) from the parameter definitions.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildAttributesFromParamDefinitions(Map<String, Object> paramDefs) {
        Map<String, Object> attrs = new HashMap<>();
        if (paramDefs == null || paramDefs.isEmpty()) {
            attrs.put(TOOL_INPUT_SCHEMA_FIELD, "{}");
            return attrs;
        }

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();
        List<String> required = new ArrayList<>();

        for (Map.Entry<String, Object> entry : paramDefs.entrySet()) {
            Map<String, Object> paramDef = (Map<String, Object>) entry.getValue();
            Map<String, Object> prop = new HashMap<>();
            prop.put("type", paramDef.getOrDefault("type", "string"));
            prop.put("description", paramDef.getOrDefault("description", ""));
            properties.put(entry.getKey(), prop);

            Object reqObj = paramDef.get("required");
            boolean isRequired = reqObj instanceof Boolean ? (Boolean) reqObj : Boolean.parseBoolean(String.valueOf(reqObj));
            if (isRequired) {
                required.add(entry.getKey());
            }
        }

        Map<String, Object> execModeProp = new HashMap<>();
        execModeProp.put("type", "string");
        execModeProp.put("description", "Execution mode: 'execute' (default), 'render_only', or 'both'");
        properties.put(EXECUTION_MODE_FIELD, execModeProp);

        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }

        attrs.put(TOOL_INPUT_SCHEMA_FIELD, GSON.toJson(schema));
        return attrs;
    }

    /**
     * Factory for creating SearchTemplateTool instances.
     */
    public static class Factory implements Tool.Factory<SearchTemplateTool> {

        private Client client;
        private ScriptService scriptService;
        private NamedXContentRegistry xContentRegistry;
        private static Factory INSTANCE;

        public static Factory getInstance() {
            if (INSTANCE != null) {
                return INSTANCE;
            }
            synchronized (SearchTemplateTool.class) {
                if (INSTANCE != null) {
                    return INSTANCE;
                }
                INSTANCE = new Factory();
                return INSTANCE;
            }
        }

        public void init(Client client, ScriptService scriptService, NamedXContentRegistry xContentRegistry) {
            this.client = client;
            this.scriptService = scriptService;
            this.xContentRegistry = xContentRegistry;
        }

        @SuppressWarnings("unchecked")
        @Override
        public SearchTemplateTool create(Map<String, Object> params) {
            String templateName = (String) params.get(SEARCH_TEMPLATE_NAME_FIELD);
            if (templateName == null) {
                throw new IllegalArgumentException("search_template_name is required for SearchTemplateTool");
            }

            String index = (String) params.get(INDEX_FIELD);

            Map<String, Object> paramDefs = null;
            Object paramsObj = params.get(PARAMS_FIELD);
            if (paramsObj instanceof Map) {
                paramDefs = (Map<String, Object>) paramsObj;
            } else if (paramsObj instanceof String) {
                paramDefs = GSON.fromJson((String) paramsObj, Map.class);
            }

            SearchTemplateTool tool = new SearchTemplateTool(client, scriptService, xContentRegistry, templateName, index, paramDefs);
            tool.setOutputParser(ToolParser.createFromToolParams(params));
            return tool;
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
    }
}
