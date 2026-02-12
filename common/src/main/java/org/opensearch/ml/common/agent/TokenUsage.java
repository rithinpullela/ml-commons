/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.common.agent;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.common.io.stream.Writeable;
import org.opensearch.core.xcontent.ToXContentObject;
import org.opensearch.core.xcontent.XContentBuilder;

import lombok.Getter;

/**
 * Represents token usage information from LLM API calls.
 * Supports multiple providers (OpenAI, Anthropic Claude, Bedrock, Gemini) including
 * cache tokens and reasoning tokens.
 */
@Getter
public class TokenUsage implements ToXContentObject, Writeable {

    // Core token counts (all providers)
    private final Long inputTokens;           // prompt tokens
    private final Long outputTokens;          // completion/candidate tokens
    private final Long totalTokens;           // sum (computed if not provided)

    // Cache tokens (Anthropic, OpenAI, Gemini)
    private final Long cacheReadInputTokens;     // tokens served from cache
    private final Long cacheCreationInputTokens; // tokens used to create cache

    // Extended tokens
    private final Long reasoningTokens;          // thinking tokens (OpenAI o1, Gemini)

    // Provider-specific additional fields
    private final Map<String, Long> additionalUsage;

    /**
     * Constructor for builder pattern
     */
    @lombok.Builder
    public TokenUsage(
        Long inputTokens,
        Long outputTokens,
        Long totalTokens,
        Long cacheReadInputTokens,
        Long cacheCreationInputTokens,
        Long reasoningTokens,
        Map<String, Long> additionalUsage
    ) {
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.totalTokens = totalTokens;
        this.cacheReadInputTokens = cacheReadInputTokens;
        this.cacheCreationInputTokens = cacheCreationInputTokens;
        this.reasoningTokens = reasoningTokens;
        this.additionalUsage = additionalUsage != null ? additionalUsage : new HashMap<>();
    }

    /**
     * Constructor for deserialization from StreamInput
     */
    public TokenUsage(StreamInput in) throws IOException {
        this.inputTokens = in.readOptionalLong();
        this.outputTokens = in.readOptionalLong();
        this.totalTokens = in.readOptionalLong();
        this.cacheReadInputTokens = in.readOptionalLong();
        this.cacheCreationInputTokens = in.readOptionalLong();
        this.reasoningTokens = in.readOptionalLong();
        this.additionalUsage = in.readMap(StreamInput::readString, StreamInput::readLong);
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeOptionalLong(inputTokens);
        out.writeOptionalLong(outputTokens);
        out.writeOptionalLong(totalTokens);
        out.writeOptionalLong(cacheReadInputTokens);
        out.writeOptionalLong(cacheCreationInputTokens);
        out.writeOptionalLong(reasoningTokens);
        out.writeMap(additionalUsage, StreamOutput::writeString, StreamOutput::writeLong);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        if (inputTokens != null) {
            builder.field("input_tokens", inputTokens);
        }
        if (outputTokens != null) {
            builder.field("output_tokens", outputTokens);
        }
        if (totalTokens != null) {
            builder.field("total_tokens", totalTokens);
        }
        if (cacheReadInputTokens != null) {
            builder.field("cache_read_input_tokens", cacheReadInputTokens);
        }
        if (cacheCreationInputTokens != null) {
            builder.field("cache_creation_input_tokens", cacheCreationInputTokens);
        }
        if (reasoningTokens != null) {
            builder.field("reasoning_tokens", reasoningTokens);
        }
        if (additionalUsage != null && !additionalUsage.isEmpty()) {
            for (Map.Entry<String, Long> entry : additionalUsage.entrySet()) {
                builder.field(entry.getKey(), entry.getValue());
            }
        }
        builder.endObject();
        return builder;
    }

    /**
     * Adds tokens from another TokenUsage instance to this one.
     * Used for aggregating usage across multiple API calls.
     *
     * @param other The TokenUsage to add
     * @return A new TokenUsage with aggregated values
     */
    public TokenUsage addTokens(TokenUsage other) {
        if (other == null) {
            return this;
        }

        Map<String, Long> mergedAdditional = new HashMap<>(this.additionalUsage);
        if (other.additionalUsage != null) {
            other.additionalUsage.forEach((key, value) -> mergedAdditional.merge(key, value, Long::sum));
        }

        return TokenUsage
            .builder()
            .inputTokens(addNullable(this.inputTokens, other.inputTokens))
            .outputTokens(addNullable(this.outputTokens, other.outputTokens))
            .totalTokens(addNullable(this.totalTokens, other.totalTokens))
            .cacheReadInputTokens(addNullable(this.cacheReadInputTokens, other.cacheReadInputTokens))
            .cacheCreationInputTokens(addNullable(this.cacheCreationInputTokens, other.cacheCreationInputTokens))
            .reasoningTokens(addNullable(this.reasoningTokens, other.reasoningTokens))
            .additionalUsage(mergedAdditional)
            .build();
    }

    /**
     * Converts TokenUsage to a Map suitable for JSON serialization in agent responses
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        if (inputTokens != null) {
            map.put("input_tokens", inputTokens);
        }
        if (outputTokens != null) {
            map.put("output_tokens", outputTokens);
        }
        if (totalTokens != null) {
            map.put("total_tokens", totalTokens);
        }
        if (cacheReadInputTokens != null) {
            map.put("cache_read_input_tokens", cacheReadInputTokens);
        }
        if (cacheCreationInputTokens != null) {
            map.put("cache_creation_input_tokens", cacheCreationInputTokens);
        }
        if (reasoningTokens != null) {
            map.put("reasoning_tokens", reasoningTokens);
        }
        if (additionalUsage != null && !additionalUsage.isEmpty()) {
            map.putAll(additionalUsage);
        }
        return map;
    }

    /**
     * Computes total tokens if not provided by summing input and output tokens
     */
    public Long getEffectiveTotalTokens() {
        if (totalTokens != null) {
            return totalTokens;
        }
        if (inputTokens != null && outputTokens != null) {
            return inputTokens + outputTokens;
        }
        return null;
    }

    private static Long addNullable(Long a, Long b) {
        if (a == null && b == null) {
            return null;
        }
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a + b;
    }
}
