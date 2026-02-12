/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.common.agent;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;
import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.xcontent.ToXContent;
import org.opensearch.core.xcontent.XContentBuilder;

public class TokenUsageTest {

    @Test
    public void testBuilder() {
        TokenUsage usage = TokenUsage
            .builder()
            .inputTokens(100L)
            .outputTokens(50L)
            .totalTokens(150L)
            .cacheReadInputTokens(20L)
            .cacheCreationInputTokens(10L)
            .reasoningTokens(5L)
            .build();

        assertEquals(Long.valueOf(100L), usage.getInputTokens());
        assertEquals(Long.valueOf(50L), usage.getOutputTokens());
        assertEquals(Long.valueOf(150L), usage.getTotalTokens());
        assertEquals(Long.valueOf(20L), usage.getCacheReadInputTokens());
        assertEquals(Long.valueOf(10L), usage.getCacheCreationInputTokens());
        assertEquals(Long.valueOf(5L), usage.getReasoningTokens());
    }

    @Test
    public void testBuilderWithNullValues() {
        TokenUsage usage = TokenUsage.builder().inputTokens(100L).outputTokens(50L).build();

        assertEquals(Long.valueOf(100L), usage.getInputTokens());
        assertEquals(Long.valueOf(50L), usage.getOutputTokens());
        assertNull(usage.getTotalTokens());
        assertNull(usage.getCacheReadInputTokens());
        assertNull(usage.getCacheCreationInputTokens());
        assertNull(usage.getReasoningTokens());
    }

    @Test
    public void testAddTokens() {
        TokenUsage usage1 = TokenUsage.builder().inputTokens(100L).outputTokens(50L).totalTokens(150L).build();

        TokenUsage usage2 = TokenUsage.builder().inputTokens(200L).outputTokens(75L).totalTokens(275L).build();

        TokenUsage combined = usage1.addTokens(usage2);

        assertEquals(Long.valueOf(300L), combined.getInputTokens());
        assertEquals(Long.valueOf(125L), combined.getOutputTokens());
        assertEquals(Long.valueOf(425L), combined.getTotalTokens());
    }

    @Test
    public void testAddTokensWithNulls() {
        TokenUsage usage1 = TokenUsage.builder().inputTokens(100L).build();

        TokenUsage usage2 = TokenUsage.builder().outputTokens(50L).build();

        TokenUsage combined = usage1.addTokens(usage2);

        assertEquals(Long.valueOf(100L), combined.getInputTokens());
        assertEquals(Long.valueOf(50L), combined.getOutputTokens());
        assertNull(combined.getTotalTokens());
    }

    @Test
    public void testAddTokensWithNull() {
        TokenUsage usage = TokenUsage.builder().inputTokens(100L).outputTokens(50L).build();

        TokenUsage result = usage.addTokens(null);

        assertSame(usage, result);
    }

    @Test
    public void testAddTokensWithAdditionalUsage() {
        Map<String, Long> additional1 = new HashMap<>();
        additional1.put("audio_tokens", 10L);

        Map<String, Long> additional2 = new HashMap<>();
        additional2.put("audio_tokens", 5L);
        additional2.put("video_tokens", 20L);

        TokenUsage usage1 = TokenUsage.builder().inputTokens(100L).additionalUsage(additional1).build();

        TokenUsage usage2 = TokenUsage.builder().inputTokens(50L).additionalUsage(additional2).build();

        TokenUsage combined = usage1.addTokens(usage2);

        assertEquals(Long.valueOf(150L), combined.getInputTokens());
        assertEquals(Long.valueOf(15L), combined.getAdditionalUsage().get("audio_tokens"));
        assertEquals(Long.valueOf(20L), combined.getAdditionalUsage().get("video_tokens"));
    }

    @Test
    public void testGetEffectiveTotalTokens() {
        // Test when totalTokens is provided
        TokenUsage usage1 = TokenUsage.builder().inputTokens(100L).outputTokens(50L).totalTokens(150L).build();
        assertEquals(Long.valueOf(150L), usage1.getEffectiveTotalTokens());

        // Test when totalTokens is computed
        TokenUsage usage2 = TokenUsage.builder().inputTokens(100L).outputTokens(50L).build();
        assertEquals(Long.valueOf(150L), usage2.getEffectiveTotalTokens());

        // Test when input or output is missing
        TokenUsage usage3 = TokenUsage.builder().inputTokens(100L).build();
        assertNull(usage3.getEffectiveTotalTokens());
    }

    @Test
    public void testToXContent() throws IOException {
        TokenUsage usage = TokenUsage.builder().inputTokens(100L).outputTokens(50L).totalTokens(150L).cacheReadInputTokens(20L).build();

        XContentBuilder builder = XContentBuilder.builder(XContentType.JSON.xContent());
        usage.toXContent(builder, ToXContent.EMPTY_PARAMS);
        String json = builder.toString();

        assertTrue(json.contains("\"input_tokens\":100"));
        assertTrue(json.contains("\"output_tokens\":50"));
        assertTrue(json.contains("\"total_tokens\":150"));
        assertTrue(json.contains("\"cache_read_input_tokens\":20"));
        assertFalse(json.contains("cache_creation_input_tokens"));
        assertFalse(json.contains("reasoning_tokens"));
    }

    @Test
    public void testToMap() {
        TokenUsage usage = TokenUsage.builder().inputTokens(100L).outputTokens(50L).totalTokens(150L).cacheReadInputTokens(20L).build();

        Map<String, Object> map = usage.toMap();

        assertEquals(100L, map.get("input_tokens"));
        assertEquals(50L, map.get("output_tokens"));
        assertEquals(150L, map.get("total_tokens"));
        assertEquals(20L, map.get("cache_read_input_tokens"));
        assertFalse(map.containsKey("cache_creation_input_tokens"));
        assertFalse(map.containsKey("reasoning_tokens"));
    }

    @Test
    public void testWriteableDeserialization() throws IOException {
        TokenUsage original = TokenUsage
            .builder()
            .inputTokens(100L)
            .outputTokens(50L)
            .totalTokens(150L)
            .cacheReadInputTokens(20L)
            .cacheCreationInputTokens(10L)
            .reasoningTokens(5L)
            .build();

        BytesStreamOutput output = new BytesStreamOutput();
        original.writeTo(output);

        StreamInput input = output.bytes().streamInput();
        TokenUsage deserialized = new TokenUsage(input);

        assertEquals(original.getInputTokens(), deserialized.getInputTokens());
        assertEquals(original.getOutputTokens(), deserialized.getOutputTokens());
        assertEquals(original.getTotalTokens(), deserialized.getTotalTokens());
        assertEquals(original.getCacheReadInputTokens(), deserialized.getCacheReadInputTokens());
        assertEquals(original.getCacheCreationInputTokens(), deserialized.getCacheCreationInputTokens());
        assertEquals(original.getReasoningTokens(), deserialized.getReasoningTokens());
    }

    @Test
    public void testWriteableDeserializationWithNulls() throws IOException {
        TokenUsage original = TokenUsage.builder().inputTokens(100L).outputTokens(50L).build();

        BytesStreamOutput output = new BytesStreamOutput();
        original.writeTo(output);

        StreamInput input = output.bytes().streamInput();
        TokenUsage deserialized = new TokenUsage(input);

        assertEquals(original.getInputTokens(), deserialized.getInputTokens());
        assertEquals(original.getOutputTokens(), deserialized.getOutputTokens());
        assertNull(deserialized.getTotalTokens());
        assertNull(deserialized.getCacheReadInputTokens());
        assertNull(deserialized.getCacheCreationInputTokens());
        assertNull(deserialized.getReasoningTokens());
    }
}
