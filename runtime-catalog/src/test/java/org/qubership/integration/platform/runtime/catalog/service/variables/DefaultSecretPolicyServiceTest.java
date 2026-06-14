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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.DefaultSecretGoneException;
import org.qubership.integration.platform.runtime.catalog.service.variables.secrets.SecretService;

import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultSecretPolicyServiceTest {
    private static final String DEFAULT_SECRET = "qip-secured-variables-v2";
    private static final String NAMED_SECRET = "my-named-secret";

    @Mock
    SecretService secretService;

    private DefaultSecretPolicyService serviceWithFlag(boolean defaultSecretEnabled) {
        return new DefaultSecretPolicyService(secretService, defaultSecretEnabled);
    }

    @Test
    @DisplayName("filterSecretsForList returns all secrets when flag is enabled")
    void filterSecretsForListWhenEnabledReturnsAllSecrets() {
        DefaultSecretPolicyService service = serviceWithFlag(true);
        Map<String, Map<String, String>> secrets = Map.of(
                DEFAULT_SECRET, Map.of("default", "1"),
                NAMED_SECRET, Map.of("named", "2"));

        assertThat(service.filterSecretsForList(secrets), equalTo(secrets));
    }

    @Test
    @DisplayName("filterSecretsForList returns named secrets when flag is disabled")
    void filterSecretsForListWhenDisabledReturnsNamedSecrets() {
        when(secretService.isDefaultSecret(DEFAULT_SECRET)).thenReturn(true);
        when(secretService.isDefaultSecret(NAMED_SECRET)).thenReturn(false);
        DefaultSecretPolicyService service = serviceWithFlag(false);
        Map<String, Map<String, String>> secrets = Map.of(
                DEFAULT_SECRET, Map.of("default", "1"),
                NAMED_SECRET, Map.of("named", "2"));

        var result = service.filterSecretsForList(secrets);

        assertThat(result, aMapWithSize(1));
        assertThat(result.containsKey(NAMED_SECRET), equalTo(true));
        assertThat(result.containsKey(DEFAULT_SECRET), equalTo(false));
    }

    @Test
    @DisplayName("assertDefaultSecretAccessible should throw exception when default secret is disabled")
    void shouldThrowExceptionWhenDefaultSecretDisabled() {
        when(secretService.isDefaultSecret(DEFAULT_SECRET)).thenReturn(true);
        DefaultSecretPolicyService service = serviceWithFlag(false);
        assertThrows(
                DefaultSecretGoneException.class,
                () -> service.assertDefaultSecretAccessible(DEFAULT_SECRET));
    }

    @Test
    @DisplayName("assertDefaultSecretAccessible should not throw exception when default secret is enabled")
    void shouldNotThrowExceptionWhenDefaultSecretEnabled() {
        DefaultSecretPolicyService service = serviceWithFlag(true);
        assertDoesNotThrow(
                () -> service.assertDefaultSecretAccessible(DEFAULT_SECRET));
    }

    @Test
    @DisplayName("assertDefaultSecretAccessible should not throw exception when secret is not default")
    void shouldNotThrowExceptionWhenSecretIsNotDefault() {
        when(secretService.isDefaultSecret(NAMED_SECRET)).thenReturn(false);
        DefaultSecretPolicyService service = serviceWithFlag(false);
        assertDoesNotThrow(
                () -> service.assertDefaultSecretAccessible(NAMED_SECRET));
    }

    @Test
    @DisplayName("assertCanAddVariables should allow variables for non-default secret")
    void shouldAllowVariablesForNonDefaultSecret() {
        when(secretService.isDefaultSecret(NAMED_SECRET)).thenReturn(false);

        DefaultSecretPolicyService service = serviceWithFlag(false);

        assertDoesNotThrow(() -> service.assertCanAddVariables(
                NAMED_SECRET,
                Map.of("a", "1"),
                Map.of()));
    }

    @Test
    @DisplayName("assertCanAddVariables should not throw when variables already exist")
    void shouldNotThrowWhenVariablesAlreadyExist() {
        when(secretService.isDefaultSecret(DEFAULT_SECRET)).thenReturn(true);

        DefaultSecretPolicyService service = serviceWithFlag(false);

        assertDoesNotThrow(() -> service.assertCanAddVariables(
                DEFAULT_SECRET,
                Map.of("a", "1"),
                Map.of("a", "old")));
    }

    @Test
    @DisplayName("assertCanAddVariables should throw when adding new variable to default secret")
    void shouldThrowWhenAddingNewVariableToDefaultSecret() {
        when(secretService.isDefaultSecret(DEFAULT_SECRET)).thenReturn(true);

        DefaultSecretPolicyService service = serviceWithFlag(false);
        Map<String, String> newVariables = Map.of("newKey", "1");
        Map<String, String> existingVariables = Map.of("existing", "old");

        assertThrows(
                DefaultSecretGoneException.class,
                () -> service.assertCanAddVariables(DEFAULT_SECRET, newVariables, existingVariables));
    }
}
