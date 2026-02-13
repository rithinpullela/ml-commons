/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.engine.agents.v2;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.ml.common.agent.MLAgent;

import lombok.Getter;
import lombok.Setter;

/**
 * Mutable execution state for a V2 agent run.
 * Created fresh for each agent execution.
 */
@Getter
public class AgentState {
    private final MLAgent agentConfig;
    private final int maxIterations;
    private final String systemPrompt;
    private final String tenantId;
    private final Map<String, String> runtimeParams;

    private final AtomicInteger iteration = new AtomicInteger(0);
    @Setter
    private String finalAnswer;
    private final Map<String, Object> additionalInfo = new ConcurrentHashMap<>();

    public AgentState(MLAgent agentConfig, int maxIterations, String systemPrompt, String tenantId, Map<String, String> runtimeParams) {
        this.agentConfig = agentConfig;
        this.maxIterations = maxIterations;
        this.systemPrompt = systemPrompt;
        this.tenantId = tenantId;
        this.runtimeParams = runtimeParams;
    }

    public int getIteration() {
        return iteration.get();
    }

    public int incrementIteration() {
        return iteration.incrementAndGet();
    }

    public static AgentState from(MLAgent agent, Map<String, String> params) {
        int maxIterations = 20;
        if (params.containsKey("max_iteration")) {
            try {
                maxIterations = Integer.parseInt(params.get("max_iteration"));
            } catch (NumberFormatException e) {
                // use default
            }
        }

        String systemPrompt = resolveSystemPrompt(agent, params);
        String tenantId = params.get("tenant_id");

        return new AgentState(agent, maxIterations, systemPrompt, tenantId, params);
    }

    private static String resolveSystemPrompt(MLAgent agent, Map<String, String> params) {
        // Check runtime params first, then agent parameters, then model parameters
        if (params.containsKey("system_prompt")) {
            return params.get("system_prompt");
        }
        if (agent.getParameters() != null && agent.getParameters().containsKey("system_prompt")) {
            return agent.getParameters().get("system_prompt");
        }
        if (agent.getModel() != null
            && agent.getModel().getModelParameters() != null
            && agent.getModel().getModelParameters().containsKey("system_prompt")) {
            return agent.getModel().getModelParameters().get("system_prompt");
        }
        return "You are a helpful assistant.";
    }
}
