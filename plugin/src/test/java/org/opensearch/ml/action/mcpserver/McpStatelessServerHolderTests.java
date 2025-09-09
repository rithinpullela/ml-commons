/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.action.mcpserver;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.Before;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.opensearch.test.OpenSearchTestCase;

import io.modelcontextprotocol.server.McpStatelessAsyncServer;

public class McpStatelessServerHolderTests extends OpenSearchTestCase {

    @Mock
    private McpStatelessToolsHelper mcpStatelessToolsHelper;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        MockitoAnnotations.openMocks(this);
        McpStatelessServerHolder.init(mcpStatelessToolsHelper);
    }

    public void test_getMcpStatelessServerTransportProvider_multiThreading() {
        AtomicReference<OpenSearchMcpStatelessServerTransportProvider> providerAtomicReference = new AtomicReference<>();
        for (int i = 0; i < 10; i++) {
            new Thread(() -> {
                OpenSearchMcpStatelessServerTransportProvider provider = McpStatelessServerHolder.getMcpStatelessServerTransportProvider();
                providerAtomicReference.compareAndExchange(null, provider);
                assert providerAtomicReference.get() == provider;
            }).start();
        }
    }

    public void test_getMcpStatelessAsyncServerInstance() {
        McpStatelessAsyncServer server = McpStatelessServerHolder.getMcpStatelessAsyncServerInstance();
        assertNotNull(server);
    }

}