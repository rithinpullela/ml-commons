/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.engine.agents.v2.llm;

import java.util.Map;

import org.opensearch.ml.common.agent.MLAgent;
import org.opensearch.ml.common.agent.MLAgentModelSpec;
import org.opensearch.ml.common.model.ModelProvider;
import org.opensearch.ml.common.model.ModelProviderFactory;
import org.opensearch.transport.client.Client;

import lombok.extern.log4j.Log4j2;

/**
 * Factory for creating LLMInterface instances from agent configuration.
 * Uses ModelProviderFactory to get the correct ModelProvider (which now has V2 methods).
 */
@Log4j2
public class LLMFactory {

    /**
     * Create an LLMInterface from agent config.
     * The agent must have a "model" field (MLAgentModelSpec) with modelId and modelProvider.
     */
    public static LLMInterface create(MLAgent agent, Client client, String internalModelId) {
        MLAgentModelSpec modelSpec = agent.getModel();
        if (modelSpec == null) {
            throw new IllegalStateException("V2 agent must have a model configuration");
        }

        String providerType = modelSpec.getModelProvider();
        ModelProvider modelProvider = ModelProviderFactory.getProvider(providerType);
        Map<String, String> baseParams = modelSpec.getModelParameters();

        return new LLMInterfaceImpl(internalModelId, client, modelProvider, baseParams);
    }
}
