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
import org.opensearch.ml.common.transport.tools.MLCreateCustomToolAction;
import org.opensearch.ml.common.transport.tools.MLCreateCustomToolRequest;
import org.opensearch.ml.common.transport.tools.MLCustomToolInput;
import org.opensearch.rest.BaseRestHandler;
import org.opensearch.rest.RestRequest;
import org.opensearch.rest.action.RestToXContentListener;
import org.opensearch.transport.client.node.NodeClient;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;

public class RestMLCreateCustomToolAction extends BaseRestHandler {
    private static final String ML_CREATE_CUSTOM_TOOL_ACTION = "ml_create_custom_tool_action";
    private final MLFeatureEnabledSetting mlFeatureEnabledSetting;

    /**
     * Constructor
     * @param mlFeatureEnabledSetting the ML feature enabled setting
     */
    public RestMLCreateCustomToolAction(MLFeatureEnabledSetting mlFeatureEnabledSetting) {
        this.mlFeatureEnabledSetting = mlFeatureEnabledSetting;
    }

    @Override
    public String getName() {
        return ML_CREATE_CUSTOM_TOOL_ACTION;
    }

    @Override
    public List<Route> routes() {
        return ImmutableList.of(new Route(RestRequest.Method.POST, String.format(Locale.ROOT, "%s/tools/_create", ML_BASE_URI)));
    }

    @Override
    public RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) throws IOException {
        MLCreateCustomToolRequest createRequest = getRequest(request);
        return channel -> client.execute(MLCreateCustomToolAction.INSTANCE, createRequest, new RestToXContentListener<>(channel));
    }

    /**
     * Creates a MLCreateCustomToolRequest from a RestRequest
     * @param request RestRequest
     * @return MLCreateCustomToolRequest
     * @throws IOException if the request body is empty or cannot be parsed
     */
    @VisibleForTesting
    MLCreateCustomToolRequest getRequest(RestRequest request) throws IOException {
        if (!request.hasContent()) {
            throw new IOException("Create custom tool request has empty body");
        }
        XContentParser parser = request.contentParser();
        ensureExpectedToken(XContentParser.Token.START_OBJECT, parser.nextToken(), parser);
        MLCustomToolInput input = MLCustomToolInput.parse(parser);
        String tenantId = getTenantID(mlFeatureEnabledSetting.isMultiTenancyEnabled(), request);
        input.setTenantId(tenantId);
        return new MLCreateCustomToolRequest(input);
    }
}
