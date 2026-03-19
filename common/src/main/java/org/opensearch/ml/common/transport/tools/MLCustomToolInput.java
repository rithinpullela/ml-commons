/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.common.transport.tools;

import static org.opensearch.core.xcontent.XContentParserUtils.ensureExpectedToken;
import static org.opensearch.ml.common.CommonValue.TENANT_ID_FIELD;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.opensearch.commons.authuser.User;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.common.io.stream.Writeable;
import org.opensearch.core.xcontent.ToXContentObject;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.ml.common.AccessMode;
import org.opensearch.ml.common.CommonValue;

import lombok.Builder;
import lombok.Data;
import lombok.Setter;

@Data
public class MLCustomToolInput implements ToXContentObject, Writeable {
    public static final String TOOL_NAME_FIELD = "name";
    public static final String TOOL_DESCRIPTION_FIELD = "description";
    public static final String TOOL_TYPE_FIELD = "type";
    public static final String SEARCH_TEMPLATE_NAME_FIELD = "search_template_name";
    public static final String INDEX_FIELD = "index";
    public static final String PARAMS_FIELD = "params";
    public static final String MODEL_ID_FIELD = "model_id";
    public static final String LLM_INTERFACE_FIELD = "llm_interface";
    public static final String BACKEND_ROLES_FIELD = CommonValue.BACKEND_ROLES_FIELD;
    public static final String ADD_ALL_BACKEND_ROLES_FIELD = "add_all_backend_roles";
    public static final String ACCESS_FIELD = "access";
    public static final String OWNER_FIELD = "owner";
    public static final String CREATE_TIME_FIELD = CommonValue.CREATED_TIME_FIELD;
    public static final String LAST_UPDATE_TIME_FIELD = CommonValue.LAST_UPDATED_TIME_FIELD;

    private String name;
    private String description;
    private String type;
    private String searchTemplateName;
    private String index;
    private Map<String, Object> params;
    private String modelId;
    private String llmInterface;
    @Setter
    private List<String> backendRoles;
    @Setter
    private Boolean addAllBackendRoles;
    @Setter
    private AccessMode access;
    @Setter
    private User owner;
    @Setter
    private String tenantId;
    private Instant createTime;
    private Instant lastUpdateTime;

    @Builder
    public MLCustomToolInput(
        String name,
        String description,
        String type,
        String searchTemplateName,
        String index,
        Map<String, Object> params,
        String modelId,
        String llmInterface,
        List<String> backendRoles,
        Boolean addAllBackendRoles,
        AccessMode access,
        User owner,
        String tenantId,
        Instant createTime,
        Instant lastUpdateTime,
        boolean isUpdateRequest
    ) {
        if (!isUpdateRequest) {
            if (name == null) {
                throw new IllegalArgumentException("Custom tool name is required");
            }
            if (description == null) {
                throw new IllegalArgumentException("Custom tool description is required");
            }
            if (type == null) {
                throw new IllegalArgumentException("Custom tool type is required");
            }
            if (!"search_template".equals(type)) {
                throw new IllegalArgumentException("Custom tool type must be 'search_template'");
            }
            if (searchTemplateName == null) {
                throw new IllegalArgumentException("Search template name is required");
            }
            if (params != null && modelId != null) {
                throw new IllegalArgumentException(
                    "Cannot specify both 'params' and 'model_id'. Use 'params' for manual parameter "
                        + "definitions or 'model_id' for LLM-assisted auto-generation, but not both."
                );
            }
        }
        this.name = name;
        this.description = description;
        this.type = type;
        this.searchTemplateName = searchTemplateName;
        this.index = index;
        this.params = params;
        this.modelId = modelId;
        this.llmInterface = llmInterface;
        this.backendRoles = backendRoles;
        this.addAllBackendRoles = addAllBackendRoles;
        this.access = access;
        this.owner = owner;
        this.tenantId = tenantId;
        this.createTime = createTime;
        this.lastUpdateTime = lastUpdateTime;
    }

    public MLCustomToolInput(StreamInput input) throws IOException {
        name = input.readOptionalString();
        description = input.readOptionalString();
        type = input.readOptionalString();
        searchTemplateName = input.readOptionalString();
        index = input.readOptionalString();
        if (input.readBoolean()) {
            params = input.readMap();
        }
        modelId = input.readOptionalString();
        llmInterface = input.readOptionalString();
        if (input.readBoolean()) {
            backendRoles = input.readList(StreamInput::readString);
        }
        addAllBackendRoles = input.readOptionalBoolean();
        if (input.readBoolean()) {
            access = input.readEnum(AccessMode.class);
        }
        if (input.readBoolean()) {
            owner = new User(input);
        }
        tenantId = input.readOptionalString();
        createTime = input.readOptionalInstant();
        lastUpdateTime = input.readOptionalInstant();
    }

    public static MLCustomToolInput parse(XContentParser parser) throws IOException {
        return parse(parser, false);
    }

    public static MLCustomToolInput parse(XContentParser parser, boolean isUpdateRequest) throws IOException {
        String name = null;
        String description = null;
        String type = null;
        String searchTemplateName = null;
        String index = null;
        Map<String, Object> params = null;
        String modelId = null;
        String llmInterface = null;
        List<String> backendRoles = null;
        Boolean addAllBackendRoles = null;
        AccessMode access = null;
        User owner = null;
        String tenantId = null;
        Instant createTime = null;
        Instant lastUpdateTime = null;

        ensureExpectedToken(XContentParser.Token.START_OBJECT, parser.currentToken(), parser);
        while (parser.nextToken() != XContentParser.Token.END_OBJECT) {
            String fieldName = parser.currentName();
            parser.nextToken();

            switch (fieldName) {
                case TOOL_NAME_FIELD:
                    name = parser.text();
                    break;
                case TOOL_DESCRIPTION_FIELD:
                    description = parser.text();
                    break;
                case TOOL_TYPE_FIELD:
                    type = parser.text();
                    break;
                case SEARCH_TEMPLATE_NAME_FIELD:
                    searchTemplateName = parser.text();
                    break;
                case INDEX_FIELD:
                    index = parser.text();
                    break;
                case PARAMS_FIELD:
                    params = parser.map();
                    break;
                case MODEL_ID_FIELD:
                    modelId = parser.text();
                    break;
                case LLM_INTERFACE_FIELD:
                    llmInterface = parser.text();
                    break;
                case BACKEND_ROLES_FIELD:
                    backendRoles = new ArrayList<>();
                    while (parser.nextToken() != XContentParser.Token.END_ARRAY) {
                        backendRoles.add(parser.text());
                    }
                    break;
                case ADD_ALL_BACKEND_ROLES_FIELD:
                    addAllBackendRoles = parser.booleanValue();
                    break;
                case ACCESS_FIELD:
                    access = AccessMode.from(parser.text());
                    break;
                case OWNER_FIELD:
                    owner = User.parse(parser);
                    break;
                case TENANT_ID_FIELD:
                    tenantId = parser.textOrNull();
                    break;
                case CREATE_TIME_FIELD:
                    createTime = Instant.ofEpochMilli(parser.longValue());
                    break;
                case LAST_UPDATE_TIME_FIELD:
                    lastUpdateTime = Instant.ofEpochMilli(parser.longValue());
                    break;
                default:
                    parser.skipChildren();
                    break;
            }
        }
        return MLCustomToolInput
            .builder()
            .name(name)
            .description(description)
            .type(type)
            .searchTemplateName(searchTemplateName)
            .index(index)
            .params(params)
            .modelId(modelId)
            .llmInterface(llmInterface)
            .backendRoles(backendRoles)
            .addAllBackendRoles(addAllBackendRoles)
            .access(access)
            .owner(owner)
            .tenantId(tenantId)
            .createTime(createTime)
            .lastUpdateTime(lastUpdateTime)
            .isUpdateRequest(isUpdateRequest)
            .build();
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        if (name != null) {
            builder.field(TOOL_NAME_FIELD, name);
        }
        if (description != null) {
            builder.field(TOOL_DESCRIPTION_FIELD, description);
        }
        if (type != null) {
            builder.field(TOOL_TYPE_FIELD, type);
        }
        if (searchTemplateName != null) {
            builder.field(SEARCH_TEMPLATE_NAME_FIELD, searchTemplateName);
        }
        if (index != null) {
            builder.field(INDEX_FIELD, index);
        }
        if (this.params != null) {
            builder.field(PARAMS_FIELD, this.params);
        }
        if (modelId != null) {
            builder.field(MODEL_ID_FIELD, modelId);
        }
        if (llmInterface != null) {
            builder.field(LLM_INTERFACE_FIELD, llmInterface);
        }
        if (backendRoles != null) {
            builder.field(BACKEND_ROLES_FIELD, backendRoles);
        }
        if (access != null) {
            builder.field(ACCESS_FIELD, access.getValue());
        }
        if (owner != null) {
            builder.field(OWNER_FIELD, owner);
        }
        if (tenantId != null) {
            builder.field(TENANT_ID_FIELD, tenantId);
        }
        if (createTime != null) {
            builder.field(CREATE_TIME_FIELD, createTime.toEpochMilli());
        }
        if (lastUpdateTime != null) {
            builder.field(LAST_UPDATE_TIME_FIELD, lastUpdateTime.toEpochMilli());
        }
        builder.endObject();
        return builder;
    }

    @Override
    public void writeTo(StreamOutput output) throws IOException {
        output.writeOptionalString(name);
        output.writeOptionalString(description);
        output.writeOptionalString(type);
        output.writeOptionalString(searchTemplateName);
        output.writeOptionalString(index);
        if (params != null) {
            output.writeBoolean(true);
            output.writeMap(params);
        } else {
            output.writeBoolean(false);
        }
        output.writeOptionalString(modelId);
        output.writeOptionalString(llmInterface);
        if (backendRoles != null) {
            output.writeBoolean(true);
            output.writeStringCollection(backendRoles);
        } else {
            output.writeBoolean(false);
        }
        output.writeOptionalBoolean(addAllBackendRoles);
        if (access != null) {
            output.writeBoolean(true);
            output.writeEnum(access);
        } else {
            output.writeBoolean(false);
        }
        if (owner != null) {
            output.writeBoolean(true);
            owner.writeTo(output);
        } else {
            output.writeBoolean(false);
        }
        output.writeOptionalString(tenantId);
        output.writeOptionalInstant(createTime);
        output.writeOptionalInstant(lastUpdateTime);
    }
}
