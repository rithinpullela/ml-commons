/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.opensearch.action.ActionRequest;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.HandledTransportAction;
import org.opensearch.common.inject.Inject;
import org.opensearch.core.action.ActionListener;
import org.opensearch.ml.action.tools.CustomToolsHelper;
import org.opensearch.ml.common.ToolMetadata;
import org.opensearch.ml.common.transport.tools.MLListToolsAction;
import org.opensearch.ml.common.transport.tools.MLToolsListRequest;
import org.opensearch.ml.common.transport.tools.MLToolsListResponse;
import org.opensearch.tasks.Task;
import org.opensearch.transport.TransportService;

import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.log4j.Log4j2;

@Log4j2
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
public class ListToolsTransportAction extends HandledTransportAction<ActionRequest, MLToolsListResponse> {

    CustomToolsHelper customToolsHelper;

    @Inject
    public ListToolsTransportAction(TransportService transportService, ActionFilters actionFilters, CustomToolsHelper customToolsHelper) {
        super(MLListToolsAction.NAME, transportService, actionFilters, MLToolsListRequest::new);
        this.customToolsHelper = customToolsHelper;
    }

    @Override
    protected void doExecute(Task task, ActionRequest request, ActionListener<MLToolsListResponse> listener) {
        MLToolsListRequest mlToolsGetRequest = MLToolsListRequest.fromActionRequest(request);
        List<ToolMetadata> builtInTools = mlToolsGetRequest.getToolMetadataList();

        try {
            // Query custom tools index and merge with built-in tools
            customToolsHelper.searchAllCustomTools(ActionListener.wrap(customTools -> {
                List<ToolMetadata> mergedList = new ArrayList<>(builtInTools);

                // Avoid duplicates: skip custom tools that share a name with built-in tools
                Set<String> builtInNames = builtInTools.stream().map(ToolMetadata::getName).collect(Collectors.toSet());
                for (ToolMetadata customTool : customTools) {
                    if (!builtInNames.contains(customTool.getName())) {
                        mergedList.add(customTool);
                    }
                }

                listener.onResponse(MLToolsListResponse.builder().toolMetadata(mergedList).build());
            }, e -> {
                log.error("Failed to search custom tools, returning only built-in tools", e);
                // Graceful degradation: return built-in tools even if custom tools query fails
                listener.onResponse(MLToolsListResponse.builder().toolMetadata(builtInTools).build());
            }));
        } catch (Exception e) {
            log.error("Failed to get tools list", e);
            listener.onFailure(e);
        }
    }
}
