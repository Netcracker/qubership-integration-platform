package org.qubership.integration.platform.runtime.catalog.cr;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.runtime.catalog.consul.ConsulService;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.CamelKIntegration;
import org.qubership.integration.platform.runtime.catalog.kubernetes.KubeOperator;
import org.qubership.integration.platform.runtime.catalog.model.consul.KeyResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Objects.isNull;

/**
 * Deletes the Consul key through which the pods of a micro domain elect their scheduler leader, once the domain is
 * gone. A leader leaves its key in place when it stops, so the next leader can fire what it missed, and the pods of
 * a deleted domain stop only after the catalog has deleted the domain. A key of this blue-green version goes only
 * while no session holds it and no Integration runs its domain, and only if nothing wrote to it after it was read.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cip.deploy.micro", name = "enabled", havingValue = "true")
@Profile("!development")
public class SchedulerLeaderKeysCleanup {
    private static final long CLEANUP_INTERVAL_MILLIS = 5L * 60 * 1000;
    private static final String ENGINE_DOMAIN_VARIABLE = "CIP_ENGINE_DOMAIN=";

    private final ConsulService consulService;
    private final KubeOperator kubeOperator;

    @Value("${cip.cr.labels.bg-version}")
    String bgVersionLabel;

    @Value("${spring.application.deployment_version}")
    String bgVersion;

    @Autowired
    public SchedulerLeaderKeysCleanup(ConsulService consulService, KubeOperator kubeOperator) {
        this.consulService = consulService;
        this.kubeOperator = kubeOperator;
    }

    @Scheduled(fixedDelay = CLEANUP_INTERVAL_MILLIS, initialDelay = CLEANUP_INTERVAL_MILLIS)
    public void deleteKeysOfDeletedDomains() {
        try {
            Set<String> keysInUse = kubeOperator.getIntegrationsByLabels(Map.of(bgVersionLabel, bgVersion)).stream()
                    .map(SchedulerLeaderKeysCleanup::engineDomain)
                    .flatMap(Optional::stream)
                    .map(this::keyName)
                    .collect(Collectors.toSet());
            for (KeyResponse key : consulService.getSchedulerLeaderKeys()) {
                String name = StringUtils.substringAfterLast(key.getKey(), "/");
                if (name.endsWith("-" + bgVersion) && isNull(key.getSession()) && !keysInUse.contains(name)
                        && consulService.deleteSchedulerLeaderKey(key)) {
                    log.info("Deleted the scheduler leader key {} of a deleted micro domain", name);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to delete the scheduler leader keys of deleted micro domains: {}", e.getMessage());
        }
    }

    private static Optional<String> engineDomain(CamelKIntegration integration) {
        return Optional.ofNullable(integration.getSpec())
                .map(CamelKIntegration.IntegrationSpec::getTraits)
                .map(CamelKIntegration.IntegrationSpec.Traits::getEnvironment)
                .map(CamelKIntegration.IntegrationSpec.Traits.EnvironmentTrait::getVars)
                .stream()
                .flatMap(Collection::stream)
                .filter(variable -> variable.startsWith(ENGINE_DOMAIN_VARIABLE))
                .map(variable -> variable.substring(ENGINE_DOMAIN_VARIABLE.length()))
                .findFirst();
    }

    // The name micro-engine's SchedulerLeaderElectionService gives the key, after its ConsulKeyValidator.
    private String keyName(String domain) {
        return (domain + "-" + bgVersion).replaceAll("[^a-zA-Z0-9\\-~]", "_");
    }
}
