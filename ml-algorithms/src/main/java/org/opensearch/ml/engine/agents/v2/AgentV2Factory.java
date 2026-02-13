/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.engine.agents.v2;

import java.util.Map;

import org.opensearch.ml.common.agent.MLAgent;
import org.opensearch.ml.common.spi.tools.Tool;
import org.opensearch.ml.engine.agents.v2.context.ConversationManager;
import org.opensearch.ml.engine.agents.v2.context.PassthroughConversationManager;
import org.opensearch.ml.engine.agents.v2.llm.LLMFactory;
import org.opensearch.ml.engine.agents.v2.llm.LLMInterface;
import org.opensearch.ml.engine.agents.v2.tools.ToolCreationHelper;
import org.opensearch.ml.engine.agents.v2.tools.ToolHandler;
import org.opensearch.transport.client.Client;

import lombok.extern.log4j.Log4j2;

/**
 * Factory that wires together all V2 agent components.
 * Called from MLAgentExecutor when V2 routing is triggered.
 */
@Log4j2
public class AgentV2Factory {

    /**
     * Create and configure a V2 agent from agent configuration.
     *
     * @param agent The MLAgent configuration (must have model field)
     * @param client The OpenSearch client
     * @param params Runtime parameters
     * @param toolFactories V1 tool factory map
     * @param internalModelId The registered model ID in OpenSearch (from agent model service)
     * @return Configured AgentV2 instance ready to run
     */
    public static AgentV2 create(
        MLAgent agent,
        Client client,
        Map<String, String> params,
        Map<String, Tool.Factory> toolFactories,
        String internalModelId
    ) {
        log
            .info(
                "Creating V2 agent: {} (model: {}, provider: {})",
                agent.getName(),
                agent.getModel().getModelId(),
                agent.getModel().getModelProvider()
            );

        // 1. Create LLM interface
        LLMInterface llm = LLMFactory.create(agent, client, internalModelId);

        // 2. Create tool handler via V1 factories + adapter
        ToolHandler toolHandler = ToolCreationHelper.createToolHandler(toolFactories, agent, params);

        // 3. Create conversation manager (passthrough for now)
        ConversationManager conversationManager = new PassthroughConversationManager();

        // 4. Create agent state
        AgentState state = AgentState.from(agent, params);

        // 5. Wire it all together
        return new AgentV2(llm, toolHandler, conversationManager, state);
    }
}
