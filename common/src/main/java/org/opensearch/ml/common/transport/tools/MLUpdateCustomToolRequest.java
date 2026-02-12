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
public class MLUpdateCustomToolRequest extends ActionRequest {
    private String toolId;
    private MLCustomToolInput updateContent;
    private String tenantId;

    @Builder
    public MLUpdateCustomToolRequest(String toolId, MLCustomToolInput updateContent, String tenantId) {
        this.toolId = toolId;
        this.updateContent = updateContent;
        this.tenantId = tenantId;
    }

    public MLUpdateCustomToolRequest(StreamInput in) throws IOException {
        super(in);
        this.toolId = in.readString();
        this.updateContent = new MLCustomToolInput(in);
        this.tenantId = in.readOptionalString();
    }

    @Override
    public ActionRequestValidationException validate() {
        if (toolId == null) {
            return addValidationError("Custom tool ID can't be null", null);
        }
        return null;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeString(toolId);
        updateContent.writeTo(out);
        out.writeOptionalString(tenantId);
    }

    public static MLUpdateCustomToolRequest fromActionRequest(ActionRequest actionRequest) {
        if (actionRequest instanceof MLUpdateCustomToolRequest) {
            return (MLUpdateCustomToolRequest) actionRequest;
        }

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream(); OutputStreamStreamOutput osso = new OutputStreamStreamOutput(baos)) {
            actionRequest.writeTo(osso);
            try (StreamInput input = new InputStreamStreamInput(new ByteArrayInputStream(baos.toByteArray()))) {
                return new MLUpdateCustomToolRequest(input);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse ActionRequest into MLUpdateCustomToolRequest", e);
        }
    }
}
