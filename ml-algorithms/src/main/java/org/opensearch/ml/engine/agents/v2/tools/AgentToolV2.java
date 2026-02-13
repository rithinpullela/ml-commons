/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.engine.agents.v2.tools;

import java.util.Map;

import org.opensearch.core.action.ActionListener;
import org.opensearch.ml.common.agent.v2.ToolCallResult;
import org.opensearch.ml.common.agent.v2.ToolSpec;

/**
 * V2 Tool Interface — the primary tool contract for V2 agents.
 * <p>
 * V2 tools accept structured arguments (Map&lt;String, Object&gt;) from LLM function calls,
 * preserving JSON types. This contrasts with V1 tools which use flat Map&lt;String, String&gt;.
 */
public interface AgentToolV2 {

    String getName();

    String getDescription();

    String getType();

    /**
     * Full tool specification with JSON Schema for input parameters.
     * This is what gets sent to the LLM for function calling.
     */
    ToolSpec getToolSpec();

    /**
     * Validate input arguments against the tool's schema.
     * @return null if valid, error message if invalid.
     */
    default String validateInput(Map<String, Object> arguments) {
        return null;
    }

    /**
     * Execute the tool with structured arguments from the LLM's function call output.
     */
    void execute(Map<String, Object> arguments, ToolExecutionContext context, ActionListener<ToolCallResult> listener);
}
