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

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import jakarta.persistence.EntityExistsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.consul.ConsulService;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.variable.ImportVariableResult;
import org.qubership.integration.platform.runtime.catalog.service.ActionsLogService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.instructions.ImportInstructionsService;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommonVariablesServiceTest {
    private static final String CLASHING_NAME = "foo";

    @Mock
    ActionsLogService actionLogger;
    @Mock
    YAMLMapper yamlMapper;
    @Mock
    SecuredVariableService securedVariableService;
    @Mock
    DefaultSecretPolicyService defaultSecretPolicyService;
    @Mock
    ConsulService consulService;
    @Mock
    ImportInstructionsService importInstructionsService;

    private CommonVariablesService commonVariablesService;

    @BeforeEach
    void setUp() {
        commonVariablesService = new CommonVariablesService(
                actionLogger,
                yamlMapper,
                securedVariableService,
                defaultSecretPolicyService,
                consulService,
                importInstructionsService);
    }

    @Test
    @DisplayName("DEFAULT_SECRET_ENABLED=false: single add succeeds without reading default secret")
    void addVariableShouldNotReadDefaultSecretWhenFlagDisabled() {
        when(defaultSecretPolicyService.isDefaultSecretEnabled()).thenReturn(false);
        when(consulService.getCommonVariable(CLASHING_NAME)).thenReturn(null);

        String name = commonVariablesService.addVariable(CLASHING_NAME, "common-value");

        assertThat(name, equalTo(CLASHING_NAME));
        verify(securedVariableService, never()).getVariablesForDefaultSecret(anyBoolean());
        verify(consulService).updateCommonVariable(CLASHING_NAME, "common-value");
    }

    @Test
    @DisplayName("DEFAULT_SECRET_ENABLED=false: bulk add skips default-secret name collision check")
    void addVariablesShouldNotReadDefaultSecretWhenFlagDisabled() {
        Map<String, String> variables = Map.of(CLASHING_NAME, "v1", "bar", "v2");
        when(defaultSecretPolicyService.isDefaultSecretEnabled()).thenReturn(false);
        when(consulService.getCommonVariable(anyString())).thenReturn(null);

        List<ImportVariableResult> results = commonVariablesService.addVariables(variables, false);

        assertThat(results, hasSize(2));
        verify(securedVariableService, never()).getVariablesForDefaultSecret(anyBoolean());
        verify(consulService).updateCommonVariables(variables);
    }

    @Test
    @DisplayName("DEFAULT_SECRET_ENABLED=true: single add rejects name reserved in default secret")
    void addVariableShouldRejectDefaultSecretNameWhenFlagEnabled() {
        when(defaultSecretPolicyService.isDefaultSecretEnabled()).thenReturn(true);
        when(securedVariableService.getVariablesForDefaultSecret(false)).thenReturn(Set.of(CLASHING_NAME));

        assertThrows(
                EntityExistsException.class,
                () -> commonVariablesService.addVariable(CLASHING_NAME, "common-value"));

        verify(securedVariableService).getVariablesForDefaultSecret(false);
        verify(consulService, never()).updateCommonVariable(anyString(), any());
    }

    @Test
    @DisplayName("DEFAULT_SECRET_ENABLED=true: bulk add rejects name reserved in default secret")
    void addVariablesShouldRejectDefaultSecretNameWhenFlagEnabled() {
        Map<String, String> variables = Map.of(CLASHING_NAME, "v1", "bar", "v2");
        when(defaultSecretPolicyService.isDefaultSecretEnabled()).thenReturn(true);
        when(securedVariableService.getVariablesForDefaultSecret(false)).thenReturn(Set.of(CLASHING_NAME));

        assertThrows(
                EntityExistsException.class,
                () -> commonVariablesService.addVariables(variables, false));

        verify(securedVariableService).getVariablesForDefaultSecret(false);
        verify(consulService, never()).updateCommonVariables(any());
    }
}
