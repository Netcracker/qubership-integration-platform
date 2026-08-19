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

package org.qubership.integration.platform.runtime.catalog.service.deployment.properties.builders;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.library.constants.CamelNames;
import org.qubership.integration.platform.library.constants.ConfigurationPropertiesConstants;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers {@link ServiceCallPropertiesBuilder}: it applies only to the service-call component and
 * copies the retry-count and retry-delay properties from the element.
 */
@ExtendWith(MockitoExtension.class)
class ServiceCallPropertiesBuilderTest {

    private final ServiceCallPropertiesBuilder builder = new ServiceCallPropertiesBuilder();

    @Test
    void applicableToAcceptsServiceCallAndRejectsOthers() {
        Element serviceCall = mock(Element.class);
        when(serviceCall.getType()).thenReturn(CamelNames.SERVICE_CALL_COMPONENT);
        Element httpSender = mock(Element.class);
        when(httpSender.getType()).thenReturn("http-sender");

        assertThat(builder.applicableTo(serviceCall)).isTrue();
        assertThat(builder.applicableTo(httpSender)).isFalse();
    }

    @Test
    void buildCopiesTheRetryCountAndDelayFromTheElement() {
        Element element = mock(Element.class);
        Map<String, Object> properties = new HashMap<>();
        properties.put(ConfigurationPropertiesConstants.SERVICE_CALL_RETRY_COUNT, "3");
        properties.put(ConfigurationPropertiesConstants.SERVICE_CALL_RETRY_DELAY, "500");
        when(element.getProperties()).thenReturn(properties);

        assertThat(builder.build(element))
                .containsEntry(ConfigurationPropertiesConstants.SERVICE_CALL_RETRY_COUNT, "3")
                .containsEntry(ConfigurationPropertiesConstants.SERVICE_CALL_RETRY_DELAY, "500");
    }
}
