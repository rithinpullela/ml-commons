/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.rest;

import static org.opensearch.core.xcontent.XContentParserUtils.ensureExpectedToken;
import static org.opensearch.ml.plugin.MachineLearningPlugin.ML_BASE_URI;
import static org.opensearch.ml.utils.TenantAwareHelper.getTenantID;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.ml.common.settings.MLFeatureEnabledSetting;
import org.opensearch.ml.common.transport.tools.MLCustomToolInput;
import org.opensearch.ml.common.transport.tools.MLUpdateCustomToolAction;
import org.opensearch.ml.common.transport.tools.MLUpdateCustomToolRequest;
import org.opensearch.rest.BaseRestHandler;
import org.opensearch.rest.RestRequest;
import org.opensearch.rest.action.RestToXContentListener;
import org.opensearch.transport.client.node.NodeClient;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;

public class RestMLUpdateCustomToolAction extends BaseRestHandler {
    private static final String ML_UPDATE_CUSTOM_TOOL_ACTION = "ml_update_custom_tool_action";
    private static final String PARAMETER_TOOL_ID = "tool_name";
    private final MLFeatureEnabledSetting mlFeatureEnabledSetting;

    /**
     * Constructor
     * @param mlFeatureEnabledSetting the ML feature enabled setting
     */
    public RestMLUpdateCustomToolAction(MLFeatureEnabledSetting mlFeatureEnabledSetting) {
        this.mlFeatureEnabledSetting = mlFeatureEnabledSetting;
    }

    @Override
    public String getName() {
        return ML_UPDATE_CUSTOM_TOOL_ACTION;
    }

    @Override
    public List<Route> routes() {
        return ImmutableList
            .of(new Route(RestRequest.Method.PUT, String.format(Locale.ROOT, "%s/tools/{%s}", ML_BASE_URI, PARAMETER_TOOL_ID)));
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) throws IOException {
        MLUpdateCustomToolRequest updateRequest = getRequest(request);
        return channel -> client.execute(MLUpdateCustomToolAction.INSTANCE, updateRequest, new RestToXContentListener<>(channel));
    }

    /**
     * Creates a MLUpdateCustomToolRequest from a RestRequest
     * @param request RestRequest
     * @return MLUpdateCustomToolRequest
     * @throws IOException if the request body is empty or cannot be parsed
     */
    @VisibleForTesting
    MLUpdateCustomToolRequest getRequest(RestRequest request) throws IOException {
        if (!request.hasContent()) {
            throw new IOException("Update custom tool request has empty body");
        }
        String toolId = request.param(PARAMETER_TOOL_ID);
        XContentParser parser = request.contentParser();
        ensureExpectedToken(XContentParser.Token.START_OBJECT, parser.nextToken(), parser);
        MLCustomToolInput updateContent = MLCustomToolInput.parse(parser, true);
        String tenantId = getTenantID(mlFeatureEnabledSetting.isMultiTenancyEnabled(), request);
        return MLUpdateCustomToolRequest.builder().toolId(toolId).updateContent(updateContent).tenantId(tenantId).build();
    }
}
