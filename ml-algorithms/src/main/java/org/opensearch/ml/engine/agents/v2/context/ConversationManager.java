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
 * Manages conversation history and builds LLM requests.
 * Determines which interactions to include in each LLM call.
 */
public interface ConversationManager {

    /**
     * Build an LLMRequest from the current agent state.
     * Decides which messages/interactions to include based on context window strategy.
     */
    LLMRequest buildNextRequest(AgentState state, String currentInput, List<InteractionTurn> interactions, List<ToolSpec> toolSpecs);
}
