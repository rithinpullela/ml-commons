/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.action.tools;

import static org.opensearch.ml.common.CommonValue.ML_CUSTOM_TOOLS_INDEX;

import java.time.Instant;
import java.util.Map;

import org.opensearch.action.ActionRequest;
import org.opensearch.action.admin.cluster.storedscripts.GetStoredScriptRequest;
import org.opensearch.action.index.IndexResponse;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.HandledTransportAction;
import org.opensearch.common.inject.Inject;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.core.action.ActionListener;
import org.opensearch.ml.common.settings.MLFeatureEnabledSetting;
import org.opensearch.ml.common.transport.tools.MLCreateCustomToolAction;
import org.opensearch.ml.common.transport.tools.MLCreateCustomToolRequest;
import org.opensearch.ml.common.transport.tools.MLCreateCustomToolResponse;
import org.opensearch.ml.common.transport.tools.MLCustomToolInput;
import org.opensearch.ml.engine.indices.MLIndicesHandler;
import org.opensearch.ml.engine.tools.MustacheTemplateAnalyzer;
import org.opensearch.ml.utils.TenantAwareHelper;
import org.opensearch.remote.metadata.client.PutDataObjectRequest;
import org.opensearch.remote.metadata.client.SdkClient;
import org.opensearch.remote.metadata.common.SdkClientUtils;
import org.opensearch.tasks.Task;
import org.opensearch.transport.TransportService;
import org.opensearch.transport.client.Client;

import lombok.extern.log4j.Log4j2;

@Log4j2
public class CreateCustomToolTransportAction extends HandledTransportAction<ActionRequest, MLCreateCustomToolResponse> {
    private final MLIndicesHandler mlIndicesHandler;
    private final Client client;
    private final SdkClient sdkClient;
    private final MLFeatureEnabledSetting mlFeatureEnabledSetting;
    private final CustomToolsHelper customToolsHelper;

    @Inject
    public CreateCustomToolTransportAction(
        TransportService transportService,
        ActionFilters actionFilters,
        MLIndicesHandler mlIndicesHandler,
        Client client,
        SdkClient sdkClient,
        MLFeatureEnabledSetting mlFeatureEnabledSetting,
        CustomToolsHelper customToolsHelper
    ) {
        super(MLCreateCustomToolAction.NAME, transportService, actionFilters, MLCreateCustomToolRequest::new);
        this.mlIndicesHandler = mlIndicesHandler;
        this.client = client;
        this.sdkClient = sdkClient;
        this.mlFeatureEnabledSetting = mlFeatureEnabledSetting;
        this.customToolsHelper = customToolsHelper;
    }

    @Override
    protected void doExecute(Task task, ActionRequest request, ActionListener<MLCreateCustomToolResponse> listener) {
        MLCreateCustomToolRequest createRequest = MLCreateCustomToolRequest.fromActionRequest(request);
        MLCustomToolInput input = createRequest.getMlCustomToolInput();

        // 1. Validate tenant
        if (!TenantAwareHelper.validateTenantId(mlFeatureEnabledSetting, input.getTenantId(), listener)) {
            return;
        }

        // 2. Validate name doesn't start with underscore
        if (input.getName() != null && input.getName().startsWith("_")) {
            listener.onFailure(new IllegalArgumentException("Custom tool name cannot start with '_'"));
            return;
        }

        // 3. Check name uniqueness, then validate template and resolve params
        checkNameUniqueness(input, listener);
    }

    private void checkNameUniqueness(MLCustomToolInput input, ActionListener<MLCreateCustomToolResponse> listener) {
        customToolsHelper.searchCustomToolByName(input.getName(), ActionListener.wrap(existing -> {
            if (existing != null) {
                listener.onFailure(new IllegalArgumentException("A custom tool with name '" + input.getName() + "' already exists"));
                return;
            }
            validateSearchTemplateAndResolveParams(input, listener);
        }, e -> {
            log.error("Failed to check custom tool name uniqueness", e);
            listener.onFailure(e);
        }));
    }

    private void validateSearchTemplateAndResolveParams(MLCustomToolInput input, ActionListener<MLCreateCustomToolResponse> listener) {
        // Tier 3: User provided params manually — use as-is, just validate template exists
        if (input.getParams() != null) {
            log.debug("Using user-provided params for custom tool '{}'", input.getName());
            validateTemplateExistsAndIndex(input, listener);
            return;
        }

        // Tier 1 or 2: Need template source for auto-extraction
        GetStoredScriptRequest getScriptRequest = new GetStoredScriptRequest(input.getSearchTemplateName());
        client.admin().cluster().getStoredScript(getScriptRequest, ActionListener.wrap(response -> {
            if (response.getSource() == null) {
                listener.onFailure(new IllegalArgumentException("Search template '" + input.getSearchTemplateName() + "' not found"));
                return;
            }

            String templateSource = response.getSource().getSource();
            Map<String, Map<String, Object>> extractedParams;
            try {
                extractedParams = MustacheTemplateAnalyzer.analyze(templateSource);
            } catch (Exception e) {
                log.warn("Failed to auto-extract params from template '{}': {}", input.getSearchTemplateName(), e.getMessage());
                listener
                    .onFailure(
                        new IllegalArgumentException(
                            "Failed to analyze search template '" + input.getSearchTemplateName() + "': " + e.getMessage()
                        )
                    );
                return;
            }

            if (input.getModelId() != null) {
                // Tier 2: model_id provided — auto-extract + LLM enrichment
                // TODO: Implement LLM-assisted parameter enrichment.
                // The extractedParams from MustacheTemplateAnalyzer provide parameter names,
                // required/optional status, and heuristic types. An LLM call using the provided
                // model_id should enhance descriptions and refine types.
                // For now, fall through to use the AST-extracted params as-is.
                log
                    .info(
                        "model_id provided for custom tool '{}' but LLM enrichment not yet implemented. Using AST-extracted params.",
                        input.getName()
                    );
            } else {
                // Tier 1: Neither params nor model_id — pure AST extraction with heuristic defaults
                log.debug("Auto-extracting params from template for custom tool '{}'", input.getName());
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> paramsForStorage = (Map<String, Object>) (Map<String, ?>) extractedParams;
            input.setParams(paramsForStorage);

            indexCustomTool(input, listener);
        }, e -> {
            log.error("Failed to validate search template", e);
            listener
                .onFailure(
                    new IllegalArgumentException(
                        "Failed to validate search template '" + input.getSearchTemplateName() + "': " + e.getMessage()
                    )
                );
        }));
    }

    private void validateTemplateExistsAndIndex(MLCustomToolInput input, ActionListener<MLCreateCustomToolResponse> listener) {
        GetStoredScriptRequest getScriptRequest = new GetStoredScriptRequest(input.getSearchTemplateName());
        client.admin().cluster().getStoredScript(getScriptRequest, ActionListener.wrap(response -> {
            if (response.getSource() == null) {
                listener.onFailure(new IllegalArgumentException("Search template '" + input.getSearchTemplateName() + "' not found"));
                return;
            }
            indexCustomTool(input, listener);
        }, e -> {
            log.error("Failed to validate search template", e);
            listener
                .onFailure(
                    new IllegalArgumentException(
                        "Failed to validate search template '" + input.getSearchTemplateName() + "': " + e.getMessage()
                    )
                );
        }));
    }

    private void indexCustomTool(MLCustomToolInput input, ActionListener<MLCreateCustomToolResponse> listener) {
        mlIndicesHandler.initMLCustomToolsIndex(ActionListener.wrap(indexCreated -> {
            if (!indexCreated) {
                listener.onFailure(new RuntimeException("No response to create custom tools index"));
                return;
            }
            try (ThreadContext.StoredContext context = client.threadPool().getThreadContext().stashContext()) {
                Instant now = Instant.now();
                input.setCreateTime(now);
                input.setLastUpdateTime(now);

                sdkClient
                    .putDataObjectAsync(
                        PutDataObjectRequest.builder().tenantId(input.getTenantId()).index(ML_CUSTOM_TOOLS_INDEX).dataObject(input).build()
                    )
                    .whenComplete((r, throwable) -> {
                        context.restore();
                        if (throwable != null) {
                            Exception cause = SdkClientUtils.unwrapAndConvertToException(throwable);
                            log.error("Failed to create custom tool", cause);
                            listener.onFailure(cause);
                        } else {
                            try {
                                IndexResponse indexResponse = r.indexResponse();
                                log.info("Custom tool created: {}, id: {}", indexResponse.getResult(), indexResponse.getId());
                                listener.onResponse(new MLCreateCustomToolResponse(indexResponse.getId(), input.getParams()));
                            } catch (Exception e) {
                                listener.onFailure(e);
                            }
                        }
                    });
            } catch (Exception e) {
                log.error("Failed to save custom tool", e);
                listener.onFailure(e);
            }
        }, e -> {
            log.error("Failed to init custom tools index", e);
            listener.onFailure(e);
        }));
    }
}
