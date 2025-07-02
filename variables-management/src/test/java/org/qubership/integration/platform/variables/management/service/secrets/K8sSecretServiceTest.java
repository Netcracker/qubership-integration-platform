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

package org.qubership.integration.platform.variables.management.service.secrets;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.kubernetes.client.openapi.models.V1Secret;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.variables.management.configuration.MapperAutoConfiguration;
import org.qubership.integration.platform.variables.management.kubernetes.KubeApiNotFoundException;
import org.qubership.integration.platform.variables.management.kubernetes.KubeOperator;
import org.qubership.integration.platform.variables.management.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.variables.management.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.variables.management.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.variables.management.rest.exception.SecretNotFoundException;
import org.qubership.integration.platform.variables.management.service.ActionsLogService;
import org.qubership.integration.platform.variables.management.service.serializer.KubeSecretSerializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ContextConfiguration(classes = {
        KubeSecretSerializer.class,
        MapperAutoConfiguration.class,
        K8sSecretService.class,
})
@TestPropertySource(properties = {
        "kubernetes.variables-secret.label=" + K8sSecretServiceTest.SECRET_LABEL_NAME,
        "kubernetes.variables-secret.name=" + K8sSecretServiceTest.KUBE_SECRET_NAME
})
@ExtendWith(SpringExtension.class)
@ExtendWith(MockitoExtension.class)
public class K8sSecretServiceTest {
    public static final String SECRET_LABEL_NAME = "qip.netcracker.com/variable-type";
    public static final String KUBE_SECRET_NAME = "qip-secured-variables-v2";

    private static final String SECRET_NAME = "qip-test-secret";

    @MockitoBean
    KubeOperator operator;

    @MockitoBean
    ActionsLogService actionLogger;

    @Captor
    private ArgumentCaptor<ActionLog> actionLogCaptor;

    @Autowired
    @Qualifier("yamlMapper")
    private YAMLMapper yamlMapper;

    @Autowired
    private K8sSecretService secretService;

    @DisplayName("Creating a secret should do nothing and return false when the secret already exists")
    @Test
    public void creatingASecretShouldDoNothingAndReturnFalseWhenSecretExists() {
        doReturn(new V1Secret()).when(operator).getSecretObjectByName(SECRET_NAME);
        assertThat(secretService.createSecret(SECRET_NAME), is(false));
        verify(operator, never()).createSecret(any(), any(), any());
        verify(actionLogger, never()).logAction(any());
    }

    @DisplayName("Should call a corresponding KubeOperator method when creating an empty secret")
    @Test
    public void creatingASecretShouldCallKubeOperatorMethod() {
        secretService.createSecret(SECRET_NAME);
        verify(operator, times(1)).createSecret(
                eq(SECRET_NAME), eq(Pair.of(SECRET_LABEL_NAME, "secured")), isNull());
    }

    @DisplayName("Should log action for secret when creating a secret")
    @Test
    public void shouldLogActionForSecretWhenCreatingSecret() {
        secretService.createSecret(SECRET_NAME);

        verify(actionLogger, times(1)).logAction(actionLogCaptor.capture());

        ActionLog action = actionLogCaptor.getValue();
        assertThat(action.getEntityType(), equalTo(EntityType.SECRET));
        assertThat(action.getEntityName(), equalTo(SECRET_NAME));
        assertThat(action.getOperation(), equalTo(LogOperation.CREATE));
    }

    @DisplayName("Should return secrets' data from KubeOperator for specific label when getting all secrets data")
    @Test
    public void shouldReturnSecretsDataFromKubeOperatorWhenGettingAllSecrets() {
        Map<String, Map<String, String>> data = Map.of("foo", Map.of("bar", "baz"));
        Pair<String, String> label = Pair.of(SECRET_LABEL_NAME, "secured");
        doReturn(data).when(operator).getAllSecretsWithLabel(eq(label));
        assertThat(secretService.getAllSecretsData(), equalTo(data));
        verify(operator, times(1)).getAllSecretsWithLabel(eq(label));
    }

    @DisplayName("Should throw SecretNotFoundException exception when getting a secret's data for non-existing secret and fail flag is set")
    @Test
    public void shouldThrowSecretNotFoundExceptionWhenGettingASecretDataForNonExistingSecretAndFailFlagIsSet() {
        doThrow(KubeApiNotFoundException.class).when(operator).getSecretByName(eq(SECRET_NAME), eq(true));
        Exception exception = assertThrows(SecretNotFoundException.class, () -> secretService.getSecretData(SECRET_NAME, true));
        assertThat(exception.getMessage(), containsString(SECRET_NAME));
    }

    @DisplayName("Should return an empty map when getting a secret's data for non-existing secret and fail flag is unset")
    @Test
    public void shouldReturnAnEmptyMapWhenGettingASecretDataForNonExistingSecretAndFailFlagIsUnset() {
        doReturn(Collections.emptyMap()).when(operator).getSecretByName(eq(SECRET_NAME), eq(false));
        Map<String, String> result = secretService.getSecretData(SECRET_NAME, false);
        assertThat(result, notNullValue());
        assertThat(result, is(Collections.emptyMap()));
    }

    @DisplayName("Should return data from KubeOperator when getting a secret's data")
    @Test
    public void shouldReturnSecretDataFromKubeOperatorWhenGettingASecretData() {
        Map<String, String> data = Map.of("foo", "bar");
        doReturn(data).when(operator).getSecretByName(eq(SECRET_NAME), eq(true));
        assertThat(secretService.getSecretData(SECRET_NAME, true), equalTo(data));
    }

    @DisplayName("getDefaultSecretName should return k8s secret name")
    @Test
    public void getDefaultSecretNameShouldReturnK8sSecretName() {
        assertThat(secretService.getDefaultSecretName(), equalTo(KUBE_SECRET_NAME));
    }

    @DisplayName("isDefaultSecret method should return true for k8s secret name")
    @Test
    public void isDefaultSecretShouldReturnTrueForK8sSecretName() {
        assertThat(secretService.isDefaultSecret(KUBE_SECRET_NAME), is(true));
    }

    @DisplayName("isDefaultSecret method should return false when name doesn't equal to k8s secret name")
    @Test
    public void isDefaultSecretShouldReturnFalseWhenDefaultSecretNameDoesNotEqualToK8sSecretName() {
        assertThat(secretService.isDefaultSecret("foo"), is(false));
    }

    @DisplayName("Should throw an exception when trying to get a secret template and the secret doesn't exists")
    @Test
    public void shouldThrowAnExceptionWhenTryingToGetSecretTemplateAndTheSecretDoesNotExists() {
        doReturn(null).when(operator).getSecretObjectByName(eq(SECRET_NAME));
        Exception exception = assertThrows(SecretNotFoundException.class, () -> secretService.getSecretTemplate(SECRET_NAME));
        assertThat(exception.getMessage(), containsString(SECRET_NAME));
    }

    @DisplayName("Should return secret template when secret exists")
    @Test
    public void shouldReturnSecretTemplateWhenSecretExists() throws JsonProcessingException {
        V1Secret secret = new V1Secret();
        secret.setKind("Secret");
        doReturn(secret).when(operator).getSecretObjectByName(eq(SECRET_NAME));
        assertThat(secretService.getSecretTemplate(SECRET_NAME), is(yamlMapper.writeValueAsString(secret)));
    }

    @DisplayName("addEntries should delegate work to KubeOperator")
    @Test
    public void addEntriesShouldDelegateWorkToKubeOperator() {
        Map<String, String> data = Map.of("foo", "bar");
        secretService.addEntries(SECRET_NAME, data, true);
        verify(operator, times(1)).addSecretData(eq(SECRET_NAME), eq(data), eq(true));
    }

    @DisplayName("updateEntries should delegate work to KubeOperator")
    @Test
    public void updateEntriesShouldDelegateWorkToKubeOperator() {
        Map<String, String> data = Map.of("foo", "bar");
        secretService.updateEntries(SECRET_NAME, data);
        verify(operator, times(1)).updateSecretData(eq(SECRET_NAME), eq(data));
    }

    @DisplayName("removeEntries should pass keys to KubeOperator")
    @Test
    public void removeEntriesShouldDelegateWorkToKubeOperator() {
        Set<String> keys = Set.of("foo", "bar");
        secretService.removeEntries(SECRET_NAME, keys);
        verify(operator, times(1)).removeSecretData(eq(SECRET_NAME), eq(keys));
    }

    @DisplayName("removeEntriesAsync should delegate work to KubeOperator")
    @Test
    public void removeEntriesAsyncShouldDelegateWorkToKubeOperator() {
        Set<String> keys = Set.of("foo", "bar");
        secretService.removeEntriesAsync(SECRET_NAME, keys, null);
        verify(operator, times(1)).removeSecretDataAsync(eq(SECRET_NAME), eq(keys), any());
    }
}
