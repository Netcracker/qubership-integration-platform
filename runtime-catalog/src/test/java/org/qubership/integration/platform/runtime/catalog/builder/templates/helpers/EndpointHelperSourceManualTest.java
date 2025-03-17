/*
 * Copyright 2024-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.integration.platform.runtime.catalog.builder.templates.helpers;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.catalog.model.constant.CamelOptions;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

@ExtendWith(SpringExtension.class)
@ExtendWith(MockitoExtension.class)
@ContextConfiguration(classes = EndpointHelperSource.class)
@TestPropertySource(properties = {
        "qip.gateway.egress.protocol=http",
        "qip.gateway.egress.url=egress-gateway:8080",
        "qip.control-plane.chain-routes-registration.egress-gateway=false"
})
public class EndpointHelperSourceManualTest {

    private static final UUID UUID_VALUE = UUID.fromString("2630f405-e260-4f22-b47e-2ef0b1cd12c4");
    private static final String DIFFERENT_ID_VALUE = "7658d306-8109-4006-be9f-b5016fbf138c";
    private static final String SECOND_DIFFERENT_ID_VALUE = "9f461518-8bfc-468f-9fb5-2490692af16d";

    private static MockedStatic<UUID> mockedUUID;
    @Autowired
    private EndpointHelperSource endpointHelperSource;


    @BeforeAll
    public static void initializeBeforeAll() {
        mockedUUID = mockStatic(UUID.class, CALLS_REAL_METHODS);
        mockedUUID.when(UUID::randomUUID).thenReturn(UUID_VALUE);
    }

    @AfterAll
    public static void finalizeAfterAll() {
        mockedUUID.close();
    }

    @DisplayName("Extracting route variable for external service without egress route registration")
    @Test
    public void externalRoutePathTestManual() {
        ChainElement httpSenderElement = new ChainElement();
        httpSenderElement.setType("http-sender");
        httpSenderElement.setOriginalId(DIFFERENT_ID_VALUE);

        ChainElement serviceCallElement = new ChainElement();
        serviceCallElement.setType("service-call");
        serviceCallElement.setProperties(Map.of(CamelOptions.SYSTEM_ID, SECOND_DIFFERENT_ID_VALUE));

        String actualHttpSender = String.valueOf(endpointHelperSource.externalRoutePath(httpSenderElement));
        String actualServiceCall = String.valueOf(endpointHelperSource.externalRoutePath(serviceCallElement));

        assertThat(actualHttpSender, equalTo("/qip/" + DIFFERENT_ID_VALUE));
        assertThat(actualServiceCall, equalTo("/qip/" + SECOND_DIFFERENT_ID_VALUE));
    }
}
