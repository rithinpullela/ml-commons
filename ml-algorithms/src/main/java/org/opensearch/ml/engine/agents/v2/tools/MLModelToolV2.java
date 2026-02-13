/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.engine.agents.v2.tools;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.opensearch.core.action.ActionListener;
import org.opensearch.ml.common.agent.v2.AgentToolV2;
import org.opensearch.ml.common.agent.v2.AgentToolV2Factory;
import org.opensearch.ml.common.agent.v2.ToolCallResult;
import org.opensearch.ml.common.agent.v2.ToolExecutionContext;
import org.opensearch.ml.common.agent.v2.ToolSpec;
import org.opensearch.ml.common.dataset.remote.RemoteInferenceInputDataSet;
import org.opensearch.ml.common.input.MLInput;
import org.opensearch.ml.common.output.model.ModelTensorOutput;
import org.opensearch.ml.common.output.model.ModelTensors;
import org.opensearch.ml.common.transport.prediction.MLPredictionTaskAction;
import org.opensearch.ml.common.transport.prediction.MLPredictionTaskRequest;
import org.opensearch.ml.common.utils.StringUtils;
import org.opensearch.transport.client.Client;

import lombok.extern.log4j.Log4j2;

/**
 * Native V2 ML model tool.
 * Invokes a registered ML model with structured parameters, preserving types.
 */
@Log4j2
public class MLModelToolV2 implements AgentToolV2 {

    public static final String TYPE = "MLModelToolV2";
    public static final String MODEL_ID_FIELD = "model_id";
    public static final String RESPONSE_FIELD = "response_field";
    public static final String DEFAULT_RESPONSE_FIELD = "response";

    private static final String DEFAULT_DESCRIPTION = "Use this tool to invoke a machine learning model with input parameters.";

    private static final Map<String, Object> INPUT_SCHEMA = Map
        .of(
            "type",
            "object",
            "properties",
            Map.of("input", Map.of("type", "string", "description", "The input text or query for the ML model")),
            "required",
            List.of("input")
        );

    private static final ToolSpec DEFAULT_TOOL_SPEC = new ToolSpec(TYPE, DEFAULT_DESCRIPTION, INPUT_SCHEMA);

    private final Client client;
    private final String modelId;
    private final String responseField;
    private String name = TYPE;
    private String description = DEFAULT_DESCRIPTION;

    public MLModelToolV2(Client client, String modelId, String responseField) {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("model_id is required for MLModelToolV2");
        }
        this.client = client;
        this.modelId = modelId;
        this.responseField = responseField != null ? responseField : DEFAULT_RESPONSE_FIELD;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public ToolSpec getToolSpec() {
        return new ToolSpec(name, description, INPUT_SCHEMA);
    }

    @Override
    public void execute(Map<String, Object> arguments, ToolExecutionContext context, ActionListener<ToolCallResult> listener) {
        try {
            // Flatten to string map for RemoteInferenceInputDataSet
            Map<String, String> params = new HashMap<>();
            for (Map.Entry<String, Object> entry : arguments.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String) {
                    params.put(entry.getKey(), (String) value);
                } else if (value != null) {
                    params.put(entry.getKey(), StringUtils.toJson(value));
                }
            }

            RemoteInferenceInputDataSet inputDataSet = RemoteInferenceInputDataSet.builder().parameters(params).build();
            MLInput mlInput = MLInput.builder().inputDataset(inputDataSet).build();
            MLPredictionTaskRequest predictionRequest = new MLPredictionTaskRequest(modelId, mlInput);

            client.execute(MLPredictionTaskAction.INSTANCE, predictionRequest, ActionListener.wrap(response -> {
                ModelTensorOutput tensorOutput = (ModelTensorOutput) response.getOutput();
                String result = extractResponse(tensorOutput);
                listener.onResponse(ToolCallResult.success(context.getToolCallId(), name, result));
            }, e -> {
                log.error("MLModelToolV2 prediction failed for model: {}", modelId, e);
                listener.onResponse(ToolCallResult.error(context.getToolCallId(), name, "Model prediction failed: " + e.getMessage()));
            }));
        } catch (Exception e) {
            log.error("MLModelToolV2 execution failed", e);
            listener.onResponse(ToolCallResult.error(context.getToolCallId(), name, "Execution error: " + e.getMessage()));
        }
    }

    private String extractResponse(ModelTensorOutput tensorOutput) {
        if (tensorOutput == null || tensorOutput.getMlModelOutputs() == null || tensorOutput.getMlModelOutputs().isEmpty()) {
            return "";
        }
        for (ModelTensors tensors : tensorOutput.getMlModelOutputs()) {
            if (tensors.getMlModelTensors() != null) {
                for (var tensor : tensors.getMlModelTensors()) {
                    if (tensor.getDataAsMap() != null) {
                        Object value = tensor.getDataAsMap().get(responseField);
                        if (value != null) {
                            return value.toString();
                        }
                        // Fall back to full response
                        return StringUtils.toJson(tensor.getDataAsMap());
                    }
                    if (tensor.getResult() != null) {
                        return tensor.getResult();
                    }
                }
            }
        }
        return StringUtils.toJson(tensorOutput);
    }

    /**
     * V2 Factory for MLModelToolV2.
     */
    public static class Factory implements AgentToolV2Factory {

        private Client client;
        private static Factory INSTANCE;

        public static Factory getInstance() {
            if (INSTANCE != null) {
                return INSTANCE;
            }
            synchronized (Factory.class) {
                if (INSTANCE != null) {
                    return INSTANCE;
                }
                INSTANCE = new Factory();
                return INSTANCE;
            }
        }

        public void init(Client client) {
            this.client = client;
        }

        @Override
        public AgentToolV2 create(Map<String, Object> params) {
            String modelId = params != null ? (String) params.get(MODEL_ID_FIELD) : null;
            String responseField = params != null
                ? (String) params.getOrDefault(RESPONSE_FIELD, DEFAULT_RESPONSE_FIELD)
                : DEFAULT_RESPONSE_FIELD;

            MLModelToolV2 tool = new MLModelToolV2(client, modelId, responseField);
            if (params != null && params.containsKey("name")) {
                tool.name = (String) params.get("name");
            }
            if (params != null && params.containsKey("description")) {
                tool.description = (String) params.get("description");
            }
            return tool;
        }

        @Override
        public String getDefaultType() {
            return TYPE;
        }

        @Override
        public ToolSpec getDefaultToolSpec() {
            return DEFAULT_TOOL_SPEC;
        }
    }
}
