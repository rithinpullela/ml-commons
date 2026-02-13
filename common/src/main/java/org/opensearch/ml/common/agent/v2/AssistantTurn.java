/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.common.agent.v2;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * An assistant turn containing the raw provider-specific message.
 */
@Data
@AllArgsConstructor
public class AssistantTurn implements InteractionTurn {
    private Map<String, ?> rawMessage;
}
