/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.common.transport.tools;

import org.opensearch.action.ActionType;
import org.opensearch.action.delete.DeleteResponse;

public class MLDeleteCustomToolAction extends ActionType<DeleteResponse> {
    public static MLDeleteCustomToolAction INSTANCE = new MLDeleteCustomToolAction();
    public static final String NAME = "cluster:admin/opensearch/ml/tools/delete";

    private MLDeleteCustomToolAction() {
        super(NAME, DeleteResponse::new);
    }
}
