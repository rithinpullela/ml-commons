/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.engine.agents.v2.llm;

import java.util.HashMap;
import java.util.Map;

import org.opensearch.core.action.ActionListener;
import org.opensearch.ml.common.FunctionName;
import org.opensearch.ml.common.agent.v2.LLMRequest;
import org.opensearch.ml.common.agent.v2.LLMResponse;
import org.opensearch.ml.common.dataset.remote.RemoteInferenceInputDataSet;
import org.opensearch.ml.common.input.MLInput;
import org.opensearch.ml.common.model.ModelProvider;
import org.opensearch.ml.common.output.model.ModelTensorOutput;
import org.opensearch.ml.common.transport.MLTaskResponse;
import org.opensearch.ml.common.transport.prediction.MLPredictionTaskAction;
import org.opensearch.ml.common.transport.prediction.MLPredictionTaskRequest;
import org.opensearch.transport.client.Client;

import lombok.extern.log4j.Log4j2;

/**
 * V2 LLM interface implementation.
 * Composes ModelProvider (for provider-specific formatting) with the OpenSearch Client
 * (for executing the prediction through the existing connector pipeline).
 */
@Log4j2
public class LLMInterfaceImpl implements LLMInterface {

    private final String modelId;
    private final Client client;
    private final ModelProvider modelProvider;
    private final Map<String, String> baseParams;

    public LLMInterfaceImpl(String modelId, Client client, ModelProvider modelProvider, Map<String, String> baseParams) {
        this.modelId = modelId;
        this.client = client;
        this.modelProvider = modelProvider;
        this.baseParams = baseParams != null ? baseParams : Map.of();
    }

    @Override
    public void call(LLMRequest request, ActionListener<LLMResponse> listener) {
        try {
            Map<String, String> params = new HashMap<>(baseParams);

            // ModelProvider handles ALL provider-specific request formatting
            modelProvider.buildRequestParams(request, params);

            // Execute via existing connector pipeline
            RemoteInferenceInputDataSet inputDataSet = RemoteInferenceInputDataSet.builder().parameters(params).build();
            MLInput mlInput = MLInput.builder().algorithm(FunctionName.REMOTE).inputDataset(inputDataSet).build();
            MLPredictionTaskRequest predictionRequest = new MLPredictionTaskRequest(modelId, mlInput);

            client.execute(MLPredictionTaskAction.INSTANCE, predictionRequest, ActionListener.wrap(taskResponse -> {
                try {
                    ModelTensorOutput output = (ModelTensorOutput) ((MLTaskResponse) taskResponse).getOutput();
                    LLMResponse response = modelProvider.parseResponse(output);
                    listener.onResponse(response);
                } catch (Exception e) {
                    log.error("Failed to parse LLM response", e);
                    listener.onFailure(e);
                }
            }, e -> {
                log.error("Failed to execute LLM prediction", e);
                listener.onFailure(e);
            }));
        } catch (Exception e) {
            log.error("Failed to build LLM request", e);
            listener.onFailure(e);
        }
    }
}
