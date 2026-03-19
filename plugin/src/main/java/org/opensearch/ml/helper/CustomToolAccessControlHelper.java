/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.helper;

import static org.opensearch.ml.common.CommonValue.BACKEND_ROLES_FIELD;
import static org.opensearch.ml.common.settings.MLCommonsSettings.ML_COMMONS_CUSTOM_TOOL_ACCESS_CONTROL_ENABLED;
import static org.opensearch.ml.common.transport.tools.MLCustomToolInput.ACCESS_FIELD;
import static org.opensearch.ml.common.transport.tools.MLCustomToolInput.OWNER_FIELD;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.apache.lucene.search.join.ScoreMode;
import org.opensearch.OpenSearchStatusException;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.settings.Settings;
import org.opensearch.commons.authuser.User;
import org.opensearch.core.common.util.CollectionUtils;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.index.query.BoolQueryBuilder;
import org.opensearch.index.query.NestedQueryBuilder;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.index.query.TermQueryBuilder;
import org.opensearch.ml.common.AccessMode;
import org.opensearch.ml.common.transport.tools.MLCustomToolInput;
import org.opensearch.search.builder.SearchSourceBuilder;

import lombok.extern.log4j.Log4j2;

@Log4j2
public class CustomToolAccessControlHelper {

    private volatile Boolean customToolAccessControlEnabled;

    public CustomToolAccessControlHelper(ClusterService clusterService, Settings settings) {
        customToolAccessControlEnabled = ML_COMMONS_CUSTOM_TOOL_ACCESS_CONTROL_ENABLED.get(settings);
        clusterService
            .getClusterSettings()
            .addSettingsUpdateConsumer(ML_COMMONS_CUSTOM_TOOL_ACCESS_CONTROL_ENABLED, it -> customToolAccessControlEnabled = it);
    }

    public boolean accessControlNotEnabled(User user) {
        return user == null || !customToolAccessControlEnabled;
    }

    public static boolean isAdmin(User user) {
        if (user == null) {
            return false;
        }
        if (CollectionUtils.isEmpty(user.getRoles())) {
            return false;
        }
        return user.getRoles().contains("all_access");
    }

    public boolean skipAccessControl(User user) {
        return user == null || !customToolAccessControlEnabled || isAdmin(user);
    }

    /**
     * Check if a user has permission to access a tool based on its stored source map.
     */
    public boolean hasPermission(User user, Map<String, Object> toolSource) {
        if (accessControlNotEnabled(user) || isAdmin(user)) {
            return true;
        }

        // Tools created before RBAC was enabled have no owner — treat as public
        Object ownerObj = toolSource.get(OWNER_FIELD);
        if (ownerObj == null) {
            return true;
        }

        String accessStr = (String) toolSource.get(ACCESS_FIELD);
        AccessMode accessMode = accessStr != null ? AccessMode.from(accessStr) : null;

        if (AccessMode.PUBLIC == accessMode) {
            return true;
        }

        // Extract owner name from nested object
        String ownerName = null;
        if (ownerObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> ownerMap = (Map<String, Object>) ownerObj;
            ownerName = (String) ownerMap.get("name");
        }

        if (AccessMode.PRIVATE == accessMode) {
            return user.getName() != null && user.getName().equals(ownerName);
        }

        if (AccessMode.RESTRICTED == accessMode) {
            @SuppressWarnings("unchecked")
            List<String> toolBackendRoles = (List<String>) toolSource.get(BACKEND_ROLES_FIELD);
            return user.getBackendRoles() != null
                && toolBackendRoles != null
                && toolBackendRoles.stream().anyMatch(role -> user.getBackendRoles().contains(role));
        }

        return false;
    }

    /**
     * Validate access control parameters during tool creation.
     */
    public void validateCreateRequest(MLCustomToolInput input, User user) {
        Boolean isAddAllBackendRoles = input.getAddAllBackendRoles();
        if (isAdmin(user)) {
            if (Boolean.TRUE.equals(isAddAllBackendRoles)) {
                throw new IllegalArgumentException("Admin can't add all backend roles");
            }
        }
        AccessMode accessMode = input.getAccess();
        if (accessMode == null) {
            if (!CollectionUtils.isEmpty(input.getBackendRoles()) || Boolean.TRUE.equals(isAddAllBackendRoles)) {
                input.setAccess(AccessMode.RESTRICTED);
                accessMode = AccessMode.RESTRICTED;
            } else {
                input.setAccess(AccessMode.PRIVATE);
                accessMode = AccessMode.PRIVATE;
            }
        }
        if (AccessMode.PUBLIC == accessMode || AccessMode.PRIVATE == accessMode) {
            if (!CollectionUtils.isEmpty(input.getBackendRoles()) || Boolean.TRUE.equals(isAddAllBackendRoles)) {
                throw new IllegalArgumentException("You can specify backend roles only for a tool with the restricted access mode.");
            }
        }
        if (AccessMode.RESTRICTED == accessMode) {
            if (Boolean.TRUE.equals(isAddAllBackendRoles)) {
                if (!CollectionUtils.isEmpty(input.getBackendRoles())) {
                    throw new IllegalArgumentException("You can't specify backend roles and add all backend roles to true at same time.");
                }
                if (CollectionUtils.isEmpty(user.getBackendRoles())) {
                    throw new IllegalArgumentException("You must have at least one backend role to create a restricted tool.");
                }
            } else {
                if (CollectionUtils.isEmpty(input.getBackendRoles())) {
                    throw new IllegalArgumentException(
                        "You must specify at least one backend role or make the tool public/private for registering it."
                    );
                } else if (!isAdmin(user) && !new HashSet<>(user.getBackendRoles()).containsAll(input.getBackendRoles())) {
                    throw new IllegalArgumentException("You don't have the backend roles specified.");
                }
            }
        }
    }

    /**
     * Validate that no access control parameters are specified when security/access control is disabled.
     */
    public void validateSecurityDisabledRequest(MLCustomToolInput input) {
        if (input.getAccess() != null || input.getAddAllBackendRoles() != null || !CollectionUtils.isEmpty(input.getBackendRoles())) {
            throw new IllegalArgumentException(
                "You cannot specify access control parameters because the Security plugin or custom tool access control is disabled on your cluster."
            );
        }
    }

    /**
     * Add backend role filtering to search queries so users only see tools they can access.
     */
    public SearchSourceBuilder addUserBackendRolesFilter(User user, SearchSourceBuilder searchSourceBuilder) {
        BoolQueryBuilder boolQueryBuilder = new BoolQueryBuilder();
        boolQueryBuilder.should(QueryBuilders.termQuery(ACCESS_FIELD, AccessMode.PUBLIC.getValue()));
        boolQueryBuilder.should(QueryBuilders.termsQuery(BACKEND_ROLES_FIELD + ".keyword", user.getBackendRoles()));

        BoolQueryBuilder privateBoolQuery = new BoolQueryBuilder();
        String ownerName = OWNER_FIELD + ".name.keyword";
        TermQueryBuilder ownerNameTermQuery = QueryBuilders.termQuery(ownerName, user.getName());
        NestedQueryBuilder nestedQueryBuilder = new NestedQueryBuilder(OWNER_FIELD, ownerNameTermQuery, ScoreMode.None);
        privateBoolQuery.must(nestedQueryBuilder);
        privateBoolQuery.must(QueryBuilders.termQuery(ACCESS_FIELD, AccessMode.PRIVATE.getValue()));
        boolQueryBuilder.should(privateBoolQuery);

        // Also include tools with no owner (created before RBAC was enabled)
        // owner is a nested field, so we must use a nested query for the exists check
        boolQueryBuilder
            .should(
                QueryBuilders
                    .boolQuery()
                    .mustNot(new NestedQueryBuilder(OWNER_FIELD, QueryBuilders.existsQuery(OWNER_FIELD + ".name"), ScoreMode.None))
            );

        QueryBuilder query = searchSourceBuilder.query();
        if (query == null) {
            searchSourceBuilder.query(boolQueryBuilder);
        } else if (query instanceof BoolQueryBuilder) {
            ((BoolQueryBuilder) query).filter(boolQueryBuilder);
        } else {
            BoolQueryBuilder rewriteQuery = new BoolQueryBuilder();
            rewriteQuery.must(query);
            rewriteQuery.filter(boolQueryBuilder);
            searchSourceBuilder.query(rewriteQuery);
        }
        return searchSourceBuilder;
    }

    /**
     * Throw a 403 if the user doesn't have permission.
     */
    public void validateToolAccess(User user, Map<String, Object> toolSource) {
        if (!hasPermission(user, toolSource)) {
            throw new OpenSearchStatusException("You don't have permission to access this tool", RestStatus.FORBIDDEN);
        }
    }
}
