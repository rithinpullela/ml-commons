/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.rest;

import static org.opensearch.ml.plugin.MachineLearningPlugin.ML_BASE_URI;
import static org.opensearch.ml.utils.TenantAwareHelper.getTenantID;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import org.opensearch.ml.common.settings.MLFeatureEnabledSetting;
import org.opensearch.ml.common.transport.tools.MLDeleteCustomToolAction;
import org.opensearch.ml.common.transport.tools.MLDeleteCustomToolRequest;
import org.opensearch.rest.BaseRestHandler;
import org.opensearch.rest.RestRequest;
import org.opensearch.rest.action.RestToXContentListener;
import org.opensearch.transport.client.node.NodeClient;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;

/**
 * This class consists of the REST handler to delete a custom ML tool.
 */
public class RestMLDeleteCustomToolAction extends BaseRestHandler {
    private static final String ML_DELETE_CUSTOM_TOOL_ACTION = "ml_delete_custom_tool_action";
    private static final String PARAMETER_TOOL_ID = "tool_name";
    private final MLFeatureEnabledSetting mlFeatureEnabledSetting;

    /**
     * Constructor
     * @param mlFeatureEnabledSetting the ML feature enabled setting
     */
    public RestMLDeleteCustomToolAction(MLFeatureEnabledSetting mlFeatureEnabledSetting) {
        this.mlFeatureEnabledSetting = mlFeatureEnabledSetting;
    }

    @Override
    public String getName() {
        return ML_DELETE_CUSTOM_TOOL_ACTION;
    }

    @Override
    public List<Route> routes() {
        return ImmutableList
            .of(new Route(RestRequest.Method.DELETE, String.format(Locale.ROOT, "%s/tools/{%s}", ML_BASE_URI, PARAMETER_TOOL_ID)));
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) throws IOException {
        MLDeleteCustomToolRequest deleteRequest = getRequest(request);
        return channel -> client.execute(MLDeleteCustomToolAction.INSTANCE, deleteRequest, new RestToXContentListener<>(channel));
    }

    /**
     * Creates a MLDeleteCustomToolRequest from a RestRequest
     * @param request RestRequest
     * @return MLDeleteCustomToolRequest
     * @throws IOException if the request cannot be parsed
     */
    @VisibleForTesting
    MLDeleteCustomToolRequest getRequest(RestRequest request) throws IOException {
        String toolId = request.param(PARAMETER_TOOL_ID);
        String tenantId = getTenantID(mlFeatureEnabledSetting.isMultiTenancyEnabled(), request);
        return MLDeleteCustomToolRequest.builder()
            .toolId(toolId)
            .tenantId(tenantId)
            .build();
    }
}
