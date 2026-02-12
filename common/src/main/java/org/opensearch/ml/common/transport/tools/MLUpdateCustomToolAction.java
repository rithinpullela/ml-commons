/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.common.transport.tools;

import org.opensearch.action.ActionType;
import org.opensearch.action.update.UpdateResponse;

public class MLUpdateCustomToolAction extends ActionType<UpdateResponse> {
    public static MLUpdateCustomToolAction INSTANCE = new MLUpdateCustomToolAction();
    public static final String NAME = "cluster:admin/opensearch/ml/tools/update";

    private MLUpdateCustomToolAction() {
        super(NAME, UpdateResponse::new);
    }
}
