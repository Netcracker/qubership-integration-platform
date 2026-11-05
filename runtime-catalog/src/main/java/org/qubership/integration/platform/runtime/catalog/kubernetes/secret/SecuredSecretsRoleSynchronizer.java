package org.qubership.integration.platform.runtime.catalog.kubernetes.secret;

import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.RbacAuthorizationV1Api;
import io.kubernetes.client.openapi.models.V1PolicyRule;
import io.kubernetes.client.openapi.models.V1Role;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.qubership.integration.platform.runtime.catalog.service.variables.secrets.K8sSecretService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Keeps the {@code resourceNames} of the shared secured variables Role equal to the names of the
 * secured variables secrets. Every scheduled run lists the secrets and compares them with the Role,
 * so the runs also write the Role at startup, retry a failed write, pick up secrets created or
 * deleted outside the catalog, and restore rules that someone else has changed. The catalog chart
 * declares the Role without rules, so a Helm or ArgoCD sync leaves them alone. {@link K8sSecretService} runs the sync right after it creates a secret,
 * so that the engines can read the new secret without waiting for the next run.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "kubernetes", name = "devmode", havingValue = "false", matchIfMissing = true)
public class SecuredSecretsRoleSynchronizer {
    static final int MAX_CONFLICT_ATTEMPTS = 3;

    private static final String SECRETS_RESOURCE = "secrets";
    private static final int CONFLICT = 409;
    private static final int NOT_FOUND = 404;

    private final KubeSecretOperator kubeSecretOperator;
    private final RbacAuthorizationV1Api rbacApi;
    private final Pair<String, String> securedLabel;
    private final String namespace;
    private final String secretsRoleName;

    public SecuredSecretsRoleSynchronizer(
            KubeSecretOperator kubeSecretOperator,
            RbacAuthorizationV1Api rbacApi,
            @Value("${kubernetes.variables-secret.label}") String label,
            @Value("${kubernetes.cluster.namespace}") String namespace,
            @Value("${cip.variables.secrets-role.name}") String secretsRoleName
    ) {
        this.kubeSecretOperator = kubeSecretOperator;
        this.rbacApi = rbacApi;
        this.securedLabel = Pair.of(label, K8sSecretService.SECURED_LABEL_VALUE);
        this.namespace = namespace;
        this.secretsRoleName = secretsRoleName;
    }

    /**
     * Has no initial delay, so the first run starts together with the catalog and a restarted
     * catalog writes the Role at once.
     */
    @Scheduled(fixedDelayString = "${cip.variables.secrets-role.sync-delay}")
    public void scheduledSync() {
        syncSecretsRole();
    }

    /**
     * Writes the full set of secret names, so that every catalog replica writes the same result for
     * the same secrets. The scheduler thread and the request threads that create secrets both call
     * this method, so it is synchronized. It does not throw: a failed run is logged, and the next
     * scheduled run repeats it.
     *
     * <p>The secrets are listed after the Role is read, and the Role is replaced with the
     * {@code resourceVersion} it was read with. A replica that writes the Role in between makes the
     * replace fail with 409, so a list older than that write never reaches the Role. On a 409 the
     * sync reads the Role and lists the secrets again, up to {@link #MAX_CONFLICT_ATTEMPTS} times.
     */
    public synchronized void syncSecretsRole() {
        try {
            for (int attempt = 1; attempt <= MAX_CONFLICT_ATTEMPTS; attempt++) {
                try {
                    replaceRoleIfChanged();
                    return;
                } catch (ApiException e) {
                    if (e.getCode() != CONFLICT) {
                        throw e;
                    }
                    log.debug("Role {} changed while it was being updated, attempt {} of {}",
                            secretsRoleName, attempt, MAX_CONFLICT_ATTEMPTS);
                }
            }
            log.warn("Role {} changed during each of {} updates. The next sync retries the update",
                    secretsRoleName, MAX_CONFLICT_ATTEMPTS);
        } catch (ApiException e) {
            if (e.getCode() == NOT_FOUND) {
                log.warn("Role {} not found in namespace {}. The engines cannot read the secured variables secrets",
                        secretsRoleName, namespace);
            } else {
                log.error("Failed to update Role {} with the secured variables secrets: {}",
                        secretsRoleName, e.getResponseBody(), e);
            }
        } catch (Exception e) {
            log.error("Failed to update Role {} with the secured variables secrets", secretsRoleName, e);
        }
    }

    private void replaceRoleIfChanged() throws ApiException {
        V1Role role = rbacApi.readNamespacedRole(secretsRoleName, namespace).execute();
        Set<String> secretNames = kubeSecretOperator.getAllSecretsWithLabel(securedLabel).keySet();
        List<V1PolicyRule> currentRules = Optional.ofNullable(role.getRules()).orElse(Collections.emptyList());
        Optional<List<V1PolicyRule>> newRules = rulesWithSecretNames(currentRules, secretNames);
        if (newRules.isEmpty()) {
            return;
        }
        role.setRules(newRules.get());
        rbacApi.replaceNamespacedRole(secretsRoleName, namespace, role).execute();
        log.info("Set secured variables secrets {} on Role {}", new TreeSet<>(secretNames), secretsRoleName);
    }

    /**
     * Returns the rules of the Role with {@code secretNames} as the {@code resourceNames} of every
     * rule on secrets, or an empty {@code Optional} when the rules already grant exactly these names.
     * When {@code secretNames} is empty, the rules on secrets are removed, because a rule with no
     * {@code resourceNames} grants access to every secret in the namespace. When the rules have no
     * rule on secrets and {@code secretNames} is not empty, a rule with the {@code get} verb is added.
     * Rules on other resources are kept as they are.
     */
    static Optional<List<V1PolicyRule>> rulesWithSecretNames(List<V1PolicyRule> rules, Set<String> secretNames) {
        List<String> sortedNames = secretNames.stream().sorted().toList();
        List<V1PolicyRule> result = new ArrayList<>();
        boolean changed = false;
        boolean hasSecretsRule = false;
        for (V1PolicyRule rule : rules) {
            if (!isSecretsRule(rule)) {
                result.add(rule);
                continue;
            }
            hasSecretsRule = true;
            if (sortedNames.isEmpty()) {
                changed = true;
                continue;
            }
            Set<String> currentNames = Optional.ofNullable(rule.getResourceNames())
                    .<Set<String>>map(HashSet::new)
                    .orElse(Collections.emptySet());
            if (!currentNames.equals(secretNames)) {
                rule.setResourceNames(new ArrayList<>(sortedNames));
                changed = true;
            }
            result.add(rule);
        }
        if (!hasSecretsRule && !sortedNames.isEmpty()) {
            result.add(new V1PolicyRule()
                    .apiGroups(List.of(""))
                    .resources(List.of(SECRETS_RESOURCE))
                    .resourceNames(new ArrayList<>(sortedNames))
                    .verbs(List.of("get")));
            changed = true;
        }
        return changed ? Optional.of(result) : Optional.empty();
    }

    private static boolean isSecretsRule(V1PolicyRule rule) {
        return rule.getResources() != null && rule.getResources().contains(SECRETS_RESOURCE);
    }
}
