package org.qubership.integration.platform.engine.configuration.persistence;

import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "qip.postgres")
public interface PostgresProperties {
    ConnectionProperties serviceDb();

    ConnectionProperties schedulerDb();

    interface ConnectionProperties {
        String url();

        String username();

        String password();

        String role();
    }
}
