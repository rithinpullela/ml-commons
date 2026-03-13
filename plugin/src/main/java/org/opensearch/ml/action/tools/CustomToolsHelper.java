/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.action.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.opensearch.action.search.SearchRequest;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.core.action.ActionListener;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.ml.common.CommonValue;
import org.opensearch.ml.common.ToolMetadata;
import org.opensearch.ml.common.settings.MLFeatureEnabledSetting;
import org.opensearch.ml.engine.indices.MLIndicesHandler;
import org.opensearch.search.SearchHit;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.transport.client.Client;

import lombok.extern.log4j.Log4j2;

@Log4j2
public class CustomToolsHelper {
    private final Client client;
    private final ClusterService clusterService;
    private final MLFeatureEnabledSetting mlFeatureEnabledSetting;

    public CustomToolsHelper(Client client, ClusterService clusterService, MLFeatureEnabledSetting mlFeatureEnabledSetting) {
        this.client = client;
        this.clusterService = clusterService;
        this.mlFeatureEnabledSetting = mlFeatureEnabledSetting;
    }

    public static final int MAX_CUSTOM_TOOLS = 1000;

    public boolean customToolsIndexExists() {
        return MLIndicesHandler
            .doesMultiTenantIndexExist(clusterService, mlFeatureEnabledSetting.isMultiTenancyEnabled(), CommonValue.ML_CUSTOM_TOOLS_INDEX);
    }

    public void searchAllCustomTools(ActionListener<List<ToolMetadata>> listener) {
        if (!customToolsIndexExists()) {
            listener.onResponse(new ArrayList<>());
            return;
        }
        try (ThreadContext.StoredContext context = client.threadPool().getThreadContext().stashContext()) {
            SearchRequest searchRequest = new SearchRequest(CommonValue.ML_CUSTOM_TOOLS_INDEX);
            searchRequest.source(new SearchSourceBuilder().query(QueryBuilders.matchAllQuery()).size(MAX_CUSTOM_TOOLS));

            client.search(searchRequest, ActionListener.wrap(searchResponse -> {
                context.restore();
                List<ToolMetadata> customTools = new ArrayList<>();
                for (SearchHit hit : searchResponse.getHits().getHits()) {
                    customTools.add(parseHitToToolMetadata(hit));
                }
                listener.onResponse(customTools);
            }, e -> {
                context.restore();
                log.error("Failed to search custom tools", e);
                listener.onResponse(new ArrayList<>()); // graceful degradation
            }));
        }
    }

    public void searchCustomToolByName(String name, ActionListener<ToolMetadata> listener) {
        if (!customToolsIndexExists()) {
            listener.onResponse(null);
            return;
        }
        try (ThreadContext.StoredContext context = client.threadPool().getThreadContext().stashContext()) {
            SearchRequest searchRequest = new SearchRequest(CommonValue.ML_CUSTOM_TOOLS_INDEX);
            searchRequest.source(new SearchSourceBuilder().query(QueryBuilders.termQuery("name.keyword", name)).size(1));

            client.search(searchRequest, ActionListener.wrap(searchResponse -> {
                context.restore();
                if (searchResponse.getHits().getTotalHits().value() > 0) {
                    listener.onResponse(parseHitToToolMetadata(searchResponse.getHits().getHits()[0]));
                } else {
                    listener.onResponse(null);
                }
            }, e -> {
                context.restore();
                log.error("Failed to search custom tool by name", e);
                listener.onResponse(null);
            }));
        }
    }

    private ToolMetadata parseHitToToolMetadata(SearchHit hit) {
        Map<String, Object> source = hit.getSourceAsMap();
        return ToolMetadata
            .builder()
            .name((String) source.get("name"))
            .description((String) source.get("description"))
            .type((String) source.get("type"))
            .version(null)
            .build();
    }
}
