/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.engine.agents.v2.tools;

import org.opensearch.ml.engine.agents.v2.AgentState;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Context available to tools during execution.
 */
@Data
@AllArgsConstructor
public class ToolExecutionContext {
    private String toolCallId;
    private AgentState agentState;
    private String tenantId;
}
