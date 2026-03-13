/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.action.tools;

import static org.opensearch.ml.common.CommonValue.ML_CUSTOM_TOOLS_INDEX;

import java.time.Instant;

import org.opensearch.action.ActionRequest;
import org.opensearch.action.admin.cluster.storedscripts.GetStoredScriptRequest;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.HandledTransportAction;
import org.opensearch.action.update.UpdateResponse;
import org.opensearch.common.inject.Inject;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.core.action.ActionListener;
import org.opensearch.ml.common.settings.MLFeatureEnabledSetting;
import org.opensearch.ml.common.transport.tools.MLCustomToolInput;
import org.opensearch.ml.common.transport.tools.MLUpdateCustomToolAction;
import org.opensearch.ml.common.transport.tools.MLUpdateCustomToolRequest;
import org.opensearch.ml.utils.TenantAwareHelper;
import org.opensearch.remote.metadata.client.SdkClient;
import org.opensearch.remote.metadata.client.UpdateDataObjectRequest;
import org.opensearch.remote.metadata.common.SdkClientUtils;
import org.opensearch.tasks.Task;
import org.opensearch.transport.TransportService;
import org.opensearch.transport.client.Client;

import lombok.extern.log4j.Log4j2;

@Log4j2
public class UpdateCustomToolTransportAction extends HandledTransportAction<ActionRequest, UpdateResponse> {
    private final Client client;
    private final SdkClient sdkClient;
    private final MLFeatureEnabledSetting mlFeatureEnabledSetting;

    @Inject
    public UpdateCustomToolTransportAction(
        TransportService transportService,
        ActionFilters actionFilters,
        Client client,
        SdkClient sdkClient,
        MLFeatureEnabledSetting mlFeatureEnabledSetting
    ) {
        super(MLUpdateCustomToolAction.NAME, transportService, actionFilters, MLUpdateCustomToolRequest::new);
        this.client = client;
        this.sdkClient = sdkClient;
        this.mlFeatureEnabledSetting = mlFeatureEnabledSetting;
    }

    @Override
    protected void doExecute(Task task, ActionRequest request, ActionListener<UpdateResponse> listener) {
        MLUpdateCustomToolRequest updateRequest = MLUpdateCustomToolRequest.fromActionRequest(request);
        String toolId = updateRequest.getToolId();
        MLCustomToolInput updateContent = updateRequest.getUpdateContent();
        String tenantId = updateRequest.getTenantId();

        if (!TenantAwareHelper.validateTenantId(mlFeatureEnabledSetting, tenantId, listener)) {
            return;
        }

        // If search_template_name is being changed, validate it exists
        if (updateContent.getSearchTemplateName() != null) {
            GetStoredScriptRequest getScriptRequest = new GetStoredScriptRequest(updateContent.getSearchTemplateName());
            client.admin().cluster().getStoredScript(getScriptRequest, ActionListener.wrap(response -> {
                if (response.getSource() == null) {
                    listener
                        .onFailure(
                            new IllegalArgumentException("Search template '" + updateContent.getSearchTemplateName() + "' not found")
                        );
                    return;
                }
                performUpdate(toolId, updateContent, tenantId, listener);
            }, e -> listener.onFailure(new IllegalArgumentException("Failed to validate search template: " + e.getMessage()))));
        } else {
            performUpdate(toolId, updateContent, tenantId, listener);
        }
    }

    private void performUpdate(String toolId, MLCustomToolInput updateContent, String tenantId, ActionListener<UpdateResponse> listener) {
        try (ThreadContext.StoredContext context = client.threadPool().getThreadContext().stashContext()) {
            updateContent.setLastUpdateTime(Instant.now());

            sdkClient
                .updateDataObjectAsync(
                    UpdateDataObjectRequest
                        .builder()
                        .index(ML_CUSTOM_TOOLS_INDEX)
                        .id(toolId)
                        .tenantId(tenantId)
                        .dataObject(updateContent)
                        .build()
                )
                .whenComplete((r, throwable) -> {
                    context.restore();
                    if (throwable != null) {
                        Exception cause = SdkClientUtils.unwrapAndConvertToException(throwable);
                        log.error("Failed to update custom tool {}", toolId, cause);
                        listener.onFailure(cause);
                    } else {
                        try {
                            UpdateResponse updateResponse = r.updateResponse();
                            log.info("Custom tool updated: {}", toolId);
                            listener.onResponse(updateResponse);
                        } catch (Exception e) {
                            listener.onFailure(e);
                        }
                    }
                });
        } catch (Exception e) {
            log.error("Failed to update custom tool", e);
            listener.onFailure(e);
        }
    }
}
