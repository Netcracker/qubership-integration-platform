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

package org.qubership.integration.platform.engine.kubernetes;

import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.RbacAuthorizationV1Api;
import io.kubernetes.client.openapi.models.V1PolicyRule;
import io.kubernetes.client.openapi.models.V1Role;
import io.kubernetes.client.openapi.models.V1Secret;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.errorhandling.KubeApiException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KubeOperatorTest {
    private static final String NAMESPACE = "cip";
    private static final String ROLE_NAME = "cip-secured-variables-reader";

    private CoreV1Api coreApi;
    private RbacAuthorizationV1Api rbacApi;
    private KubeOperator kubeOperator;

    @BeforeEach
    void setUp() {
        coreApi = mock(CoreV1Api.class);
        rbacApi = mock(RbacAuthorizationV1Api.class);
        kubeOperator = new KubeOperator(coreApi, mock(AppsV1Api.class), rbacApi, NAMESPACE, false);
    }

    @Test
    void getSecretsFromRoleReadsEachSecretListedInTheRolesResourceNames() throws ApiException {
        stubRole(roleWithSecretRule(List.of("get"), "secret-a", "secret-b"));
        stubSecret("secret-a", Map.of("key-a", "value-a".getBytes()));
        stubSecret("secret-b", Map.of("key-b", "value-b".getBytes()));

        Map<String, Map<String, String>> secrets = kubeOperator.getSecretsFromRole(ROLE_NAME);

        assertEquals(Map.of("key-a", "value-a"), secrets.get("secret-a"));
        assertEquals(Map.of("key-b", "value-b"), secrets.get("secret-b"));
    }

    @Test
    void getSecretsFromRoleIgnoresRulesForOtherResourcesOrVerbs() throws ApiException {
        V1PolicyRule configMapRule = new V1PolicyRule()
                .resources(List.of("configmaps"))
                .resourceNames(List.of("unrelated-configmap"))
                .verbs(List.of("get"));
        V1PolicyRule secretsListRule = new V1PolicyRule()
                .resources(List.of("secrets"))
                .resourceNames(List.of("unrelated-secret"))
                .verbs(List.of("list"));
        stubRole(new V1Role().rules(List.of(configMapRule, secretsListRule)));

        Map<String, Map<String, String>> secrets = kubeOperator.getSecretsFromRole(ROLE_NAME);

        assertTrue(secrets.isEmpty());
        verify(coreApi, never()).readNamespacedSecret(any(), any(), any());
    }

    @Test
    void getSecretsFromRoleReturnsEmptyWhenRoleNotFound() throws ApiException {
        stubRoleFailure(new ApiException(404, "Not Found"));

        Map<String, Map<String, String>> secrets = kubeOperator.getSecretsFromRole(ROLE_NAME);

        assertTrue(secrets.isEmpty());
    }

    @Test
    void getSecretsFromRoleThrowsKubeApiExceptionOnOtherRoleFailure() throws ApiException {
        stubRoleFailure(new ApiException(403, "Forbidden"));

        assertThrows(KubeApiException.class, () -> kubeOperator.getSecretsFromRole(ROLE_NAME));
    }

    @Test
    void getSecretsFromRoleSkipsMissingSecretAndKeepsTheOthers() throws ApiException {
        stubRole(roleWithSecretRule(List.of("get"), "missing-secret", "secret-b"));
        stubSecretFailure("missing-secret", new ApiException(404, "Not Found"));
        stubSecret("secret-b", Map.of("key-b", "value-b".getBytes()));

        Map<String, Map<String, String>> secrets = kubeOperator.getSecretsFromRole(ROLE_NAME);

        assertEquals(Map.of("secret-b", Map.of("key-b", "value-b")), secrets);
    }

    @Test
    void getSecretsFromRoleThrowsKubeApiExceptionWhenSecretReadFailsWithOtherError() throws ApiException {
        stubRole(roleWithSecretRule(List.of("get"), "secret-a"));
        stubSecretFailure("secret-a", new ApiException(500, "Internal Server Error"));

        assertThrows(KubeApiException.class, () -> kubeOperator.getSecretsFromRole(ROLE_NAME));
    }

    private V1Role roleWithSecretRule(List<String> verbs, String... resourceNames) {
        V1PolicyRule rule = new V1PolicyRule()
                .apiGroups(List.of(""))
                .resources(List.of("secrets"))
                .resourceNames(List.of(resourceNames))
                .verbs(verbs);
        return new V1Role().rules(List.of(rule));
    }

    private void stubRole(V1Role role) throws ApiException {
        when(rbacApi.readNamespacedRole(eq(ROLE_NAME), eq(NAMESPACE), any())).thenReturn(role);
    }

    private void stubRoleFailure(ApiException exception) throws ApiException {
        when(rbacApi.readNamespacedRole(eq(ROLE_NAME), eq(NAMESPACE), any())).thenThrow(exception);
    }

    private void stubSecret(String name, Map<String, byte[]> data) throws ApiException {
        when(coreApi.readNamespacedSecret(eq(name), eq(NAMESPACE), any())).thenReturn(new V1Secret().data(data));
    }

    private void stubSecretFailure(String name, ApiException exception) throws ApiException {
        when(coreApi.readNamespacedSecret(eq(name), eq(NAMESPACE), any())).thenThrow(exception);
    }
}
