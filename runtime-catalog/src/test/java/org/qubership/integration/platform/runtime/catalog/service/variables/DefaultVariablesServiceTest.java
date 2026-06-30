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

package org.qubership.integration.platform.runtime.catalog.service.variables;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.configuration.KubeOperatorAutoConfiguration;
import org.qubership.integration.platform.runtime.catalog.configuration.tenant.TenantConfiguration;
import org.qubership.integration.platform.runtime.catalog.service.variables.secrets.SecretService;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultVariablesServiceTest {

    @Mock
    SecretService secretService;

    @Mock
    CommonVariablesService commonVariablesService;

    @Mock
    SecuredVariableService securedVariableService;

    @Mock
    DefaultSecretPolicyService defaultSecretPolicyService;

    @Mock
    KubeOperatorAutoConfiguration kubeOperatorAutoConfiguration;

    @Mock
    TenantConfiguration tenantConfiguration;

    @Mock
    DefaultVariablesProvider defaultVariablesProvider;

    @BeforeEach
    void clearDefaultVariableNamesList() {
        DefaultVariablesService.DEFAULT_VARIABLES_LIST.clear();
    }

    @Test
    @DisplayName("restoreVariables deletes default secret variables when default secret is enabled")
    void restoreVariablesDeletesDefaultSecretVariablesWhenEnabled() {
        String defaultSecret = "def-sec";
        when(secretService.getDefaultSecretName()).thenReturn(defaultSecret);
        when(secretService.createSecret(defaultSecret)).thenReturn(true);
        when(kubeOperatorAutoConfiguration.getNamespace()).thenReturn("ns1");
        when(defaultVariablesProvider.getDefaultVariableNames()).thenReturn(Collections.emptyList());
        when(defaultVariablesProvider.provide()).thenReturn(Collections.emptyMap());
        when(defaultSecretPolicyService.isDefaultSecretEnabled()).thenReturn(true);
        when(tenantConfiguration.getDefaultTenant()).thenReturn("11111111111111111111");

        DefaultVariablesService service = new DefaultVariablesService(
                secretService,
                commonVariablesService,
                securedVariableService,
                defaultSecretPolicyService,
                kubeOperatorAutoConfiguration,
                tenantConfiguration,
                defaultVariablesProvider);

        service.restoreVariables();

        verify(secretService).createSecret(defaultSecret);
        verify(securedVariableService).deleteVariables(
                defaultSecret,
                Set.of(DefaultVariablesService.TENANT_VARIABLE_NAME, DefaultVariablesService.NAMESPACE_VARIABLE_NAME),
                false);
        verify(commonVariablesService).addVariablesUnlogged(
                Map.of(DefaultVariablesService.TENANT_VARIABLE_NAME, "11111111111111111111",
                    DefaultVariablesService.NAMESPACE_VARIABLE_NAME, "ns1"));
    }

    @Test
    @DisplayName("restoreVariables skips deleting default secret variables when default secret is disabled")
    void restoreVariablesSkipsDeleteWhenDisabled() {
        String defaultSecret = "def-sec";
        when(secretService.getDefaultSecretName()).thenReturn(defaultSecret);
        when(secretService.createSecret(defaultSecret)).thenReturn(true);
        when(kubeOperatorAutoConfiguration.getNamespace()).thenReturn("ns1");
        when(defaultVariablesProvider.getDefaultVariableNames()).thenReturn(Collections.emptyList());
        when(defaultVariablesProvider.provide()).thenReturn(Collections.emptyMap());
        when(defaultSecretPolicyService.isDefaultSecretEnabled()).thenReturn(false);
        when(tenantConfiguration.getDefaultTenant()).thenReturn("11111111111111111111");

        DefaultVariablesService service = new DefaultVariablesService(
                secretService,
                commonVariablesService,
                securedVariableService,
                defaultSecretPolicyService,
                kubeOperatorAutoConfiguration,
                tenantConfiguration,
                defaultVariablesProvider);

        service.restoreVariables();

        verify(securedVariableService, never()).deleteVariables(anyString(), anySet(), anyBoolean());
        verify(commonVariablesService).addVariablesUnlogged(
                Map.of(DefaultVariablesService.TENANT_VARIABLE_NAME, "11111111111111111111",
                    DefaultVariablesService.NAMESPACE_VARIABLE_NAME, "ns1"));
    }
}
