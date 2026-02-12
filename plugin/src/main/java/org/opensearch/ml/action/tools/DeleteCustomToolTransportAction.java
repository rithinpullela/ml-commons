/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.action.tools;

import static org.opensearch.ml.common.CommonValue.ML_CUSTOM_TOOLS_INDEX;

import org.opensearch.action.ActionRequest;
import org.opensearch.action.delete.DeleteResponse;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.HandledTransportAction;
import org.opensearch.common.inject.Inject;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.core.action.ActionListener;
import org.opensearch.ml.common.settings.MLFeatureEnabledSetting;
import org.opensearch.ml.common.transport.tools.MLDeleteCustomToolAction;
import org.opensearch.ml.common.transport.tools.MLDeleteCustomToolRequest;
import org.opensearch.ml.utils.TenantAwareHelper;
import org.opensearch.remote.metadata.client.DeleteDataObjectRequest;
import org.opensearch.remote.metadata.client.SdkClient;
import org.opensearch.remote.metadata.common.SdkClientUtils;
import org.opensearch.tasks.Task;
import org.opensearch.transport.TransportService;
import org.opensearch.transport.client.Client;

import lombok.extern.log4j.Log4j2;

@Log4j2
public class DeleteCustomToolTransportAction extends HandledTransportAction<ActionRequest, DeleteResponse> {
    private final Client client;
    private final SdkClient sdkClient;
    private final MLFeatureEnabledSetting mlFeatureEnabledSetting;

    @Inject
    public DeleteCustomToolTransportAction(
        TransportService transportService,
        ActionFilters actionFilters,
        Client client,
        SdkClient sdkClient,
        MLFeatureEnabledSetting mlFeatureEnabledSetting
    ) {
        super(MLDeleteCustomToolAction.NAME, transportService, actionFilters, MLDeleteCustomToolRequest::new);
        this.client = client;
        this.sdkClient = sdkClient;
        this.mlFeatureEnabledSetting = mlFeatureEnabledSetting;
    }

    @Override
    protected void doExecute(Task task, ActionRequest request, ActionListener<DeleteResponse> listener) {
        MLDeleteCustomToolRequest deleteRequest = MLDeleteCustomToolRequest.fromActionRequest(request);
        String toolId = deleteRequest.getToolId();
        String tenantId = deleteRequest.getTenantId();

        if (!TenantAwareHelper.validateTenantId(mlFeatureEnabledSetting, tenantId, listener)) {
            return;
        }

        try (ThreadContext.StoredContext context = client.threadPool().getThreadContext().stashContext()) {
            sdkClient
                .deleteDataObjectAsync(
                    DeleteDataObjectRequest
                        .builder()
                        .index(ML_CUSTOM_TOOLS_INDEX)
                        .id(toolId)
                        .tenantId(tenantId)
                        .build()
                )
                .whenComplete((r, throwable) -> {
                    context.restore();
                    if (throwable != null) {
                        Exception cause = SdkClientUtils.unwrapAndConvertToException(throwable);
                        log.error("Failed to delete custom tool {}", toolId, cause);
                        listener.onFailure(cause);
                    } else {
                        try {
                            DeleteResponse deleteResponse = r.deleteResponse();
                            log.info("Custom tool deleted: {}", toolId);
                            listener.onResponse(deleteResponse);
                        } catch (Exception e) {
                            listener.onFailure(e);
                        }
                    }
                });
        } catch (Exception e) {
            log.error("Failed to delete custom tool", e);
            listener.onFailure(e);
        }
    }
}
