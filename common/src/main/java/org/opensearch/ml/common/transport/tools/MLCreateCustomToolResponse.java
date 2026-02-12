/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.common.transport.tools;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

import org.opensearch.core.action.ActionResponse;
import org.opensearch.core.common.io.stream.InputStreamStreamInput;
import org.opensearch.core.common.io.stream.OutputStreamStreamOutput;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.xcontent.ToXContentObject;
import org.opensearch.core.xcontent.XContentBuilder;

import lombok.Getter;

@Getter
public class MLCreateCustomToolResponse extends ActionResponse implements ToXContentObject {
    public static final String TOOL_ID_FIELD = "tool_id";

    private String toolId;

    public MLCreateCustomToolResponse(StreamInput in) throws IOException {
        super(in);
        this.toolId = in.readString();
    }

    public MLCreateCustomToolResponse(String toolId) {
        this.toolId = toolId;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeString(toolId);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.field(TOOL_ID_FIELD, toolId);
        builder.endObject();
        return builder;
    }

    public static MLCreateCustomToolResponse fromActionResponse(ActionResponse actionResponse) {
        if (actionResponse instanceof MLCreateCustomToolResponse) {
            return (MLCreateCustomToolResponse) actionResponse;
        }

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream(); OutputStreamStreamOutput osso = new OutputStreamStreamOutput(baos)) {
            actionResponse.writeTo(osso);
            try (StreamInput input = new InputStreamStreamInput(new ByteArrayInputStream(baos.toByteArray()))) {
                return new MLCreateCustomToolResponse(input);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse ActionResponse into MLCreateCustomToolResponse", e);
        }
    }
}
