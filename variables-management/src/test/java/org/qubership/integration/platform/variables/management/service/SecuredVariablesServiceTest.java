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

package org.qubership.integration.platform.variables.management.service;

import jakarta.persistence.EntityExistsException;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.variables.management.configuration.MapperAutoConfiguration;
import org.qubership.integration.platform.variables.management.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.variables.management.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.variables.management.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.variables.management.rest.exception.EmptyVariableFieldException;
import org.qubership.integration.platform.variables.management.rest.exception.SecretNotFoundException;
import org.qubership.integration.platform.variables.management.rest.exception.SecuredVariablesNotFoundException;
import org.qubership.integration.platform.variables.management.service.secrets.SecretService;
import org.qubership.integration.platform.variables.management.service.serializer.KubeSecretSerializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

import static java.util.stream.Collectors.toSet;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ContextConfiguration(classes = {
        MapperAutoConfiguration.class,
        KubeSecretSerializer.class,
        SecuredVariableService.class
})
@ExtendWith(SpringExtension.class)
@ExtendWith(MockitoExtension.class)
public class SecuredVariablesServiceTest {
    private static final String DEFAULT_SECRET_NAME = "qip-test-secret";

    @MockitoBean
    SecretService secretService;

    @MockitoBean
    ActionsLogService actionLogger;

    @MockitoBean
    CommonVariablesService commonVariablesService;

    @Captor
    private ArgumentCaptor<ActionLog> actionLogCaptor;

    @Autowired
    private SecuredVariableService securedVariableService;

    @BeforeEach
    public void beforeEach() {
        doReturn(DEFAULT_SECRET_NAME).when(secretService).getDefaultSecretName();
        doReturn(true).when(secretService).isDefaultSecret(DEFAULT_SECRET_NAME);
        Map<String, Map<String, String>> data = Map.of(
                DEFAULT_SECRET_NAME, Map.of("foo", "bar", "baz", "qux"),
                "fiz", Map.of("quux", "gee")
        );
        doReturn(data).when(secretService).getAllSecretsData();
        doReturn(data.get(DEFAULT_SECRET_NAME))
                .when(secretService).getSecretData(eq(DEFAULT_SECRET_NAME), anyBoolean());
        doReturn(data.get("fiz"))
                .when(secretService).getSecretData(eq("fiz"), anyBoolean());
    }

    @DisplayName("getAllSecretsVariablesNames should return all variable names by secrets")
    @Test
    public void shouldReturnAllSecretsVariableNames() {
        assertThat(securedVariableService.getAllSecretsVariablesNames(),
                equalTo(Map.of(DEFAULT_SECRET_NAME, Set.of("foo", "baz"), "fiz", Set.of("quux"))));
    }

    @DisplayName("getVariablesForDefaultSecret should throw an exception when default secret does not exists and fail flag is set")
    @Test
    public void getVariablesForDefaultSecretShouldThrowExceptionWhenDefaultSecretNotExistAndFailFlagIsSet() {
        doReturn(Collections.emptyMap()).when(secretService).getAllSecretsData();
        doThrow(SecretNotFoundException.class).when(secretService).getSecretData(eq(DEFAULT_SECRET_NAME), eq(true));

        assertThrows(SecretNotFoundException.class, () -> securedVariableService.getVariablesForDefaultSecret(true));
    }

    @DisplayName("getVariablesForDefaultSecret should return an empty set when default secret does not exists and fail flag is unset")
    @Test
    public void getVariablesForDefaultSecretShouldReturnAnEmptySetWhenDefaultSecretNotExistAndFailFlagIsUnset() {
        doReturn(Collections.emptyMap()).when(secretService).getAllSecretsData();
        doReturn(Collections.emptyMap()).when(secretService).getSecretData(eq(DEFAULT_SECRET_NAME), eq(false));

        assertThat(securedVariableService.getVariablesForDefaultSecret(false), equalTo(Collections.emptySet()));
    }

    @DisplayName("getVariablesForSecret should throw an exception when the secret does not exists and fail flag is set")
    @Test
    public void getVariablesForSecretShouldThrowExceptionWhenSecretNotExistAndFailFlagIsSet() {
        doThrow(SecretNotFoundException.class).when(secretService).getSecretData(eq("non-existing-secret"), eq(true));
        assertThrows(SecretNotFoundException.class, () -> securedVariableService.getVariablesForSecret("non-existing-secret", true));
    }

    @DisplayName("getVariablesForSecret should return an empty set when the secret does not exists and fail flag is unset")
    @Test
    public void getVariablesForSecretShouldReturnAnEmptySetWhenSecretNotExistAndFailFlagIsUnset() {
        doReturn(Collections.emptyMap()).when(secretService).getSecretData(eq("non-existing-secret"), eq(false));
        assertThat(securedVariableService.getVariablesForSecret("non-existing-secret", false), equalTo(Collections.emptySet()));
    }

    @DisplayName("getVariablesForSecret should return variable names for a secret")
    @Test
    public void getVariablesForSecretShouldReturnVariableNamesForSecret() {
        assertThat(securedVariableService.getVariablesForSecret("fiz", true), equalTo(Set.of("quux")));
    }

    @DisplayName("addVariablesToDefaultSecret should delegate to addVariables")
    @Test
    public void addVariablesToDefaultSecretShouldDelegateToAddVariables() {
        SecuredVariableService spy = Mockito.spy(securedVariableService);
        Map<String, String> variables = Map.of("foo", "gee", "bla-bla-bla", "ha-ha");
        doReturn(Map.of(DEFAULT_SECRET_NAME, Set.of("foo", "bla-bla-bla")))
                .when(spy).addVariables(eq(DEFAULT_SECRET_NAME), eq(variables));
        assertThat(
                spy.addVariablesToDefaultSecret(variables),
                equalTo(Set.of("foo", "bla-bla-bla")));
        verify(spy, times(1)).addVariables(eq(DEFAULT_SECRET_NAME), eq(variables));
    }

    @DisplayName("addVariables should delegate to addVariables method with unset import mode flag")
    @Test
    public void addVariablesShouldDelegateToAddVariablesWithoutImportMode() {
        SecuredVariableService spy = Mockito.spy(securedVariableService);
        Map<String, String> variables = Map.of("foo", "gee", "bla-bla-bla", "ha-ha");
        doReturn(Map.of(DEFAULT_SECRET_NAME, Set.of("foo", "bla-bla-bla")))
                .when(spy).addVariables(eq(DEFAULT_SECRET_NAME), eq(variables), eq(false));
        assertThat(
                spy.addVariables(DEFAULT_SECRET_NAME, variables),
                equalTo(Map.of(DEFAULT_SECRET_NAME, Set.of("foo", "bla-bla-bla"))));
        verify(spy, times(1)).addVariables(eq(DEFAULT_SECRET_NAME), eq(variables), eq(false));
    }

    @DisplayName("addVariables should add variables to a secret and return updated variable names set")
    @Test
    public void addVariablesShouldAddVariablesToDefaultSecretAndReturnUpdatedVariableNamesSet() {
        Map<String, String> variables = Map.of("quux", "foo", "bla-bla-bla", "ha-ha");
        doReturn(Map.of("fiz", variables))
                .when(secretService).addEntries(eq("fiz"), eq(variables), anyBoolean());
        assertThat(
                securedVariableService.addVariables("fiz", variables, false),
                equalTo(Map.of("fiz", Set.of("quux", "bla-bla-bla"))));
    }

    @DisplayName("addVariables should log actions on variables")
    @Test
    public void addVariablesShouldLogActionsOnVariables() {
        Map<String, String> variables = Map.of("quux", "foo", "bla-bla-bla", "ha-ha");
        doReturn(Map.of("fiz", variables))
                .when(secretService).addEntries(eq("fiz"), eq(variables), anyBoolean());
        doReturn(true).when(actionLogger).logAction(actionLogCaptor.capture());
        securedVariableService.addVariables("fiz", variables, false);

        assertThat(actionLogCaptor.getAllValues().size(), equalTo(2));
        assertTrue(actionLogCaptor.getAllValues().stream().map(ActionLog::getParentType).allMatch(EntityType.SECRET::equals));
        assertTrue(actionLogCaptor.getAllValues().stream().map(ActionLog::getEntityType).allMatch(EntityType.SECURED_VARIABLE::equals));
        assertTrue(actionLogCaptor.getAllValues().stream().map(ActionLog::getParentName).allMatch("fiz"::equals));
        assertThat(
                actionLogCaptor.getAllValues().stream()
                        .map(action -> Pair.of(action.getEntityName(), action.getOperation()))
                        .collect(toSet()),
                equalTo(Set.of(
                        Pair.of("quux", LogOperation.UPDATE),
                        Pair.of("bla-bla-bla", LogOperation.CREATE))));
    }

    @DisplayName("addVariables should log actions on variables as import when a corresponding flag is set")
    @Test
    public void addVariablesShouldLogActionsOnVariablesAsImportWhenCorrespondingFlagIsSet() {
        Map<String, String> variables = Map.of("quux", "foo", "bla-bla-bla", "ha-ha");
        doReturn(Map.of("fiz", variables))
                .when(secretService).addEntries(eq("fiz"), eq(variables), anyBoolean());
        doReturn(true).when(actionLogger).logAction(actionLogCaptor.capture());
        securedVariableService.addVariables("fiz", variables, true);

        assertThat(actionLogCaptor.getAllValues().size(), equalTo(2));
        assertTrue(actionLogCaptor.getAllValues().stream().map(ActionLog::getParentType).allMatch(EntityType.SECRET::equals));
        assertTrue(actionLogCaptor.getAllValues().stream().map(ActionLog::getEntityType).allMatch(EntityType.SECURED_VARIABLE::equals));
        assertTrue(actionLogCaptor.getAllValues().stream().map(ActionLog::getParentName).allMatch("fiz"::equals));
        assertThat(
                actionLogCaptor.getAllValues().stream()
                        .map(action -> Pair.of(action.getEntityName(), action.getOperation()))
                        .collect(toSet()),
                equalTo(Set.of(
                        Pair.of("quux", LogOperation.IMPORT),
                        Pair.of("bla-bla-bla", LogOperation.IMPORT))));
    }

    @DisplayName("addVariables should throw an exception when variable name is empty")
    @Test
    public void addVariablesShouldThrowAnExceptionWhenVariableNameIsEmpty() {
        assertThrows(EmptyVariableFieldException.class,
                () -> securedVariableService.addVariables("fiz", Map.of("", "bla-bla-bla"), false));
    }

    @DisplayName("addVariables should throw an exception when adding variables to the default secret and there is already exists a common variable with same name")
    @Test
    public void addVariablesShouldThrowAnExceptionWhenThereIsAlreadyExistsCommonVariableWithSameName() {
        doReturn(Map.of("foo", "bar")).when(commonVariablesService).getVariables();
        EntityExistsException exception = assertThrows(EntityExistsException.class,
                () -> securedVariableService.addVariables(DEFAULT_SECRET_NAME, Map.of("foo", "bar"), false));
        assertThat(exception.getMessage(), containsString("foo"));
    }

    @DisplayName("deleteVariablesFromDefaultSecret should delegate to deleteVariables")
    @Test
    public void deleteVariablesFromDefaultSecretShouldDelegateToDeleteVariables() {
        SecuredVariableService spy = Mockito.spy(securedVariableService);
        Set<String> variables = Set.of("foo", "bla-bla-bla");
        doNothing().when(spy).deleteVariables(eq(DEFAULT_SECRET_NAME), eq(variables));
        spy.deleteVariablesFromDefaultSecret(variables);
        verify(spy, times(1)).deleteVariables(eq(DEFAULT_SECRET_NAME), eq(variables));
    }

    @DisplayName("deleteVariables should delegate to deleteVariables with logging flag set")
    @Test
    public void deleteVariablesShouldDelegateToDeleteVariablesWithLoggingFlagSet() {
        SecuredVariableService spy = Mockito.spy(securedVariableService);
        Set<String> variables = Set.of("foo", "bla-bla-bla");
        doNothing().when(spy).deleteVariables(eq(DEFAULT_SECRET_NAME), eq(variables), eq(true));
        spy.deleteVariables(DEFAULT_SECRET_NAME, variables);
        verify(spy, times(1)).deleteVariables(eq(DEFAULT_SECRET_NAME), eq(variables), eq(true));
    }

    @DisplayName("deleteVariables should call secret service's method removeEntities")
    @Test
    public void deleteVariablesShouldCallSecretServiceMethodRemoveEntities() {
        Set<String> variables = Set.of("foo", "bla-bla-bla");
        securedVariableService.deleteVariables(DEFAULT_SECRET_NAME, variables, false);
        verify(secretService, times(1)).removeEntries(eq(DEFAULT_SECRET_NAME), eq(variables));
    }

    @DisplayName("deleteVariables should not log operation when the corresponding flag is unset")
    @Test
    public void deleteVariablesShouldNotLogOperationWhenCorrespondingFlagIsUnset() {
        Set<String> variables = Set.of("foo", "bla-bla-bla");
        securedVariableService.deleteVariables(DEFAULT_SECRET_NAME, variables, false);
        verify(actionLogger, never()).logAction(any());
    }

    @DisplayName("deleteVariables should log operation only for existed and deleted variables")
    @Test
    public void deleteVariablesShouldLogOperationOnlyForExistedAndDeletedVariables() {
        Set<String> variables = Set.of("foo", "non-existent-variable");
        doReturn(true).when(actionLogger).logAction(actionLogCaptor.capture());

        securedVariableService.deleteVariables(DEFAULT_SECRET_NAME, variables, true);

        verify(actionLogger, times(1)).logAction(any());

        ActionLog action = actionLogCaptor.getValue();
        assertThat(action.getEntityType(), equalTo(EntityType.SECURED_VARIABLE));
        assertThat(action.getEntityName(), equalTo("foo"));
        assertThat(action.getOperation(), equalTo(LogOperation.DELETE));
        assertThat(action.getParentType(), equalTo(EntityType.SECRET));
        assertThat(action.getParentName(), equalTo(DEFAULT_SECRET_NAME));
    }

    @DisplayName("updateVariableInDefaultSecret should delegate to updateVariables method with default secret")
    @Test
    public void updateVariableInDefaultSecretShouldDelegateToUpdateVariables() {
        SecuredVariableService spy = Mockito.spy(securedVariableService);
        doReturn(Pair.of(DEFAULT_SECRET_NAME, Set.of("foo")))
                .when(spy).updateVariables(eq(DEFAULT_SECRET_NAME), eq(Map.of("foo", "gee")));
        assertThat(spy.updateVariableInDefaultSecret("foo", "gee"), equalTo("foo"));
        verify(spy, times(1)).updateVariables(eq(DEFAULT_SECRET_NAME), eq(Map.of("foo", "gee")));
    }

    @DisplayName("updateVariables should throw an exception when variable name is empty")
    @Test
    public void updateVariablesShouldThrowExceptionWhenVariableNameIsEmpty() {
        assertThrows(EmptyVariableFieldException.class,
                () -> securedVariableService.updateVariables(DEFAULT_SECRET_NAME, Map.of("", "foo")));
    }

    @DisplayName("updateVariables should throw an exception when variable does not exist")
    @Test
    public void updateVariablesShouldThrowExceptionWhenVariableDoesNotExist() {
        SecuredVariablesNotFoundException exception = assertThrows(
                SecuredVariablesNotFoundException.class,
                () -> securedVariableService.updateVariables("fiz", Map.of("foo", "bar")));
        assertThat(exception.getMessage(), containsString("foo"));
    }

    @DisplayName("updateVariables should return secret and updated variable names")
    @Test
    public void updateVariablesShouldReturnSecretAndUpdatedVariableNames() {
        assertThat(securedVariableService.updateVariables(DEFAULT_SECRET_NAME, Map.of("foo", "bar")),
                equalTo(Pair.of(DEFAULT_SECRET_NAME, Set.of("foo"))));
    }

    @DisplayName("updateVariables should log actions")
    @Test
    public void updateVariablesShouldLogActions() {
        doReturn(true).when(actionLogger).logAction(actionLogCaptor.capture());
        securedVariableService.updateVariables(DEFAULT_SECRET_NAME, Map.of("foo", "bar", "baz", "qux"));
        assertThat(actionLogCaptor.getAllValues().size(), equalTo(2));
        assertTrue(actionLogCaptor.getAllValues().stream().map(ActionLog::getParentType).allMatch(EntityType.SECRET::equals));
        assertTrue(actionLogCaptor.getAllValues().stream().map(ActionLog::getParentName).allMatch(DEFAULT_SECRET_NAME::equals));
        assertTrue(actionLogCaptor.getAllValues().stream().map(ActionLog::getEntityType).allMatch(EntityType.SECURED_VARIABLE::equals));
        assertTrue(actionLogCaptor.getAllValues().stream().map(ActionLog::getOperation).allMatch(LogOperation.UPDATE::equals));
        assertThat(actionLogCaptor.getAllValues().stream().map(ActionLog::getEntityName).collect(toSet()), equalTo(Set.of("foo", "baz")));
    }

    @DisplayName("updateVariables should call secret service's updateEntries method")
    @Test
    public void updateVariablesShouldCallSecretServiceUpdateEntriesMethod() {
        Map<String, String> data = Map.of("foo", "bar", "baz", "qux");
        securedVariableService.updateVariables(DEFAULT_SECRET_NAME, data);
        verify(secretService, times(1)).updateEntries(eq(DEFAULT_SECRET_NAME), eq(data));
    }

    @DisplayName("importVariablesRequest should add variables to the default secret")
    @Test
    public void importVariablesRequestShouldAddVariablesToDefaultSecret() {
        String fileContent = "a: b\nc: d\n";
        MultipartFile multipartFile = new MockMultipartFile("variables", "variables.yml", "application/yaml", fileContent.getBytes());
        SecuredVariableService spy = Mockito.spy(securedVariableService);
        assertThat(spy.importVariablesRequest(multipartFile), equalTo(Set.of("a", "c")));
        verify(spy, times(1)).addVariables(eq(DEFAULT_SECRET_NAME), eq(Map.of("a", "b", "c", "d")), eq(true));
    }
}
