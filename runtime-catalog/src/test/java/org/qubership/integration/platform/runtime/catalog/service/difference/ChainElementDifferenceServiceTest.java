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

package org.qubership.integration.platform.runtime.catalog.service.difference;

import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.*;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.samePropertyValuesAs;

@ContextConfiguration(classes = ChainElementDifferenceService.class)
@ExtendWith(SpringExtension.class)
public class ChainElementDifferenceServiceTest {

    private static final String MOCKED_UUID = "8b56c2f7-52c3-45cf-99bf-70e4be32c8a0";

    @Autowired
    private ChainElementDifferenceService chainElementDifferenceService;

    private static Stream<Arguments> provideTestData() {
        return Stream.of(
                Arguments.of(
                        "All fields are different",
                        ChainElement.builder()
                                .id(MOCKED_UUID)
                                .type("http-trigger")
                                .name("Left HTTP Trigger")
                                .description("Left HTTP Trigger description")
                                .originalId("53a6b1a0-56f0-402c-82fa-fbc87c4ef8d0")
                                .properties(ImmutableMap.<String, Object>builder()
                                        .put("chunked", true)
                                        .put("contextPath", "v1/reuse/test")
                                        .put("httpBinding", "left/handlingHttpBinding")
                                        .put("privateRoute", false)
                                        .put("externalRoute", true)
                                        .put("handlerContainer", new HashMap<>())
                                        .put("accessControlType", "RBAC")
                                        .put("httpMethodRestrict", "GET")
                                        .put("allowedContentTypes", new ArrayList<String>())
                                        .put("handleValidationAction", "default")
                                        .put("rejectRequestIfNonNullBodyGetDelete", false)
                                        .build())
                                .build(),
                        ChainElement.builder()
                                .id(MOCKED_UUID)
                                .type("http-trigger")
                                .name("Right HTTP Trigger")
                                .description("Right HTTP Trigger description")
                                .originalId("53a6b1a0-56f0-402c-82fa-fbc87c4ef8d0")
                                .properties(ImmutableMap.<String, Object>builder()
                                        .put("chunked", false)
                                        .put("contextPath", "v2/reuse/test")
                                        .put("httpBinding", "right/handlingHttpBinding")
                                        .put("privateRoute", true)
                                        .put("externalRoute", false)
                                        .put("handlerContainer", Map.of("test", "test"))
                                        .put("accessControlType", "ABAC")
                                        .put("httpMethodRestrict", "POST")
                                        .put("allowedContentTypes", List.of("application/json"))
                                        .put("handleValidationAction", "non-default")
                                        .put("rejectRequestIfNonNullBodyGetDelete", true)
                                        .build())
                                .build(),
                        Collections.emptySet(),
                        Collections.emptySet(),
                        Set.of(
                                "name",
                                "description",
                                "properties.chunked",
                                "properties.contextPath",
                                "properties.httpBinding",
                                "properties.privateRoute",
                                "properties.externalRoute",
                                "properties.handlerContainer",
                                "properties.accessControlType",
                                "properties.httpMethodRestrict",
                                "properties.allowedContentTypes",
                                "properties.handleValidationAction",
                                "properties.rejectRequestIfNonNullBodyGetDelete"
                        )
                ),
                Arguments.of(
                        "Exclusive fields",
                        ChainElement.builder()
                                .id(MOCKED_UUID)
                                .type("http-trigger")
                                .name("HTTP Trigger")
                                .description("HTTP Trigger description")
                                .originalId("53a6b1a0-56f0-402c-82fa-fbc87c4ef8d0")
                                .properties(ImmutableMap.<String, Object>builder()
                                        .put("chunked", true)
                                        .put("accessControlType", "RBAC")
                                        .put("httpBinding", "left/handlingHttpBinding")
                                        //.put("contextPath", null)
                                        .build())
                                .build(),
                        ChainElement.builder()
                                .id(MOCKED_UUID)
                                .type("http-trigger")
                                .name("HTTP Trigger")
                                .description("HTTP Trigger description")
                                .originalId("53a6b1a0-56f0-402c-82fa-fbc87c4ef8d0")
                                .properties(Map.of(
                                        "httpMethodRestrict", "GET",
                                        "handleValidationAction", "default",
                                        "rejectRequestIfNonNullBodyGetDelete", false,
                                        "allowedContentTypes", new ArrayList<String>()
                                ))
                                .build(),
                        Set.of(
                                "properties.chunked",
                                "properties.accessControlType",
                                "properties.httpBinding"
                        ),
                        Set.of(
                                "properties.httpMethodRestrict",
                                "properties.handleValidationAction",
                                "properties.rejectRequestIfNonNullBodyGetDelete",
                                "properties.allowedContentTypes"
                        ),
                        Collections.emptySet()
                ),
                Arguments.of(
                        "All fields are equal",
                        ChainElement.builder()
                                .id(MOCKED_UUID)
                                .type("service-call")
                                .name("Service Call")
                                .description("Service Call description")
                                .originalId("85d7640f-6832-43d2-bc89-a6798a31fca4")
                                .properties(ImmutableMap.<String, Object>builder()
                                        .put("after", Collections.emptyList())
                                        .put("retryCount", 0)
                                        .put("retryDelay", 5000)
                                        .put("systemType", "INTERNAL")
                                        .put("errorThrowing", true)
                                        .put("afterValidation", Collections.emptyList())
                                        .put("propagateContext", true)
                                        .put("integrationSystemId", "om-order-lifecycle-manager-async")
                                        .put("integrationOperationId", "387310b4-c5f9-451c-91c6-99f39b8d24dc")
                                        .put("integrationOperationAsyncProperties", Map.of("maas.classifier.name", "task.spmDisconnectProfile.start"))
                                        .build())
                                .build(),
                        ChainElement.builder()
                                .id(MOCKED_UUID)
                                .type("service-call")
                                .name("Service Call")
                                .description("Service Call description")
                                .originalId("85d7640f-6832-43d2-bc89-a6798a31fca4")
                                .properties(ImmutableMap.<String, Object>builder()
                                        .put("after", Collections.emptyList())
                                        .put("retryCount", 0)
                                        .put("retryDelay", 5000)
                                        .put("systemType", "INTERNAL")
                                        .put("errorThrowing", true)
                                        .put("afterValidation", Collections.emptyList())
                                        .put("propagateContext", true)
                                        .put("integrationSystemId", "om-order-lifecycle-manager-async")
                                        .put("integrationOperationId", "387310b4-c5f9-451c-91c6-99f39b8d24dc")
                                        .put("integrationOperationAsyncProperties", Map.of("maas.classifier.name", "task.spmDisconnectProfile.start"))
                                        .build())
                                .build(),
                        Collections.emptySet(),
                        Collections.emptySet(),
                        Collections.emptySet()
                )
        );
    }

    @DisplayName("Finds differences between two chain elements")
    @ParameterizedTest(name = "#{index} => {0}")
    @MethodSource("provideTestData")
    public void findDifferencesTest(
            String scenario,
            ChainElement leftElement,
            ChainElement rightElement,
            Set<String> expectedOnlyOnLeft,
            Set<String> expectedOnlyOnRight,
            Set<String> expectedDiffering
    ) {
        DifferenceResult<ChainElement> differenceResult = chainElementDifferenceService.findDifferences(leftElement, rightElement);

        assertThat(differenceResult.getLeftOperand(), samePropertyValuesAs(leftElement));
        assertThat(differenceResult.getRightOperand(), samePropertyValuesAs(rightElement));
        assertThat(differenceResult.getOnlyOnLeft(), equalTo(expectedOnlyOnLeft));
        assertThat(differenceResult.getOnlyOnRight(), equalTo(expectedOnlyOnRight));
        assertThat(differenceResult.getDiffering(), equalTo(expectedDiffering));
    }
}
