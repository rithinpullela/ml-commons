/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.common.agent.v2;

import java.util.List;

import lombok.Builder;
import lombok.Data;

/**
 * Provider-agnostic request to the LLM.
 */
@Data
@Builder
public class LLMRequest {
    private String systemPrompt;
    private String currentInput;
    private List<InteractionTurn> interactions;
    private List<ToolSpec> toolSpecs;
}
