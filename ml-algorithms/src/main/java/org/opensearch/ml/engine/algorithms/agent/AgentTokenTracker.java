/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.engine.algorithms.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.opensearch.ml.common.agent.TokenUsage;

/**
 * Tracks token usage across multiple LLM calls during agent execution.
 * Maintains both per-turn usage (each individual LLM call) and per-model
 * aggregated usage (total across all calls to each model).
 */
public class AgentTokenTracker {

    // Track each individual LLM call
    private final List<Map<String, Object>> perTurnUsage;

    // Aggregated by model ID
    private final Map<String, ModelUsageAggregation> perModelUsage;

    // Model metadata: modelId -> {modelUrl, modelName}
    private final Map<String, ModelMetadata> modelMetadataMap;

    // Current turn counter
    private int turnCounter;

    public AgentTokenTracker() {
        this.perTurnUsage = new ArrayList<>();
        this.perModelUsage = new HashMap<>();
        this.modelMetadataMap = new HashMap<>();
        this.turnCounter = 0;
    }

    /**
     * Sets model metadata for enriching token usage data.
     * Should be called when model details are resolved.
     *
     * @param modelId The model ID
     * @param modelUrl The resolved model URL
     * @param modelName The model name (can be same as modelId or modelUrl)
     */
    public void setModelMetadata(String modelId, String modelUrl, String modelName) {
        if (modelId != null) {
            modelMetadataMap.put(modelId, new ModelMetadata(modelUrl, modelName));
        }
    }

    /**
     * Records token usage for a single LLM call
     *
     * @param modelId The model ID
     * @param usage The token usage from the LLM response
     */
    public void recordTurn(String modelId, TokenUsage usage) {
        if (modelId == null || usage == null) {
            return;
        }

        turnCounter++;

        // Get model metadata (or use defaults if not set)
        ModelMetadata metadata = modelMetadataMap.getOrDefault(modelId, new ModelMetadata(modelId, modelId));

        // Add to per-turn list
        Map<String, Object> turnData = new HashMap<>();
        turnData.put("turn", turnCounter);
        turnData.put("model_name", metadata.getModelName());
        turnData.put("model_url", metadata.getModelUrl());
        turnData.put("model_id", modelId);
        turnData.putAll(usage.toMap());
        perTurnUsage.add(turnData);

        // Update per-model aggregation (keyed by model ID)
        perModelUsage.computeIfAbsent(modelId, k -> new ModelUsageAggregation(metadata)).addUsage(usage);
    }

    /**
     * Returns the complete token usage data structure for inclusion in agent response
     *
     * @return Map with "per_model_usage" and "per_turn_usage" keys
     */
    public Map<String, Object> toOutputMap() {
        Map<String, Object> output = new HashMap<>();

        // Build per_model_usage list
        List<Map<String, Object>> perModelList = new ArrayList<>();
        for (Map.Entry<String, ModelUsageAggregation> entry : perModelUsage.entrySet()) {
            Map<String, Object> modelData = new HashMap<>();
            ModelMetadata metadata = entry.getValue().getMetadata();
            modelData.put("model_name", metadata.getModelName());
            modelData.put("model_url", metadata.getModelUrl());
            modelData.put("model_id", entry.getKey());
            modelData.putAll(entry.getValue().getAggregatedUsage().toMap());
            modelData.put("call_count", entry.getValue().getCallCount());
            perModelList.add(modelData);
        }

        output.put("per_model_usage", perModelList);
        output.put("per_turn_usage", perTurnUsage);

        return output;
    }

    /**
     * Merges pre-aggregated token usage data from a sub-agent response.
     * The sub-agent (e.g., Chat/ReAct executor) has already tracked its own tokens
     * and returns them as aggregated per_turn_usage and per_model_usage.
     *
     * @param tokenUsageMap the token_usage dataAsMap from the sub-agent response tensor
     */
    @SuppressWarnings("unchecked")
    public void mergeSubAgentUsage(Map<String, Object> tokenUsageMap) {
        if (tokenUsageMap == null) {
            return;
        }

        // Merge per-turn usage: re-number turns sequentially
        Object perTurnObj = tokenUsageMap.get("per_turn_usage");
        if (perTurnObj instanceof List) {
            List<Map<String, Object>> subTurns = (List<Map<String, Object>>) perTurnObj;
            for (Map<String, Object> subTurn : subTurns) {
                turnCounter++;
                Map<String, Object> mergedTurn = new HashMap<>(subTurn);
                mergedTurn.put("turn", turnCounter);
                perTurnUsage.add(mergedTurn);
            }
        }

        // Merge per-model usage: aggregate into existing model data
        Object perModelObj = tokenUsageMap.get("per_model_usage");
        if (perModelObj instanceof List) {
            List<Map<String, Object>> subModels = (List<Map<String, Object>>) perModelObj;
            for (Map<String, Object> subModel : subModels) {
                String modelId = (String) subModel.get("model_id");
                if (modelId == null) {
                    continue;
                }

                String modelUrl = (String) subModel.getOrDefault("model_url", modelId);
                String modelName = (String) subModel.getOrDefault("model_name", modelId);

                TokenUsage subUsage = TokenUsage
                    .builder()
                    .inputTokens(getLongFromMap(subModel, "input_tokens"))
                    .outputTokens(getLongFromMap(subModel, "output_tokens"))
                    .totalTokens(getLongFromMap(subModel, "total_tokens"))
                    .cacheReadInputTokens(getLongFromMap(subModel, "cache_read_input_tokens"))
                    .cacheCreationInputTokens(getLongFromMap(subModel, "cache_creation_input_tokens"))
                    .reasoningTokens(getLongFromMap(subModel, "reasoning_tokens"))
                    .build();

                int subCallCount = subModel.containsKey("call_count") ? ((Number) subModel.get("call_count")).intValue() : 1;

                if (!modelMetadataMap.containsKey(modelId)) {
                    modelMetadataMap.put(modelId, new ModelMetadata(modelUrl, modelName));
                }

                ModelMetadata metadata = modelMetadataMap.getOrDefault(modelId, new ModelMetadata(modelUrl, modelName));
                perModelUsage.computeIfAbsent(modelId, k -> new ModelUsageAggregation(metadata)).mergeAggregated(subUsage, subCallCount);
            }
        }
    }

    private static Long getLongFromMap(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return null;
    }

    /**
     * Checks if any token usage has been recorded
     *
     * @return true if at least one turn has been recorded
     */
    public boolean hasUsage() {
        return !perTurnUsage.isEmpty();
    }

    /**
     * Internal class to track aggregated usage for a specific model
     */
    private static class ModelUsageAggregation {
        private TokenUsage aggregatedUsage;
        private int callCount;
        private ModelMetadata metadata;

        public ModelUsageAggregation(ModelMetadata metadata) {
            this.aggregatedUsage = TokenUsage.builder().build();
            this.callCount = 0;
            this.metadata = metadata;
        }

        public void addUsage(TokenUsage usage) {
            this.aggregatedUsage = this.aggregatedUsage.addTokens(usage);
            this.callCount++;
        }

        public void mergeAggregated(TokenUsage aggregatedUsage, int callCount) {
            this.aggregatedUsage = this.aggregatedUsage.addTokens(aggregatedUsage);
            this.callCount += callCount;
        }

        public TokenUsage getAggregatedUsage() {
            return aggregatedUsage;
        }

        public int getCallCount() {
            return callCount;
        }

        public ModelMetadata getMetadata() {
            return metadata;
        }
    }

    /**
     * Internal class to store model metadata
     */
    private static class ModelMetadata {
        private final String modelUrl;
        private final String modelName;

        public ModelMetadata(String modelUrl, String modelName) {
            this.modelUrl = modelUrl != null ? modelUrl : "";
            this.modelName = modelName != null ? modelName : "";
        }

        public String getModelUrl() {
            return modelUrl;
        }

        public String getModelName() {
            return modelName;
        }
    }
}
