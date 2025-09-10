/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.rest.mcpserver;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.opensearch.OpenSearchException;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.ml.common.settings.MLFeatureEnabledSetting;
import org.opensearch.rest.RestRequest;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.test.rest.FakeRestRequest;
import org.opensearch.transport.client.node.NodeClient;

public class RestMCPStatelessStreamingActionTests extends OpenSearchTestCase {

    private RestMCPStatelessStreamingAction restMCPStatelessStreamingAction;

    @Rule
    public ExpectedException exceptionRule = ExpectedException.none();

    @Mock
    private MLFeatureEnabledSetting mlFeatureEnabledSetting;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        MockitoAnnotations.openMocks(this);
        restMCPStatelessStreamingAction = new RestMCPStatelessStreamingAction(mlFeatureEnabledSetting);
        when(mlFeatureEnabledSetting.isMcpServerEnabled()).thenReturn(true);
    }

    @Test
    public void test_prepareRequest_withRestRequest_successful() throws Exception {
        RestRequest request = new FakeRestRequest.Builder(NamedXContentRegistry.EMPTY)
            .withMethod(RestRequest.Method.POST)
            .withPath(RestMCPStatelessStreamingAction.STATELESS_ENDPOINT)
            .build();
        restMCPStatelessStreamingAction.prepareRequest(request, mock(NodeClient.class));
    }

    @Test
    public void test_prepareRequest_featureFlagDisabled() throws Exception {
        exceptionRule.expect(OpenSearchException.class);
        exceptionRule
            .expectMessage("The MCP server is not enabled. To enable, please update the setting plugins.ml_commons.mcp_server_enabled");
        when(mlFeatureEnabledSetting.isMcpServerEnabled()).thenReturn(false);
        RestMCPStatelessStreamingAction messageStreamingAction = new RestMCPStatelessStreamingAction(mlFeatureEnabledSetting);
        RestRequest request = new FakeRestRequest.Builder(NamedXContentRegistry.EMPTY)
            .withMethod(RestRequest.Method.POST)
            .withPath(RestMCPStatelessStreamingAction.STATELESS_ENDPOINT)
            .build();
        messageStreamingAction.prepareRequest(request, mock(NodeClient.class));
    }

    @Test
    public void test_routes() {
        assertTrue(restMCPStatelessStreamingAction.routes().stream().anyMatch(route -> route.getMethod() == RestRequest.Method.POST));
        assertEquals(1, restMCPStatelessStreamingAction.routes().size());
    }

    @Test
    public void test_getName() {
        assertEquals("ml_stateless_mcp_action", restMCPStatelessStreamingAction.getName());
    }

    @Test
    public void test_statelessEndpoint() {
        assertEquals("/_plugins/_ml/mcp/stream", RestMCPStatelessStreamingAction.STATELESS_ENDPOINT);
    }
}
