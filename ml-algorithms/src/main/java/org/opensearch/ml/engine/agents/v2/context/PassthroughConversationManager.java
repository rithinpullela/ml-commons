/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.engine.agents.v2.context;

import java.util.List;

import org.opensearch.ml.common.agent.v2.InteractionTurn;
import org.opensearch.ml.common.agent.v2.LLMRequest;
import org.opensearch.ml.common.agent.v2.ToolSpec;
import org.opensearch.ml.engine.agents.v2.AgentState;

/**
 * Passthrough conversation manager — includes ALL interactions in every request.
 * No truncation or windowing. Suitable for short conversations and initial POC.
 */
public class PassthroughConversationManager implements ConversationManager {

    @Override
    public LLMRequest buildNextRequest(
        AgentState state,
        String currentInput,
        List<InteractionTurn> interactions,
        List<ToolSpec> toolSpecs
    ) {
        return LLMRequest
            .builder()
            .systemPrompt(state.getSystemPrompt())
            .currentInput(currentInput)
            .interactions(interactions)
            .toolSpecs(toolSpecs)
            .build();
    }
}
