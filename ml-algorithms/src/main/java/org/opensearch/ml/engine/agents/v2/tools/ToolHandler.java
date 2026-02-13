/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.engine.agents.v2.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.opensearch.core.action.ActionListener;
import org.opensearch.ml.common.agent.v2.AgentToolV2;
import org.opensearch.ml.common.agent.v2.ToolCallRequest;
import org.opensearch.ml.common.agent.v2.ToolCallResult;
import org.opensearch.ml.common.agent.v2.ToolExecutionContext;
import org.opensearch.ml.common.agent.v2.ToolSpec;

import lombok.extern.log4j.Log4j2;

/**
 * V2 Tool Registry + Executor.
 * Manages tool registration and handles parallel tool execution.
 */
@Log4j2
public class ToolHandler {

    private final Map<String, AgentToolV2> tools = new HashMap<>();

    public void register(AgentToolV2 tool) {
        tools.put(tool.getName(), tool);
        log.debug("Registered tool: {} (type: {})", tool.getName(), tool.getType());
    }

    public AgentToolV2 getTool(String name) {
        return tools.get(name);
    }

    public List<ToolSpec> getToolSpecs() {
        return tools.values().stream().map(AgentToolV2::getToolSpec).collect(Collectors.toList());
    }

    public boolean isEmpty() {
        return tools.isEmpty();
    }

    /**
     * Execute tool calls in parallel and collect all results.
     * All tool calls are dispatched immediately; the listener is called
     * once ALL results are collected (success or error).
     */
    public void executeParallel(
        List<ToolCallRequest> toolCalls,
        ToolExecutionContext context,
        ActionListener<List<ToolCallResult>> listener
    ) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            listener.onResponse(new ArrayList<>());
            return;
        }

        List<ToolCallResult> results = new ArrayList<>(toolCalls.size());
        // Pre-fill with nulls so we can set by index (preserves order)
        for (int i = 0; i < toolCalls.size(); i++) {
            results.add(null);
        }

        AtomicInteger remaining = new AtomicInteger(toolCalls.size());

        for (int i = 0; i < toolCalls.size(); i++) {
            final int index = i;
            ToolCallRequest call = toolCalls.get(i);
            AgentToolV2 tool = tools.get(call.getToolName());

            if (tool == null) {
                ToolCallResult errorResult = ToolCallResult
                    .error(call.getToolCallId(), call.getToolName(), "Tool not found: " + call.getToolName());
                results.set(index, errorResult);
                if (remaining.decrementAndGet() == 0) {
                    listener.onResponse(results);
                }
                continue;
            }

            // Create per-tool context with the tool call id
            ToolExecutionContext toolContext = new ToolExecutionContext(
                call.getToolCallId(),
                context.getTenantId(),
                context.getRuntimeParams()
            );

            try {
                tool.execute(call.getArguments(), toolContext, ActionListener.wrap(result -> {
                    results.set(index, result);
                    if (remaining.decrementAndGet() == 0) {
                        listener.onResponse(results);
                    }
                }, e -> {
                    log.error("Tool execution failed: {}", call.getToolName(), e);
                    ToolCallResult errorResult = ToolCallResult
                        .error(call.getToolCallId(), call.getToolName(), "Tool execution error: " + e.getMessage());
                    results.set(index, errorResult);
                    if (remaining.decrementAndGet() == 0) {
                        listener.onResponse(results);
                    }
                }));
            } catch (Exception e) {
                log.error("Failed to invoke tool: {}", call.getToolName(), e);
                ToolCallResult errorResult = ToolCallResult
                    .error(call.getToolCallId(), call.getToolName(), "Failed to invoke tool: " + e.getMessage());
                results.set(index, errorResult);
                if (remaining.decrementAndGet() == 0) {
                    listener.onResponse(results);
                }
            }
        }
    }
}
