package org.qubership.integration.platform.engine.controlplane;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "qip.control-plane")
public interface ControlPlaneServiceProperties {
    EgressProperties egress();

    RoutesProperties routes();

    interface EgressProperties {
        String name();

        String virtualService();

        @WithDefault("false")
        Boolean enableInsecureTls();
    }

    interface RoutesProperties {
        RegistrationProperties registration();

        String prefix();
    }

    interface RegistrationProperties {
        @WithDefault("true")
        Boolean enabled();
    }
}
