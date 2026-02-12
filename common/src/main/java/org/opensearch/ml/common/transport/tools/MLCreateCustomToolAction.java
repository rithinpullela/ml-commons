/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.common.transport.tools;

import org.opensearch.action.ActionType;

public class MLCreateCustomToolAction extends ActionType<MLCreateCustomToolResponse> {
    public static MLCreateCustomToolAction INSTANCE = new MLCreateCustomToolAction();
    public static final String NAME = "cluster:admin/opensearch/ml/tools/create";

    private MLCreateCustomToolAction() {
        super(NAME, MLCreateCustomToolResponse::new);
    }
}
