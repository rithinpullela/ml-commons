/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.engine.agents.v2;

import java.util.List;

import org.opensearch.core.action.ActionListener;
import org.opensearch.ml.common.agent.v2.AssistantTurn;
import org.opensearch.ml.common.agent.v2.LLMRequest;
import org.opensearch.ml.common.agent.v2.LLMResponse;
import org.opensearch.ml.common.agent.v2.ToolCallResult;
import org.opensearch.ml.common.agent.v2.ToolExecutionContext;
import org.opensearch.ml.common.agent.v2.ToolResultTurn;
import org.opensearch.ml.engine.agents.v2.context.ConversationManager;
import org.opensearch.ml.engine.agents.v2.llm.LLMInterface;
import org.opensearch.ml.engine.agents.v2.tools.ToolHandler;

import lombok.extern.log4j.Log4j2;

/**
 * V2 Agent — the central event loop.
 * <p>
 * Repeatedly calls LLM → executes tool calls → feeds results back to LLM
 * until the LLM returns a final answer (no more tool calls) or max iterations reached.
 * <p>
 * Unlike V1 (MLChatAgentRunner), this loop:
 * - Uses structured types (LLMRequest/LLMResponse, not raw Map/String)
 * - Executes ALL tool calls in parallel (not one at a time)
 * - Keeps full interaction history in structured form
 * - Delegates provider-specific formatting to ModelProvider (not FunctionCalling)
 */
@Log4j2
public class AgentV2 {

    private final LLMInterface llm;
    private final ToolHandler toolHandler;
    private final ConversationManager conversationManager;
    private final AgentState state;

    public AgentV2(LLMInterface llm, ToolHandler toolHandler, ConversationManager conversationManager, AgentState state) {
        this.llm = llm;
        this.toolHandler = toolHandler;
        this.conversationManager = conversationManager;
        this.state = state;
    }

    /**
     * Run the agent with the given input.
     * The listener receives the final text answer as a String.
     * Interaction history is stored in AgentState and accessible after completion.
     */
    public void run(String input, ActionListener<String> listener) {
        log.info("V2 Agent starting: {}, maxIterations={}", state.getAgentConfig().getName(), state.getMaxIterations());

        // Start the event loop
        runLoop(input, listener);
    }

    public AgentState getState() {
        return state;
    }

    /**
     * Recursive event loop: LLM call → tool execution → LLM call → ...
     */
    private void runLoop(String input, ActionListener<String> listener) {
        int iteration = state.incrementIteration();
        if (iteration > state.getMaxIterations()) {
            log.warn("V2 Agent reached max iterations ({}), returning last available response", state.getMaxIterations());
            listener.onResponse("Agent reached maximum iterations without a final answer.");
            return;
        }

        log.info("V2 Agent iteration {}/{}", iteration, state.getMaxIterations());

        // Build request via conversation manager
        LLMRequest request = conversationManager.buildNextRequest(state, input, state.getInteractions(), toolHandler.getToolSpecs());

        // Call LLM
        llm.call(request, ActionListener.wrap(response -> handleLLMResponse(input, response, listener), e -> {
            log.error("V2 Agent LLM call failed at iteration {}", iteration, e);
            listener.onFailure(e);
        }));
    }

    /**
     * Handle LLM response: either return final answer or execute tool calls and loop.
     */
    private void handleLLMResponse(String input, LLMResponse response, ActionListener<String> listener) {
        // Record assistant turn
        state.addInteraction(new AssistantTurn(response.getRawAssistantMessage()));

        if (response.isFinalAnswer()) {
            // Done! Return the text response
            String answer = response.getTextContent() != null ? response.getTextContent() : "";
            log.info("V2 Agent completed with final answer at iteration {}", state.getIteration());
            state.setFinalAnswer(answer);
            listener.onResponse(answer);
            return;
        }

        // Execute all tool calls in parallel
        log.info("V2 Agent executing {} tool call(s) at iteration {}", response.getToolCalls().size(), state.getIteration());

        ToolExecutionContext context = new ToolExecutionContext(
            null, // individual tool call IDs set in ToolHandler
            state.getTenantId(),
            state.getRuntimeParams()
        );

        toolHandler.executeParallel(response.getToolCalls(), context, ActionListener.wrap(results -> {
            // Record tool results
            state.addInteraction(new ToolResultTurn(results));

            logToolResults(results);

            // Continue the loop with tool results added to state
            runLoop(input, listener);
        }, e -> {
            log.error("V2 Agent tool execution failed at iteration {}", state.getIteration(), e);
            listener.onFailure(e);
        }));
    }

    private void logToolResults(List<ToolCallResult> results) {
        for (ToolCallResult result : results) {
            if (result.isError()) {
                log.warn("Tool {} returned error: {}", result.getToolName(), result.getContent());
            } else {
                log
                    .debug(
                        "Tool {} returned success (content length: {})",
                        result.getToolName(),
                        result.getContent() != null ? result.getContent().length() : 0
                    );
            }
        }
    }
}
