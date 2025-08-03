/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.common.transport.memorycontainer.memory;

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

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.FieldDefaults;

@Getter
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
@ToString
public class MLMemoryGetRequest extends ActionRequest {

    String memoryContainerId;
    String memoryId;
    String tenantId;

    @Builder
    public MLMemoryGetRequest(String memoryContainerId, String memoryId, String tenantId) {
        this.memoryContainerId = memoryContainerId;
        this.memoryId = memoryId;
        this.tenantId = tenantId;
    }

    public MLMemoryGetRequest(StreamInput in) throws IOException {
        super(in);
        this.memoryContainerId = in.readString();
        this.memoryId = in.readString();
        this.tenantId = in.readOptionalString();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeString(this.memoryContainerId);
        out.writeString(this.memoryId);
        out.writeOptionalString(tenantId);
    }

    @Override
    public ActionRequestValidationException validate() {
        ActionRequestValidationException exception = null;

        if (this.memoryContainerId == null || this.memoryId == null) {
            exception = addValidationError("Memory container and memoryId id can not be null", exception);
        }

        return exception;
    }

    public static MLMemoryGetRequest fromActionRequest(ActionRequest actionRequest) {
        if (actionRequest instanceof MLMemoryGetRequest) {
            return (MLMemoryGetRequest) actionRequest;
        }

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream(); OutputStreamStreamOutput osso = new OutputStreamStreamOutput(baos)) {
            actionRequest.writeTo(osso);
            try (StreamInput input = new InputStreamStreamInput(new ByteArrayInputStream(baos.toByteArray()))) {
                return new MLMemoryGetRequest(input);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to parse ActionRequest into MLMemoryGetRequest", e);
        }
    }
}
