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

package org.qubership.integration.platform.runtime.catalog.service.deployment.properties;

import com.netcracker.cloud.dbaas.client.config.MSInfoProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.library.constants.CamelOptions;
import org.qubership.integration.platform.runtime.catalog.configuration.tenant.TenantConfiguration;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers {@link MaasPropertiesUtils#enrichWithMaasEnvProperties}: it prefers the values the element
 * carries and otherwise falls back to the microservice namespace, a disabled tenant flag, and the
 * default tenant.
 */
@ExtendWith(MockitoExtension.class)
class MaasPropertiesUtilsTest {

    @Mock
    private TenantConfiguration tenantConfiguration;
    @Mock
    private MSInfoProvider msInfoProvider;

    private MaasPropertiesUtils utils() {
        return new MaasPropertiesUtils(tenantConfiguration, msInfoProvider);
    }

    @Test
    void usesTheValuesCarriedByTheElementWhenPresent() {
        Element element = mock(Element.class);
        Map<String, Object> properties = new HashMap<>();
        properties.put(CamelOptions.MAAS_CLASSIFIER_NAMESPACE_PROP, "custom-namespace");
        properties.put(MaasPropertiesUtils.MAAS_CLASSIFIER_TENANT_ENABLED_PROP, "true");
        properties.put(MaasPropertiesUtils.MAAS_CLASSIFIER_TENANT_ID_PROP, "tenant-7");
        when(element.getProperties()).thenReturn(properties);

        Map<String, String> result = new HashMap<>();
        utils().enrichWithMaasEnvProperties(element, result);

        assertThat(result)
                .containsEntry(CamelOptions.MAAS_CLASSIFIER_NAMESPACE_PROP, "custom-namespace")
                .containsEntry(MaasPropertiesUtils.MAAS_CLASSIFIER_TENANT_ENABLED_PROP, "true")
                .containsEntry(MaasPropertiesUtils.MAAS_CLASSIFIER_TENANT_ID_PROP, "tenant-7");
    }

    @Test
    void fallsBackToTheNamespaceDisabledTenantFlagAndDefaultTenantWhenTheElementIsBlank() {
        Element element = mock(Element.class);
        when(element.getProperties()).thenReturn(new HashMap<>());
        when(msInfoProvider.getNamespace()).thenReturn("platform-namespace");
        when(tenantConfiguration.getDefaultTenant()).thenReturn("default-tenant");

        Map<String, String> result = new HashMap<>();
        utils().enrichWithMaasEnvProperties(element, result);

        assertThat(result)
                .containsEntry(CamelOptions.MAAS_CLASSIFIER_NAMESPACE_PROP, "platform-namespace")
                .containsEntry(MaasPropertiesUtils.MAAS_CLASSIFIER_TENANT_ENABLED_PROP, "false")
                .containsEntry(MaasPropertiesUtils.MAAS_CLASSIFIER_TENANT_ID_PROP, "default-tenant");
    }
}
