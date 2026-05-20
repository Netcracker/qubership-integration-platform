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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SnapshotCreationException;
import org.qubership.integration.platform.runtime.catalog.model.system.ServiceEnvironment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.testutils.TestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.UUID;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

@ExtendWith(SpringExtension.class)
@ExtendWith(MockitoExtension.class)
@ContextConfiguration(classes = EndpointHelperSource.class)
@TestPropertySource(properties = {
        "qip.gateway.egress.protocol=http",
        "qip.gateway.egress.url=egress-gateway:8080"
})
public class EndpointHelperSourceTest {

    private static final String ENDPOINT = "http://localhost:8080/api/v1/test";
    private static final UUID UUID_VALUE = UUID.fromString("2630f405-e260-4f22-b47e-2ef0b1cd12c4");
    private static final String DIFFERENT_ID_VALUE = "7658d306-8109-4006-be9f-b5016fbf138c";

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

    @DisplayName("Get gateway URI")
    @Test
    public void gatewayURITest() {
        String expected = "egress-gateway:8080/loop-2/" + UUID_VALUE;

        ChainElement element = new ChainElement();
        element.setType("loop-2");

        String actual = String.valueOf(endpointHelperSource.gatewayURI(element));

        assertThat(actual, equalTo(expected));
    }

    private static Stream<Arguments> integrationAddressTestData() {
        return Stream.of(
                Arguments.of(
                        "External service address",
                        """
                           {
                                "systemType": "EXTERNAL"
                           }
                        """,
                        """
                           {
                                "address": "https://localhost:8080"
                           }
                        """,
                        "http://egress-gateway:8080/system/8ff3584f-3666-4c75-ba40-a3c8955c44e8/d7ac2b8c44ec95567c89824823e34d3973e809a9"
                ),
                Arguments.of(
                        "Internal service address without protocol",
                        """
                           {
                             "systemType": "INTERNAL"
                           }
                        """,
                        """
                           {
                             "address": "om-order-lifecycle-manager-v1:8080"
                           }
                        """,
                        "om-order-lifecycle-manager-v1:8080"
                ),
                Arguments.of(
                        "Internal service address with protocol",
                        """
                           {
                             "systemType": "INTERNAL"
                           }
                        """,
                        """
                           {
                             "address": "https://om-order-lifecycle-manager-v1:8080"
                           }
                        """,
                        "https://om-order-lifecycle-manager-v1:8080"
                ),
                Arguments.of(
                        "Implemented service address with environment",
                        """
                           {
                             "systemType": "IMPLEMENTED"
                           }
                        """,
                        """
                           {
                             "address": "/api/v1"
                           }
                        """,
                        "/api/v1"
                ),
                Arguments.of(
                        "Implemented service address without environment",
                        """
                           {
                              "systemType": "IMPLEMENTED"
                           }
                        """,
                        null,
                        StringUtils.EMPTY
                )
        );
    }

    @DisplayName("Composing integration address")
    @ParameterizedTest(name = "#{index} => {0}")
    @MethodSource("integrationAddressTestData")
    public void integrationAddressTest(
            String scenario,
            String elementProperties,
            String environment,
            String expectedAddress
    ) throws JsonProcessingException {
        ChainElement element = new ChainElement();
        element.setOriginalId("8ff3584f-3666-4c75-ba40-a3c8955c44e8");
        if (elementProperties != null) {
            element.setProperties(TestUtils.OBJECT_MAPPER.readValue(elementProperties, new TypeReference<>() {}));
        }
        if (environment != null) {
            element.setEnvironment(TestUtils.OBJECT_MAPPER.readValue(environment, ServiceEnvironment.class));
        }

        String actual = String.valueOf(endpointHelperSource.integrationAddress(element));

        assertThat(actual, equalTo(expectedAddress));
    }

    private static Stream<Arguments> integrationAddressErrorTestData() {
        return Stream.of(
                Arguments.of(
                        "No environment for external service",
                        """
                           {
                              "systemType": "EXTERNAL"
                           }
                        """,
                        null
                ),
                Arguments.of(
                        "No environment for internal service",
                        """
                           {
                              "systemType": "INTERNAL"
                           }
                        """,
                        null
                ),
                Arguments.of(
                        "Blank address for internal service",
                        """
                           {
                              "systemType": "INTERNAL"
                           }
                        """,
                        """
                           {
                              "address": "   "
                           }
                        """
                )
        );
    }

    @DisplayName("Error case of composing integration address")
    @ParameterizedTest(name = "#{index} => {0}")
    @MethodSource("integrationAddressErrorTestData")
    public void integrationAddressErrorTest(
            String scenario,
            String elementProperties,
            String environment
    ) throws JsonProcessingException {
        ChainElement element = new ChainElement();
        if (elementProperties != null) {
            element.setProperties(TestUtils.OBJECT_MAPPER.readValue(elementProperties, new TypeReference<>() {}));
        }
        if (environment != null) {
            element.setEnvironment(TestUtils.OBJECT_MAPPER.readValue(environment, ServiceEnvironment.class));
        }

        assertThrows(SnapshotCreationException.class, () -> endpointHelperSource.integrationAddress(element));
    }

    @DisplayName("Extracting integration endpoint")
    @Test
    public void integrationEndpointTest() {
        ChainElement element = new ChainElement();
        ServiceEnvironment environment = new ServiceEnvironment();
        environment.setAddress(ENDPOINT);
        element.setEnvironment(environment);

        String actual = String.valueOf(endpointHelperSource.integrationEndpoint(element));

        assertThat(actual, equalTo(ENDPOINT));
    }

    private static Stream<Arguments> integrationEndpointErrorTestData() {
        return Stream.of(
                Arguments.of(
                        "Empty environment",
                        null
                ),
                Arguments.of(
                        "Not activated",
                        """
                           {
                              "notActivated": true
                           }
                        """
                ),
                Arguments.of(
                        "Blank endpoint",
                        """
                           {
                              "address": "   "
                           }
                        """
                )
        );
    }

    @DisplayName("Error case of extracting integration endpoint")
    @ParameterizedTest(name = "#{index} => {0}")
    @MethodSource("integrationEndpointErrorTestData")
    public void integrationEndpointErrorTest(String scenario, String environment) throws JsonProcessingException {
        ChainElement element = new ChainElement();
        if (environment != null) {
            element.setEnvironment(TestUtils.OBJECT_MAPPER.readValue(environment, ServiceEnvironment.class));
        }

        assertThrows(SnapshotCreationException.class, () -> endpointHelperSource.integrationEndpoint(element));
    }

    @DisplayName("Extracting route variable for external service with egress route registration")
    @Test
    public void externalRoutePathTestAuto() {
        ChainElement element = new ChainElement();
        element.setOriginalId(DIFFERENT_ID_VALUE);

        String actual = String.valueOf(endpointHelperSource.externalRoutePath(element));

        assertThat(actual, equalTo("%%{route-" + DIFFERENT_ID_VALUE + "}"));
    }

    @DisplayName("Extracting route variable for external service without egress route registration")
    @Test
    public void externalRoutePathTestManual() {
        ChainElement element = new ChainElement();
        element.setOriginalId(DIFFERENT_ID_VALUE);

        String actual = String.valueOf(endpointHelperSource.externalRoutePath(element));

        assertThat(actual, equalTo("%%{route-" + DIFFERENT_ID_VALUE + "}"));
    }
}
