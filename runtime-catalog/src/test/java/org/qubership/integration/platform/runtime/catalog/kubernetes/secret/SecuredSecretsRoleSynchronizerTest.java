package org.qubership.integration.platform.runtime.catalog.kubernetes.secret;

import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.RbacAuthorizationV1Api;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1PolicyRule;
import io.kubernetes.client.openapi.models.V1Role;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.kubernetes.KubeApiException;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Covers how {@link SecuredSecretsRoleSynchronizer} sets the names of the secured variables
 * secrets on the shared Role: the rules it writes, when it skips the write, and how it handles a
 * concurrent update and a failed read.
 */
@ExtendWith(MockitoExtension.class)
class SecuredSecretsRoleSynchronizerTest {
    private static final String NAMESPACE = "cip";
    private static final String ROLE_NAME = "cip-secured-variables-reader";
    private static final String LABEL = "cip-variable-type";
    private static final Pair<String, String> SECURED_LABEL = Pair.of(LABEL, "secured");

    private SecuredSecretsRoleSynchronizer synchronizer;

    @Mock
    private KubeSecretOperator kubeSecretOperator;
    @Mock
    private RbacAuthorizationV1Api rbacApi;
    @Mock
    private RbacAuthorizationV1Api.APIreadNamespacedRoleRequest readRoleRequest;
    @Mock
    private RbacAuthorizationV1Api.APIreplaceNamespacedRoleRequest replaceRoleRequest;

    @BeforeEach
    void setUp() {
        synchronizer = new SecuredSecretsRoleSynchronizer(kubeSecretOperator, rbacApi, LABEL, NAMESPACE, ROLE_NAME);
    }

    @Test
    void replaceSecretNamesInRulesReplacesTheNamesOfTheSecretsRuleAndKeepsOtherRules() {
        V1PolicyRule rolesRule = rolesRule();
        List<V1PolicyRule> rules = List.of(secretsRule("default-secret"), rolesRule);

        Optional<List<V1PolicyRule>> result =
                SecuredSecretsRoleSynchronizer.replaceSecretNamesInRules(rules, Set.of("second", "default-secret"));

        assertTrue(result.isPresent());
        assertEquals(List.of(secretsRule("default-secret", "second"), rolesRule), result.get());
    }

    @Test
    void replaceSecretNamesInRulesReturnsEmptyWhenTheRuleAlreadyGrantsTheSameNamesInAnotherOrder() {
        List<V1PolicyRule> rules = List.of(secretsRule("second", "default-secret"), rolesRule());

        Optional<List<V1PolicyRule>> result =
                SecuredSecretsRoleSynchronizer.replaceSecretNamesInRules(rules, Set.of("default-secret", "second"));

        assertTrue(result.isEmpty());
    }

    @Test
    void replaceSecretNamesInRulesRemovesTheSecretsRuleWhenNoSecretIsLeft() {
        V1PolicyRule rolesRule = rolesRule();
        List<V1PolicyRule> rules = List.of(secretsRule("default-secret"), rolesRule);

        Optional<List<V1PolicyRule>> result = SecuredSecretsRoleSynchronizer.replaceSecretNamesInRules(rules, Set.of());

        assertTrue(result.isPresent());
        assertEquals(List.of(rolesRule), result.get());
    }

    @Test
    void replaceSecretNamesInRulesNarrowsASecretsRuleWithoutResourceNames() {
        List<V1PolicyRule> rules = List.of(new V1PolicyRule()
                .apiGroups(List.of(""))
                .resources(List.of("secrets"))
                .verbs(List.of("get")));

        Optional<List<V1PolicyRule>> result =
                SecuredSecretsRoleSynchronizer.replaceSecretNamesInRules(rules, Set.of("default-secret"));

        assertTrue(result.isPresent());
        assertEquals(List.of(secretsRule("default-secret")), result.get());
    }

    @Test
    void replaceSecretNamesInRulesAddsASecretsRuleWhenTheRoleHasNone() {
        V1PolicyRule rolesRule = rolesRule();

        Optional<List<V1PolicyRule>> result =
                SecuredSecretsRoleSynchronizer.replaceSecretNamesInRules(List.of(rolesRule), Set.of("default-secret"));

        assertTrue(result.isPresent());
        assertEquals(List.of(rolesRule, secretsRule("default-secret")), result.get());
    }

    @Test
    void replaceSecretNamesInRulesReturnsEmptyWhenNoSecretsRuleAndNoSecrets() {
        Optional<List<V1PolicyRule>> result =
                SecuredSecretsRoleSynchronizer.replaceSecretNamesInRules(List.of(rolesRule()), Set.of());

        assertTrue(result.isEmpty());
    }

    @Test
    void syncReplacesTheRoleThatWasReadWhenTheNamesDiffer() throws ApiException {
        stubSecrets("default-secret", "second");
        V1Role role = role(secretsRule("default-secret"), rolesRule());
        when(readRoleRequest.execute()).thenReturn(role);
        when(rbacApi.replaceNamespacedRole(ROLE_NAME, NAMESPACE, role)).thenReturn(replaceRoleRequest);

        synchronizer.syncSecretsRole();

        verify(replaceRoleRequest).execute();
        assertEquals("42", role.getMetadata().getResourceVersion());
        assertEquals(List.of(secretsRule("default-secret", "second"), rolesRule()), role.getRules());
    }

    @Test
    void syncDoesNotReplaceTheRoleWhenTheNamesAreTheSame() throws ApiException {
        stubSecrets("default-secret");
        when(readRoleRequest.execute()).thenReturn(role(secretsRule("default-secret"), rolesRule()));

        synchronizer.syncSecretsRole();

        verify(rbacApi, never()).replaceNamespacedRole(any(), any(), any());
    }

    @Test
    void syncReadsTheRoleAgainWhenAnotherReplicaUpdatedItFirst() throws ApiException {
        stubSecrets("default-secret", "second");
        V1Role staleRole = role(secretsRule("default-secret"), rolesRule());
        V1Role freshRole = role(secretsRule("default-secret", "second"), rolesRule());
        when(readRoleRequest.execute()).thenReturn(staleRole, freshRole);
        when(rbacApi.replaceNamespacedRole(ROLE_NAME, NAMESPACE, staleRole)).thenReturn(replaceRoleRequest);
        when(replaceRoleRequest.execute()).thenThrow(new ApiException(409, "Conflict"));

        synchronizer.syncSecretsRole();

        verify(readRoleRequest, times(2)).execute();
        verify(rbacApi, times(1)).replaceNamespacedRole(any(), any(), any());
    }

    /**
     * Replica A lists the secrets before replica B creates {@code second} and writes it to the
     * Role. The replace of A fails with 409, and the retry must list the secrets again rather than
     * write the list that misses {@code second}.
     */
    @Test
    void syncListsTheSecretsAgainAfterAConflict() throws ApiException {
        when(rbacApi.readNamespacedRole(ROLE_NAME, NAMESPACE)).thenReturn(readRoleRequest);
        doReturn(secrets("default-secret", "third"), secrets("default-secret", "second", "third"))
                .when(kubeSecretOperator).getAllSecretsWithLabel(SECURED_LABEL);
        V1Role readByA = role(secretsRule("default-secret"), rolesRule());
        V1Role writtenByB = role(secretsRule("default-secret", "second"), rolesRule());
        when(readRoleRequest.execute()).thenReturn(readByA, writtenByB);
        when(rbacApi.replaceNamespacedRole(eq(ROLE_NAME), eq(NAMESPACE), any())).thenReturn(replaceRoleRequest);
        when(replaceRoleRequest.execute()).thenThrow(new ApiException(409, "Conflict")).thenReturn(writtenByB);

        synchronizer.syncSecretsRole();

        verify(kubeSecretOperator, times(2)).getAllSecretsWithLabel(SECURED_LABEL);
        verify(rbacApi).replaceNamespacedRole(ROLE_NAME, NAMESPACE, writtenByB);
        assertEquals(List.of(secretsRule("default-secret", "second", "third"), rolesRule()), writtenByB.getRules());
    }

    @Test
    void syncStopsAfterTheLastConflictAttemptWithoutThrowing() throws ApiException {
        stubSecrets("default-secret", "second");
        when(readRoleRequest.execute()).thenAnswer(invocation -> role(secretsRule("default-secret"), rolesRule()));
        when(rbacApi.replaceNamespacedRole(eq(ROLE_NAME), eq(NAMESPACE), any())).thenReturn(replaceRoleRequest);
        when(replaceRoleRequest.execute()).thenThrow(new ApiException(409, "Conflict"));

        assertDoesNotThrow(() -> synchronizer.syncSecretsRole());

        verify(replaceRoleRequest, times(SecuredSecretsRoleSynchronizer.MAX_CONFLICT_ATTEMPTS)).execute();
    }

    @Test
    void syncDoesNotReplaceTheRoleWhenTheSecretsCannotBeListed() throws ApiException {
        when(rbacApi.readNamespacedRole(ROLE_NAME, NAMESPACE)).thenReturn(readRoleRequest);
        when(readRoleRequest.execute()).thenReturn(role(secretsRule("default-secret"), rolesRule()));
        when(kubeSecretOperator.getAllSecretsWithLabel(SECURED_LABEL))
                .thenThrow(new KubeApiException("API server unavailable"));

        assertDoesNotThrow(() -> synchronizer.syncSecretsRole());

        verify(rbacApi, never()).replaceNamespacedRole(any(), any(), any());
    }

    private void stubSecrets(String... names) {
        doReturn(secrets(names)).when(kubeSecretOperator).getAllSecretsWithLabel(SECURED_LABEL);
        when(rbacApi.readNamespacedRole(ROLE_NAME, NAMESPACE)).thenReturn(readRoleRequest);
    }

    private static Map<String, Map<String, String>> secrets(String... names) {
        Map<String, Map<String, String>> secrets = new HashMap<>();
        for (String name : names) {
            secrets.put(name, Map.of());
        }
        return secrets;
    }

    private static V1Role role(V1PolicyRule... rules) {
        return new V1Role()
                .metadata(new V1ObjectMeta().name(ROLE_NAME).resourceVersion("42"))
                .rules(new ArrayList<>(List.of(rules)));
    }

    private static V1PolicyRule secretsRule(String... names) {
        return new V1PolicyRule()
                .apiGroups(List.of(""))
                .resources(List.of("secrets"))
                .resourceNames(new ArrayList<>(List.of(names)))
                .verbs(List.of("get"));
    }

    private static V1PolicyRule rolesRule() {
        return new V1PolicyRule()
                .apiGroups(List.of("rbac.authorization.k8s.io"))
                .resources(List.of("roles"))
                .resourceNames(List.of(ROLE_NAME))
                .verbs(List.of("get"));
    }
}
