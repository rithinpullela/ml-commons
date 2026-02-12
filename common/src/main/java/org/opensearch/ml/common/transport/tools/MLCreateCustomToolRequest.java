/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.common.transport.tools;

import static org.opensearch.action.ValidateActions.addValidationError;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

import org.opensearch.action.ActionRequest;
import org.opensearch.action.ActionRequestValidationException;
import org.opensearch.core.common.io.stream.InputStreamStreamInput;
import org.opensearch.core.common.io.stream.OutputStreamStreamOutput;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;

import lombok.Builder;
import lombok.Getter;

@Getter
public class MLCreateCustomToolRequest extends ActionRequest {
    private MLCustomToolInput mlCustomToolInput;

    @Builder
    public MLCreateCustomToolRequest(MLCustomToolInput mlCustomToolInput) {
        this.mlCustomToolInput = mlCustomToolInput;
    }

    public MLCreateCustomToolRequest(StreamInput in) throws IOException {
        super(in);
        this.mlCustomToolInput = new MLCustomToolInput(in);
    }

    @Override
    public ActionRequestValidationException validate() {
        if (mlCustomToolInput == null) {
            return addValidationError("Custom tool input can't be null", null);
        }
        return null;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        this.mlCustomToolInput.writeTo(out);
    }

    public static MLCreateCustomToolRequest fromActionRequest(ActionRequest actionRequest) {
        if (actionRequest instanceof MLCreateCustomToolRequest) {
            return (MLCreateCustomToolRequest) actionRequest;
        }

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream(); OutputStreamStreamOutput osso = new OutputStreamStreamOutput(baos)) {
            actionRequest.writeTo(osso);
            try (StreamInput input = new InputStreamStreamInput(new ByteArrayInputStream(baos.toByteArray()))) {
                return new MLCreateCustomToolRequest(input);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse ActionRequest into MLCreateCustomToolRequest", e);
        }
    }
}
