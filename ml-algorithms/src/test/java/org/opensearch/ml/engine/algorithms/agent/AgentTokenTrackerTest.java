/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.engine.algorithms.agent;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.opensearch.ml.common.agent.TokenUsage;

public class AgentTokenTrackerTest {

    private AgentTokenTracker tracker;

    @Before
    public void setUp() {
        tracker = new AgentTokenTracker();
    }

    @Test
    public void testRecordSingleTurn() {
        TokenUsage usage = TokenUsage.builder().inputTokens(100L).outputTokens(50L).totalTokens(150L).build();

        tracker.setModelMetadata("gpt-4", "https://api.openai.com/v1/chat/completions", "gpt-4");
        tracker.recordTurn("gpt-4", usage);

        assertTrue(tracker.hasUsage());

        Map<String, Object> output = tracker.toOutputMap();
        assertNotNull(output);

        List<Map<String, Object>> perTurnUsage = (List<Map<String, Object>>) output.get("per_turn_usage");
        assertEquals(1, perTurnUsage.size());

        Map<String, Object> turn = perTurnUsage.get(0);
        assertEquals(1, turn.get("turn"));
        assertEquals("gpt-4", turn.get("model_name"));
        assertEquals(100L, turn.get("input_tokens"));
        assertEquals(50L, turn.get("output_tokens"));
        assertEquals(150L, turn.get("total_tokens"));
    }

    @Test
    public void testRecordMultipleTurns() {
        TokenUsage usage1 = TokenUsage.builder().inputTokens(100L).outputTokens(50L).totalTokens(150L).build();

        TokenUsage usage2 = TokenUsage.builder().inputTokens(200L).outputTokens(75L).totalTokens(275L).build();

        TokenUsage usage3 = TokenUsage.builder().inputTokens(150L).outputTokens(60L).totalTokens(210L).build();

        tracker.setModelMetadata("gpt-4", "https://api.openai.com/v1/chat/completions", "gpt-4");
        tracker.recordTurn("gpt-4", usage1);
        tracker.recordTurn("gpt-4", usage2);
        tracker.recordTurn("gpt-4", usage3);

        Map<String, Object> output = tracker.toOutputMap();

        List<Map<String, Object>> perTurnUsage = (List<Map<String, Object>>) output.get("per_turn_usage");
        assertEquals(3, perTurnUsage.size());

        // Verify turn numbers
        assertEquals(1, perTurnUsage.get(0).get("turn"));
        assertEquals(2, perTurnUsage.get(1).get("turn"));
        assertEquals(3, perTurnUsage.get(2).get("turn"));
    }

    @Test
    public void testPerModelAggregation() {
        TokenUsage usage1 = TokenUsage.builder().inputTokens(100L).outputTokens(50L).totalTokens(150L).build();

        TokenUsage usage2 = TokenUsage.builder().inputTokens(200L).outputTokens(75L).totalTokens(275L).build();

        tracker.recordTurn("gpt-4", usage1);
        tracker.recordTurn("gpt-4", usage2);

        Map<String, Object> output = tracker.toOutputMap();

        List<Map<String, Object>> perModelUsage = (List<Map<String, Object>>) output.get("per_model_usage");
        assertEquals(1, perModelUsage.size());

        Map<String, Object> modelData = perModelUsage.get(0);
        assertEquals("gpt-4", modelData.get("model_name"));
        assertEquals(300L, modelData.get("input_tokens"));
        assertEquals(125L, modelData.get("output_tokens"));
        assertEquals(425L, modelData.get("total_tokens"));
        assertEquals(2, modelData.get("call_count"));
    }

    @Test
    public void testMultipleModels() {
        TokenUsage usage1 = TokenUsage.builder().inputTokens(100L).outputTokens(50L).build();

        TokenUsage usage2 = TokenUsage.builder().inputTokens(200L).outputTokens(75L).build();

        TokenUsage usage3 = TokenUsage.builder().inputTokens(150L).outputTokens(60L).build();

        tracker.setModelMetadata("gpt-4", "https://api.openai.com/v1/chat/completions", "gpt-4");
        tracker.setModelMetadata("claude-3", "https://api.anthropic.com/v1/messages", "claude-3");
        tracker.recordTurn("gpt-4", usage1);
        tracker.recordTurn("claude-3", usage2);
        tracker.recordTurn("gpt-4", usage3);

        Map<String, Object> output = tracker.toOutputMap();

        List<Map<String, Object>> perModelUsage = (List<Map<String, Object>>) output.get("per_model_usage");
        assertEquals(2, perModelUsage.size());

        // Find each model's data
        Map<String, Object> gpt4Data = perModelUsage.stream().filter(m -> "gpt-4".equals(m.get("model_id"))).findFirst().orElse(null);
        Map<String, Object> claudeData = perModelUsage
            .stream()
            .filter(m -> "claude-3".equals(m.get("model_id")))
            .findFirst()
            .orElse(null);

        assertNotNull(gpt4Data);
        assertNotNull(claudeData);

        assertEquals(250L, gpt4Data.get("input_tokens"));
        assertEquals(110L, gpt4Data.get("output_tokens"));
        assertEquals(2, gpt4Data.get("call_count"));

        assertEquals(200L, claudeData.get("input_tokens"));
        assertEquals(75L, claudeData.get("output_tokens"));
        assertEquals(1, claudeData.get("call_count"));
    }

    @Test
    public void testRecordWithCacheTokens() {
        TokenUsage usage = TokenUsage
            .builder()
            .inputTokens(100L)
            .outputTokens(50L)
            .totalTokens(150L)
            .cacheReadInputTokens(20L)
            .cacheCreationInputTokens(10L)
            .build();

        tracker.setModelMetadata("claude-3", "https://api.anthropic.com/v1/messages", "claude-3");
        tracker.recordTurn("claude-3", usage);

        Map<String, Object> output = tracker.toOutputMap();
        List<Map<String, Object>> perTurnUsage = (List<Map<String, Object>>) output.get("per_turn_usage");

        Map<String, Object> turn = perTurnUsage.get(0);
        assertEquals(20L, turn.get("cache_read_input_tokens"));
        assertEquals(10L, turn.get("cache_creation_input_tokens"));
    }

    @Test
    public void testRecordWithReasoningTokens() {
        TokenUsage usage = TokenUsage.builder().inputTokens(100L).outputTokens(50L).totalTokens(150L).reasoningTokens(30L).build();

        tracker.setModelMetadata("o1-preview", "https://api.openai.com/v1/chat/completions", "o1-preview");
        tracker.recordTurn("o1-preview", usage);

        Map<String, Object> output = tracker.toOutputMap();
        List<Map<String, Object>> perTurnUsage = (List<Map<String, Object>>) output.get("per_turn_usage");

        Map<String, Object> turn = perTurnUsage.get(0);
        assertEquals(30L, turn.get("reasoning_tokens"));
    }

    @Test
    public void testRecordWithNullModelName() {
        TokenUsage usage = TokenUsage.builder().inputTokens(100L).outputTokens(50L).build();

        tracker.recordTurn(null, usage);

        assertFalse(tracker.hasUsage());
    }

    @Test
    public void testRecordWithNullUsage() {
        tracker.recordTurn("gpt-4", null);

        assertFalse(tracker.hasUsage());
    }

    @Test
    public void testHasUsageWhenEmpty() {
        assertFalse(tracker.hasUsage());
    }

    @Test
    public void testEmptyOutputMap() {
        Map<String, Object> output = tracker.toOutputMap();

        assertNotNull(output);
        assertTrue(((List<?>) output.get("per_turn_usage")).isEmpty());
        assertTrue(((List<?>) output.get("per_model_usage")).isEmpty());
    }
}
