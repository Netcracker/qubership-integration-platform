package org.qubership.integration.platform.engine.configuration.k8s;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.util.Optional;

@ConfigMapping(prefix = "kubernetes")
public interface KubernetesProperties {
    ClusterProperties cluster();

    ServiceAccountProperties serviceAccount();

    VariablesSecretProperties variablesSecret();

    SecretsRoleProperties secretsRole();

    @WithName("devmode")
    @WithDefault("false")
    Boolean devMode();

    interface ClusterProperties {
        String uri();

        String namespace();

        @WithName("token")
        Optional<String> devToken();
    }

    interface ServiceAccountProperties {
        String tokenFilePath();

        String cert();
    }

    interface VariablesSecretProperties {
        String name();

        String label();
    }

    interface SecretsRoleProperties {
        String name();
    }
}
