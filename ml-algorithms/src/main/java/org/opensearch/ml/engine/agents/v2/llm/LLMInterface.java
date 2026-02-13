/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.engine.agents.v2.llm;

import org.opensearch.core.action.ActionListener;
import org.opensearch.ml.common.agent.v2.LLMRequest;
import org.opensearch.ml.common.agent.v2.LLMResponse;

/**
 * Abstraction for LLM calls. The agent loop talks to this interface only.
 * Implementation composes ModelProvider (for formatting) + Client (for execution).
 */
public interface LLMInterface {
    void call(LLMRequest request, ActionListener<LLMResponse> listener);
}
