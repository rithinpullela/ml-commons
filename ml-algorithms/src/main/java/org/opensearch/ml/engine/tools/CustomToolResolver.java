/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.engine.tools;

import java.util.Map;

import org.opensearch.action.search.SearchRequest;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.core.action.ActionListener;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.ml.common.CommonValue;
import org.opensearch.ml.common.agent.MLToolSpec;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.transport.client.Client;

import lombok.extern.log4j.Log4j2;

/**
 * Async resolver that queries the .plugins-ml-custom-tools index by name
 * and returns the full tool definition.
 */
@Log4j2
public class CustomToolResolver {
    /**
     * For Tool Execute API — does this request need custom tool resolution?
     */
    public static boolean needsResolution(String toolType, String name) {
        return SearchTemplateTool.TYPE.equals(toolType) && name != null;
    }

    /**
     * For Agent toolSpec — does this spec need custom tool resolution?
     * A SearchTemplateTool needs resolution when it has a name (lookup key)
     * but no inline search_template_name in parameters.
     */
    public static boolean needsResolution(MLToolSpec toolSpec) {
        if (!SearchTemplateTool.TYPE.equals(toolSpec.getType())) {
            return false;
        }
        return toolSpec.getName() != null
            && (toolSpec.getParameters() == null || !toolSpec.getParameters().containsKey(SearchTemplateTool.SEARCH_TEMPLATE_NAME_FIELD));
    }

    private final Client client;

    public CustomToolResolver(Client client) {
        this.client = client;
    }

    /**
     * Resolves a pre-registered tool by name from the custom tools index.
     *
     * @param name the name of the tool to resolve
     * @param listener the listener to notify with the tool definition or error
     */
    public void resolve(String name, ActionListener<Map<String, Object>> listener) {
        try (ThreadContext.StoredContext context = client.threadPool().getThreadContext().stashContext()) {
            SearchRequest searchRequest = new SearchRequest(CommonValue.ML_CUSTOM_TOOLS_INDEX);
            searchRequest.source(new SearchSourceBuilder().query(QueryBuilders.termQuery("name.keyword", name)).size(1));

            client.search(searchRequest, ActionListener.wrap(searchResponse -> {
                context.restore();
                if (searchResponse.getHits().getTotalHits().value() > 0) {
                    listener.onResponse(searchResponse.getHits().getHits()[0].getSourceAsMap());
                } else {
                    listener.onFailure(new IllegalArgumentException("Pre-registered tool not found: " + name));
                }
            }, e -> {
                context.restore();
                log.error("Failed to resolve pre-registered tool by name: {}", name, e);
                listener.onFailure(e);
            }));
        }
    }
}
