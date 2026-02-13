/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.common.agent.v2;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Tool specification — what the LLM sees for function calling.
 */
@Data
@AllArgsConstructor
public class ToolSpec {
    private String name;
    private String description;
    private Map<String, Object> inputSchema;
}
