/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.common.agent.v2;

import java.util.List;
import java.util.Map;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LLMResponse {
    private String textContent;
    private String thinkingContent;
    private List<ToolCallRequest> toolCalls;
    private StopReason stopReason;
    private Map<String, ?> rawAssistantMessage;

    public boolean isFinalAnswer() {
        return toolCalls == null || toolCalls.isEmpty();
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
