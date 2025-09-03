/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.action.memorycontainer.memory;

import static org.opensearch.common.xcontent.json.JsonXContent.jsonXContent;
import static org.opensearch.core.xcontent.XContentParserUtils.ensureExpectedToken;
import static org.opensearch.ml.common.memorycontainer.MemoryContainerConstants.DEFAULT_UPDATE_MEMORY_PROMPT;
import static org.opensearch.ml.common.memorycontainer.MemoryContainerConstants.MEMORY_DECISION_FIELD;
import static org.opensearch.ml.common.memorycontainer.MemoryContainerConstants.PERSONAL_INFORMATION_ORGANIZER_PROMPT;
import static org.apache.commons.text.StringEscapeUtils.escapeJson;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.opensearch.OpenSearchException;
import org.opensearch.common.xcontent.LoggingDeprecationHandler;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.ml.common.FunctionName;
import org.opensearch.ml.common.dataset.remote.RemoteInferenceInputDataSet;
import org.opensearch.ml.common.input.MLInput;
import org.opensearch.ml.common.memorycontainer.MemoryDecision;
import org.opensearch.ml.common.memorycontainer.MemoryDecisionRequest;
import org.opensearch.ml.common.memorycontainer.MemoryStorageConfig;
import org.opensearch.ml.common.output.MLOutput;
import org.opensearch.ml.common.output.model.ModelTensorOutput;
import org.opensearch.ml.common.output.model.ModelTensors;
import org.opensearch.ml.common.utils.StringUtils;
import org.opensearch.ml.common.transport.MLTaskResponse;
import org.opensearch.ml.common.transport.memorycontainer.memory.MessageInput;
import org.opensearch.ml.common.transport.prediction.MLPredictionTaskAction;
import org.opensearch.ml.common.transport.prediction.MLPredictionTaskRequest;
import org.opensearch.transport.client.Client;

import lombok.extern.log4j.Log4j2;

@Log4j2
public class MemoryProcessingService {

    private final Client client;
    private final NamedXContentRegistry xContentRegistry;

    public MemoryProcessingService(Client client, NamedXContentRegistry xContentRegistry) {
        this.client = client;
        this.xContentRegistry = xContentRegistry;
    }

    public void extractFactsFromConversation(
        List<MessageInput> messages,
        MemoryStorageConfig storageConfig,
        ActionListener<List<String>> listener
    ) {
        if (storageConfig == null || storageConfig.getLlmModelId() == null) {
            listener.onResponse(new ArrayList<>());
            return;
        }

        String llmModelId = storageConfig.getLlmModelId();
        Map<String, String> stringParameters = new HashMap<>();
        stringParameters.put("system_prompt", PERSONAL_INFORMATION_ORGANIZER_PROMPT);

        try {
            StringBuilder user_messages = new StringBuilder();

            for (MessageInput message : messages) {
                if(message.getRole() != null && message.getRole().equals("user")) {
                    user_messages.append(message.getContent());
                }
            }

            String messagesJson = user_messages.toString();
            // Escape the messages string since it will be used as a string value in the connector template
            stringParameters.put("messages", escapeJson(messagesJson));

            log.debug("LLM request - processing {} messages", messages.size());
        } catch (Exception e) {
            log.error("Failed to build messages JSON", e);
            listener.onResponse(new ArrayList<>());
            return;
        }

        MLInput mlInput = MLInput
            .builder()
            .algorithm(FunctionName.REMOTE)
            .inputDataset(RemoteInferenceInputDataSet.builder().parameters(stringParameters).build())
            .build();

        MLPredictionTaskRequest predictionRequest = MLPredictionTaskRequest.builder().modelId(llmModelId).mlInput(mlInput).build();

        client.execute(MLPredictionTaskAction.INSTANCE, predictionRequest, ActionListener.wrap(response -> {
            try {
                log.debug("Received LLM response, parsing facts...");
                MLOutput mlOutput = response.getOutput();
                List<String> facts = parseFactsFromLLMResponse(mlOutput);
                log.debug("Extracted {} facts from LLM response", facts.size());
                listener.onResponse(facts);
            } catch (Exception e) {
                log.error("Failed to parse facts from LLM response", e);
                listener.onFailure(new IllegalArgumentException("Failed to parse facts from LLM response", e));
            }
        }, e -> {
            log.error("Failed to call LLM for fact extraction", e);
            listener.onFailure(new OpenSearchException("Failed to extract facts using LLM model: " + e.getMessage(), e));
        }));
    }

    public void makeMemoryDecisions(
        List<String> extractedFacts,
        List<FactSearchResult> allSearchResults,
        MemoryStorageConfig storageConfig,
        ActionListener<List<MemoryDecision>> listener
    ) {
        if (storageConfig == null || storageConfig.getLlmModelId() == null) {
            listener.onFailure(new IllegalStateException("LLM model is required for memory decisions"));
            return;
        }

        String llmModelId = storageConfig.getLlmModelId();

        List<MemoryDecisionRequest.OldMemory> oldMemories = new ArrayList<>();
        for (FactSearchResult result : allSearchResults) {
            oldMemories
                .add(MemoryDecisionRequest.OldMemory.builder().id(result.getId()).text(result.getText()).score(result.getScore()).build());
        }

        MemoryDecisionRequest decisionRequest = MemoryDecisionRequest
            .builder()
            .oldMemory(oldMemories)
            .retrievedFacts(extractedFacts)
            .build();

        Map<String, String> stringParameters = new HashMap<>();
        stringParameters.put("system_prompt", DEFAULT_UPDATE_MEMORY_PROMPT);

        String decisionRequestJson = decisionRequest.toJsonString();

        try {
            // Manually escape the JSON string since it will be used as a string value in the connector template
            stringParameters.put("messages", escapeJson(decisionRequestJson));

            log
                .debug(
                    "Making memory decisions for {} extracted facts and {} existing memories",
                    extractedFacts.size(),
                    allSearchResults.size()
                );

            RemoteInferenceInputDataSet inputDataSet = RemoteInferenceInputDataSet.builder().parameters(stringParameters).build();
            MLInput mlInput = MLInput.builder().algorithm(FunctionName.REMOTE).inputDataset(inputDataSet).build();

            MLPredictionTaskRequest predictionRequest = MLPredictionTaskRequest.builder().modelId(llmModelId).mlInput(mlInput).build();

            client.execute(MLPredictionTaskAction.INSTANCE, predictionRequest, ActionListener.wrap(response -> {
                try {
                    List<MemoryDecision> decisions = parseMemoryDecisions(response);
                    log.debug("LLM made {} memory decisions", decisions.size());
                    listener.onResponse(decisions);
                } catch (Exception e) {
                    log.error("Failed to parse memory decisions from LLM response", e);
                    listener.onFailure(e);
                }
            }, e -> {
                log.error("Failed to get memory decisions from LLM", e);
                listener.onFailure(e);
            }));
        } catch (Exception e) {
            log.error("Failed to build memory decision request", e);
            listener.onFailure(e);
        }
    }

    private List<String> parseFactsFromLLMResponse(MLOutput mlOutput) {
        List<String> facts = new ArrayList<>();

        if (!(mlOutput instanceof ModelTensorOutput)) {
            log.warn("Unexpected ML output type for LLM response: {}", mlOutput != null ? mlOutput.getClass().getName() : "null");
            return facts;
        }

        ModelTensorOutput tensorOutput = (ModelTensorOutput) mlOutput;
        if (tensorOutput.getMlModelOutputs() == null || tensorOutput.getMlModelOutputs().isEmpty()) {
            log.warn("No model outputs found in LLM response");
            return facts;
        }

        ModelTensors modelTensors = tensorOutput.getMlModelOutputs().get(0);
        if (modelTensors.getMlModelTensors() == null || modelTensors.getMlModelTensors().isEmpty()) {
            log.warn("No model tensors found in LLM response");
            return facts;
        }

        // Try to extract the response content generically
        String responseContent = extractResponseContent(modelTensors.getMlModelTensors().get(0).getDataAsMap());
        if (responseContent == null) {
            log.warn("Could not extract response content from LLM response");
            return facts;
        }

        // Parse the JSON response to extract facts
        try (
            XContentParser parser = jsonXContent
                .createParser(xContentRegistry, LoggingDeprecationHandler.INSTANCE, responseContent)
        ) {
            ensureExpectedToken(XContentParser.Token.START_OBJECT, parser.nextToken(), parser);

            while (parser.nextToken() != XContentParser.Token.END_OBJECT) {
                String fieldName = parser.currentName();
                if ("facts".equals(fieldName)) {
                    ensureExpectedToken(XContentParser.Token.START_ARRAY, parser.nextToken(), parser);
                    while (parser.nextToken() != XContentParser.Token.END_ARRAY) {
                        String fact = parser.text();
                        facts.add(fact);
                    }
                } else {
                    parser.skipChildren();
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse facts from LLM response: {}", responseContent, e);
            throw new IllegalArgumentException("Failed to parse facts from LLM response", e);
        }

        return facts;
    }

    /**
     * Generic method to extract response content from different LLM providers
     * Follows the same pattern as Agentic Search and other ML Commons components
     * Handles OpenAI, Claude, Bedrock, and other provider response formats
     */
    private String extractResponseContent(Map<String, ?> dataMap) {
        if (dataMap == null) {
            return null;
        }

        // Pattern 1: Simple response field (most common pattern across ML Commons)
        if (dataMap.containsKey("response")) {
            return (String) dataMap.get("response");
        }

        // Pattern 2: OpenAI format - choices[0].message.content
        if (dataMap.containsKey("choices")) {
            try {
                List<?> choices = (List<?>) dataMap.get("choices");
                if (choices != null && !choices.isEmpty()) {
                    Map<String, ?> firstChoice = (Map<String, ?>) choices.get(0);
                    if (firstChoice.containsKey("message")) {
                        Map<String, ?> message = (Map<String, ?>) firstChoice.get("message");
                        if (message.containsKey("content")) {
                            return (String) message.get("content");
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to parse OpenAI format response", e);
            }
        }

        // Pattern 3: Claude/Bedrock Converse format - content[0].text
        if (dataMap.containsKey("content")) {
            try {
                List<?> contentList = (List<?>) dataMap.get("content");
                if (contentList != null && !contentList.isEmpty()) {
                    Map<String, ?> contentItem = (Map<String, ?>) contentList.get(0);
                    if (contentItem.containsKey("text")) {
                        return (String) contentItem.get("text");
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to parse Claude/Bedrock format response", e);
            }
        }

        // Pattern 4: Bedrock completion format
        if (dataMap.containsKey("completion")) {
            return (String) dataMap.get("completion");
        }

        // Pattern 5: Cohere text format
        if (dataMap.containsKey("text")) {
            return (String) dataMap.get("text");
        }

        // Pattern 6: Fallback - return entire response as JSON string (like MLModelTool)
        log.warn("Could not extract response content using standard patterns. Available keys: {}. Returning full response as JSON.", dataMap.keySet());
        try {
            return StringUtils.toJson(dataMap);
        } catch (Exception e) {
            log.error("Failed to serialize response as JSON", e);
            return null;
        }
    }

    private List<MemoryDecision> parseMemoryDecisions(MLTaskResponse response) {
        try {
            MLOutput mlOutput = response.getOutput();
            if (!(mlOutput instanceof ModelTensorOutput)) {
                throw new IllegalStateException("Expected ModelTensorOutput but got: " + mlOutput.getClass().getSimpleName());
            }

            ModelTensorOutput tensorOutput = (ModelTensorOutput) mlOutput;
            List<ModelTensors> tensors = tensorOutput.getMlModelOutputs();
            if (tensors == null || tensors.isEmpty()) {
                throw new IllegalStateException("No model output tensors found");
            }

            // Use the generic response content extraction
            String responseContent = extractResponseContent(tensors.get(0).getMlModelTensors().get(0).getDataAsMap());
            if (responseContent == null) {
                throw new IllegalStateException("No response content found in LLM output");
            }

            // Clean response content (remove markdown code blocks if present)
            if (responseContent.startsWith("```json") && responseContent.endsWith("```")) {
                responseContent = responseContent.substring(7, responseContent.length() - 3).trim();
            } else if (responseContent.startsWith("```") && responseContent.endsWith("```")) {
                responseContent = responseContent.substring(3, responseContent.length() - 3).trim();
            }

            List<MemoryDecision> decisions = new ArrayList<>();
            try (XContentParser parser = jsonXContent.createParser(xContentRegistry, LoggingDeprecationHandler.INSTANCE, responseContent)) {
                ensureExpectedToken(XContentParser.Token.START_OBJECT, parser.nextToken(), parser);

                while (parser.nextToken() != XContentParser.Token.END_OBJECT) {
                    String fieldName = parser.currentName();
                    parser.nextToken();

                    if (MEMORY_DECISION_FIELD.equals(fieldName)) {
                        ensureExpectedToken(XContentParser.Token.START_ARRAY, parser.currentToken(), parser);
                        while (parser.nextToken() != XContentParser.Token.END_ARRAY) {
                            decisions.add(MemoryDecision.parse(parser));
                        }
                    } else {
                        parser.skipChildren();
                    }
                }
            }

            return decisions;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse memory decisions", e);
        }
    }
}
