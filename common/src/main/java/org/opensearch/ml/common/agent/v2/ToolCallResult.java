/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.common.agent.v2;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
public class ToolCallResult {

    public enum ToolResultStatus {
        SUCCESS,
        ERROR
    }

    private String toolCallId;
    private String toolName;
    private ToolResultStatus status;
    private String content;

    public boolean isError() {
        return status == ToolResultStatus.ERROR;
    }

    public static ToolCallResult success(String toolCallId, String toolName, String content) {
        return ToolCallResult.builder().toolCallId(toolCallId).toolName(toolName).status(ToolResultStatus.SUCCESS).content(content).build();
    }

    public static ToolCallResult error(String toolCallId, String toolName, String errorMessage) {
        return ToolCallResult
            .builder()
            .toolCallId(toolCallId)
            .toolName(toolName)
            .status(ToolResultStatus.ERROR)
            .content(errorMessage)
            .build();
    }
}
