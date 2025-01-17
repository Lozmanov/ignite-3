/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.cli.ssl;

import static org.apache.ignite.internal.NodeConfig.restSslBootstrapConfig;
import static org.junit.jupiter.api.Assertions.assertAll;

import jakarta.inject.Inject;
import org.apache.ignite.internal.NodeConfig;
import org.apache.ignite.internal.cli.call.connect.ConnectCall;
import org.apache.ignite.internal.cli.call.connect.ConnectCallInput;
import org.apache.ignite.internal.cli.commands.CliCommandTestNotInitializedIntegrationBase;
import org.apache.ignite.internal.cli.config.CliConfigKeys;
import org.apache.ignite.internal.cli.core.flow.builder.Flows;
import org.junit.jupiter.api.Test;

/** Tests for REST SSL. */
public class ItSslCustomCipherTest extends CliCommandTestNotInitializedIntegrationBase {
    private static final String CIPHER1 = "TLS_AES_256_GCM_SHA384";
    private static final String CIPHER2 = "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384";

    @Inject
    ConnectCall connectCall;

    /** Mimics non-REPL "connect" command without starting REPL mode. Overriding getCommandClass and returning TopLevelCliReplCommand
     * wouldn't help because it will start to ask questions.
     */
    private void connect(String url) {
        Flows.from(ConnectCallInput.builder().url(url).build())
                .then(Flows.fromCall(connectCall))
                .print()
                .start();
    }

    @Override
    protected String nodeBootstrapConfigTemplate() {
        return restSslBootstrapConfig(CIPHER1);
    }

    @Test
    void compatibleCiphers() {
        // When REST SSL is enabled
        configManagerProvider.configManager.setProperty(CliConfigKeys.REST_TRUST_STORE_PATH.value(), NodeConfig.resolvedTruststorePath);
        configManagerProvider.configManager.setProperty(CliConfigKeys.REST_TRUST_STORE_PASSWORD.value(), NodeConfig.trustStorePassword);
        configManagerProvider.configManager.setProperty(CliConfigKeys.REST_KEY_STORE_PATH.value(), NodeConfig.resolvedKeystorePath);
        configManagerProvider.configManager.setProperty(CliConfigKeys.REST_KEY_STORE_PASSWORD.value(), NodeConfig.keyStorePassword);

        // And explicitly set the same cipher as for the node
        configManagerProvider.configManager.setProperty(CliConfigKeys.REST_CIPHERS.value(), CIPHER1);

        // And connect via HTTPS
        connect("https://localhost:10400");

        // Then
        assertAll(
                this::assertErrOutputIsEmpty,
                () -> assertOutputContains("Connected to https://localhost:10400")
        );
    }

    @Test
    void incompatibleCiphers() {
        // When REST SSL is enabled
        configManagerProvider.configManager.setProperty(CliConfigKeys.REST_TRUST_STORE_PATH.value(), NodeConfig.resolvedTruststorePath);
        configManagerProvider.configManager.setProperty(CliConfigKeys.REST_TRUST_STORE_PASSWORD.value(), NodeConfig.trustStorePassword);
        configManagerProvider.configManager.setProperty(CliConfigKeys.REST_KEY_STORE_PATH.value(), NodeConfig.resolvedKeystorePath);
        configManagerProvider.configManager.setProperty(CliConfigKeys.REST_KEY_STORE_PASSWORD.value(), NodeConfig.keyStorePassword);

        // And explicitly set cipher different from the node
        configManagerProvider.configManager.setProperty(CliConfigKeys.REST_CIPHERS.value(), CIPHER2);

        // And connect via HTTPS
        connect("https://localhost:10400");

        // Then
        assertAll(
                () -> assertErrOutputContains("SSL error"),
                this::assertOutputIsEmpty
        );
    }
}
