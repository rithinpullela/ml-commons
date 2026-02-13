/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.common.agent.v2;

import java.util.Map;

/**
 * Factory interface for creating native V2 tool instances.
 * <p>
 * Unlike V1 Tool.Factory which produces Map&lt;String,String&gt;-based tools,
 * V2 factories produce tools that accept structured Map&lt;String,Object&gt; arguments
 * directly from LLM function call outputs.
 * <p>
 * Each V2 tool implementation should provide a nested Factory class implementing this interface.
 */
public interface AgentToolV2Factory {

    /**
     * Create a V2 tool instance from configuration parameters.
     * @param params Tool configuration (from agent tool spec registration)
     * @return Configured AgentToolV2 instance
     */
    AgentToolV2 create(Map<String, Object> params);

    /**
     * Get the tool type identifier for registry lookup.
     * @return Type key (e.g. "SearchIndexToolV2")
     */
    String getDefaultType();

    /**
     * Get the default tool specification including JSON Schema.
     * @return Default ToolSpec
     */
    ToolSpec getDefaultToolSpec();
}
