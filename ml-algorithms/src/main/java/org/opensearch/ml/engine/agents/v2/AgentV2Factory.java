/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.engine.agents.v2;

import java.util.Map;

import org.opensearch.core.action.ActionListener;
import org.opensearch.ml.common.agent.MLAgent;
import org.opensearch.ml.common.agent.v2.AgentToolV2Factory;
import org.opensearch.ml.common.spi.tools.Tool;
import org.opensearch.ml.engine.agents.v2.context.ConversationManager;
import org.opensearch.ml.engine.agents.v2.context.PassthroughConversationManager;
import org.opensearch.ml.engine.agents.v2.llm.LLMFactory;
import org.opensearch.ml.engine.agents.v2.llm.LLMInterface;
import org.opensearch.ml.engine.agents.v2.tools.ToolCreationHelper;
import org.opensearch.ml.engine.encryptor.Encryptor;
import org.opensearch.remote.metadata.client.SdkClient;
import org.opensearch.transport.client.Client;

import lombok.extern.log4j.Log4j2;

/**
 * Factory that wires together all V2 agent components.
 * Called from MLAgentExecutor when V2 routing is triggered.
 * <p>
 * Async because tool creation may require MCP connector discovery (network calls).
 */
@Log4j2
public class AgentV2Factory {

    /**
     * Create and configure a V2 agent from agent configuration.
     * Async because ToolCreationHelper may need to discover MCP tools via connectors.
     *
     * @param agent            The MLAgent configuration (must have model field)
     * @param client           The OpenSearch client
     * @param sdkClient        SDK client for connector lookups
     * @param encryptor        Encryptor for MCP credential decryption
     * @param params           Runtime parameters
     * @param v1ToolFactories  V1 tool factory map
     * @param v2ToolFactories  V2 native tool factory map (may be null)
     * @param internalModelId  The registered model ID in OpenSearch
     * @param listener         Callback with the configured AgentV2
     */
    public static void create(
        MLAgent agent,
        Client client,
        SdkClient sdkClient,
        Encryptor encryptor,
        Map<String, String> params,
        Map<String, Tool.Factory> v1ToolFactories,
        Map<String, AgentToolV2Factory> v2ToolFactories,
        String internalModelId,
        ActionListener<AgentV2> listener
    ) {
        log
            .info(
                "Creating V2 agent: {} (model: {}, provider: {})",
                agent.getName(),
                agent.getModel().getModelId(),
                agent.getModel().getModelProvider()
            );

        // 1. Create LLM interface (sync — no I/O)
        LLMInterface llm = LLMFactory.create(agent, client, internalModelId);

        // 2. Create conversation manager (sync)
        ConversationManager conversationManager = new PassthroughConversationManager();

        // 3. Create agent state (sync)
        AgentState state = AgentState.from(agent, params);

        // 4. Create tool handler (async — MCP discovery may need network calls)
        ToolCreationHelper
            .createToolHandler(
                v2ToolFactories,
                v1ToolFactories,
                agent,
                params,
                client,
                sdkClient,
                encryptor,
                ActionListener.wrap(toolHandler -> {
                    // 5. Wire it all together
                    AgentV2 agentV2 = new AgentV2(llm, toolHandler, conversationManager, state);
                    listener.onResponse(agentV2);
                }, e -> {
                    log.error("Failed to create tool handler for V2 agent: {}", agent.getName(), e);
                    listener.onFailure(e);
                })
            );
    }
}
