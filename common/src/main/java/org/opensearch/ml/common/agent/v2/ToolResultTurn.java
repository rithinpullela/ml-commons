/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.common.agent.v2;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * A tool result turn containing one or more tool results (for parallel tool calls).
 */
@Data
@AllArgsConstructor
public class ToolResultTurn implements InteractionTurn {
    private List<ToolCallResult> results;
}
