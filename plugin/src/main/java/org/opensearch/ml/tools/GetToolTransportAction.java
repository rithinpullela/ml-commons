/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.tools;

import java.util.List;
import java.util.Optional;

import org.opensearch.OpenSearchStatusException;
import org.opensearch.action.ActionRequest;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.HandledTransportAction;
import org.opensearch.common.inject.Inject;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.ml.action.tools.CustomToolsHelper;
import org.opensearch.ml.common.ToolMetadata;
import org.opensearch.ml.common.transport.tools.*;
import org.opensearch.tasks.Task;
import org.opensearch.transport.TransportService;

import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.log4j.Log4j2;

@Log4j2
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
public class GetToolTransportAction extends HandledTransportAction<ActionRequest, MLToolGetResponse> {

    CustomToolsHelper customToolsHelper;

    @Inject
    public GetToolTransportAction(TransportService transportService, ActionFilters actionFilters, CustomToolsHelper customToolsHelper) {
        super(MLGetToolAction.NAME, transportService, actionFilters, MLToolGetRequest::new);
        this.customToolsHelper = customToolsHelper;
    }

    @Override
    protected void doExecute(Task task, ActionRequest request, ActionListener<MLToolGetResponse> listener) {
        MLToolGetRequest mlToolGetRequest = MLToolGetRequest.fromActionRequest(request);
        String toolName = mlToolGetRequest.getToolName();
        try {
            List<ToolMetadata> toolsList = mlToolGetRequest.getToolMetadataList();

            // Check built-in tools first
            Optional<ToolMetadata> builtInTool = toolsList.stream().filter(tool -> tool.getName().equals(toolName)).findFirst();

            if (builtInTool.isPresent()) {
                listener.onResponse(MLToolGetResponse.builder().toolMetadata(builtInTool.get()).build());
                return;
            }

            // Not a built-in tool; search custom tools index
            customToolsHelper.searchCustomToolByName(toolName, ActionListener.wrap(customTool -> {
                if (customTool != null) {
                    listener.onResponse(MLToolGetResponse.builder().toolMetadata(customTool).build());
                } else {
                    listener
                        .onFailure(
                            new OpenSearchStatusException(
                                "Failed to find tool information with the provided tool name: " + toolName,
                                RestStatus.NOT_FOUND
                            )
                        );
                }
            }, e -> {
                log.error("Failed to search custom tools", e);
                listener
                    .onFailure(
                        new OpenSearchStatusException(
                            "Failed to find tool information with the provided tool name: " + toolName,
                            RestStatus.NOT_FOUND
                        )
                    );
            }));
        } catch (Exception e) {
            log.error("Failed to get tool", e);
            listener.onFailure(e);
        }
    }
}
