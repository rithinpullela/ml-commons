/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.common.agent.v2;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Context available to tools during execution.
 * Contains only the information tools need — no internal agent state.
 */
@Data
@AllArgsConstructor
public class ToolExecutionContext {
    private String toolCallId;
    private String tenantId;
    private Map<String, String> runtimeParams;
}
