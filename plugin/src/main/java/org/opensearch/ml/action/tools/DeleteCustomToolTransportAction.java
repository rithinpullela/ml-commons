/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.action.tools;

import static org.opensearch.ml.common.CommonValue.ML_CUSTOM_TOOLS_INDEX;

import java.util.Map;

import org.opensearch.action.ActionRequest;
import org.opensearch.action.delete.DeleteResponse;
import org.opensearch.action.get.GetRequest;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.HandledTransportAction;
import org.opensearch.common.inject.Inject;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.commons.authuser.User;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.ml.common.settings.MLFeatureEnabledSetting;
import org.opensearch.ml.common.transport.tools.MLDeleteCustomToolAction;
import org.opensearch.ml.common.transport.tools.MLDeleteCustomToolRequest;
import org.opensearch.ml.helper.CustomToolAccessControlHelper;
import org.opensearch.ml.utils.RestActionUtils;
import org.opensearch.ml.utils.TenantAwareHelper;
import org.opensearch.remote.metadata.client.DeleteDataObjectRequest;
import org.opensearch.remote.metadata.client.SdkClient;
import org.opensearch.remote.metadata.common.SdkClientUtils;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.tasks.Task;
import org.opensearch.transport.TransportService;
import org.opensearch.transport.client.Client;

import lombok.extern.log4j.Log4j2;

@Log4j2
public class DeleteCustomToolTransportAction extends HandledTransportAction<ActionRequest, DeleteResponse> {
    private final Client client;
    private final SdkClient sdkClient;
    private final MLFeatureEnabledSetting mlFeatureEnabledSetting;
    private final CustomToolAccessControlHelper accessControlHelper;

    @Inject
    public DeleteCustomToolTransportAction(
        TransportService transportService,
        ActionFilters actionFilters,
        Client client,
        SdkClient sdkClient,
        MLFeatureEnabledSetting mlFeatureEnabledSetting,
        CustomToolAccessControlHelper accessControlHelper
    ) {
        super(MLDeleteCustomToolAction.NAME, transportService, actionFilters, MLDeleteCustomToolRequest::new);
        this.client = client;
        this.sdkClient = sdkClient;
        this.mlFeatureEnabledSetting = mlFeatureEnabledSetting;
        this.accessControlHelper = accessControlHelper;
    }

    @Override
    protected void doExecute(Task task, ActionRequest request, ActionListener<DeleteResponse> listener) {
        MLDeleteCustomToolRequest deleteRequest = MLDeleteCustomToolRequest.fromActionRequest(request);
        String toolIdOrName = deleteRequest.getToolId();
        String tenantId = deleteRequest.getTenantId();

        if (!TenantAwareHelper.validateTenantId(mlFeatureEnabledSetting, tenantId, listener)) {
            return;
        }

        User user = RestActionUtils.getUserContext(client);
        if (accessControlHelper.skipAccessControl(user)) {
            // Try by ID first, fallback to name lookup
            resolveToolIdAndDelete(toolIdOrName, tenantId, user, listener, true);
            return;
        }

        // Non-admin path: validate access before deleting
        resolveToolIdAndDelete(toolIdOrName, tenantId, user, listener, false);
    }

    /**
     * Resolves tool ID from either direct ID or name lookup, validates access if needed, then deletes.
     *
     * @param toolIdOrName Either a document ID or tool name
     * @param tenantId Tenant ID for multi-tenancy
     * @param user User for access control
     * @param listener Response listener
     * @param skipAccessControl Whether to skip access validation
     */
    private void resolveToolIdAndDelete(
        String toolIdOrName,
        String tenantId,
        User user,
        ActionListener<DeleteResponse> listener,
        boolean skipAccessControl
    ) {
        try (ThreadContext.StoredContext context = client.threadPool().getThreadContext().stashContext()) {
            // Try fetching by document ID first
            client.get(new GetRequest(ML_CUSTOM_TOOLS_INDEX, toolIdOrName), ActionListener.wrap(getResponse -> {
                context.restore();
                if (getResponse.isExists()) {
                    // Found by ID - validate access and delete
                    if (skipAccessControl) {
                        performDelete(toolIdOrName, tenantId, listener);
                    } else {
                        try {
                            accessControlHelper.validateToolAccess(user, getResponse.getSourceAsMap());
                            performDelete(toolIdOrName, tenantId, listener);
                        } catch (Exception e) {
                            listener.onFailure(e);
                        }
                    }
                } else {
                    // Not found by ID - try name-based lookup
                    resolveByNameAndDelete(toolIdOrName, tenantId, user, listener, skipAccessControl);
                }
            }, e -> {
                context.restore();
                // Get request failed - try name-based lookup as fallback
                log.debug("Failed to get tool by ID, trying name lookup: {}", toolIdOrName);
                resolveByNameAndDelete(toolIdOrName, tenantId, user, listener, skipAccessControl);
            }));
        } catch (Exception e) {
            log.error("Failed to resolve tool for deletion", e);
            listener.onFailure(e);
        }
    }

    /**
     * Searches for tool by name, validates access if needed, then deletes.
     *
     * @param toolName Tool name to search for
     * @param tenantId Tenant ID
     * @param user User for access control
     * @param listener Response listener
     * @param skipAccessControl Whether to skip access validation
     */
    private void resolveByNameAndDelete(
        String toolName,
        String tenantId,
        User user,
        ActionListener<DeleteResponse> listener,
        boolean skipAccessControl
    ) {
        try (ThreadContext.StoredContext context = client.threadPool().getThreadContext().stashContext()) {
            SearchRequest searchRequest = new SearchRequest(ML_CUSTOM_TOOLS_INDEX);
            searchRequest.source(new SearchSourceBuilder().query(QueryBuilders.termQuery("name.keyword", toolName)).size(1));

            client.search(searchRequest, ActionListener.wrap(searchResponse -> {
                context.restore();
                if (searchResponse.getHits().getTotalHits().value() == 0) {
                    listener
                        .onFailure(
                            new org.opensearch.OpenSearchStatusException(
                                "Custom tool not found by ID or name: " + toolName,
                                RestStatus.NOT_FOUND
                            )
                        );
                    return;
                }

                // Found by name - extract document ID and tool definition
                String documentId = searchResponse.getHits().getHits()[0].getId();
                Map<String, Object> toolDef = searchResponse.getHits().getHits()[0].getSourceAsMap();

                log.info("Resolved tool name '{}' to document ID '{}'", toolName, documentId);

                // Validate access and delete
                if (skipAccessControl) {
                    performDelete(documentId, tenantId, listener);
                } else {
                    try {
                        accessControlHelper.validateToolAccess(user, toolDef);
                        performDelete(documentId, tenantId, listener);
                    } catch (Exception e) {
                        listener.onFailure(e);
                    }
                }
            }, e -> {
                context.restore();
                log.error("Failed to search for tool by name: {}", toolName, e);
                listener.onFailure(e);
            }));
        } catch (Exception e) {
            log.error("Failed to resolve tool by name", e);
            listener.onFailure(e);
        }
    }

    private void performDelete(String toolId, String tenantId, ActionListener<DeleteResponse> listener) {
        try (ThreadContext.StoredContext context = client.threadPool().getThreadContext().stashContext()) {
            sdkClient
                .deleteDataObjectAsync(DeleteDataObjectRequest.builder().index(ML_CUSTOM_TOOLS_INDEX).id(toolId).tenantId(tenantId).build())
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
