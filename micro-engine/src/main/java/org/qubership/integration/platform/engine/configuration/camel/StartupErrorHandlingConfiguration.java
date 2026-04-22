package org.qubership.integration.platform.engine.configuration.camel;

import io.smallrye.config.ConfigMapping;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ConfigMapping(prefix = "qip.camel.startup.error-handling")
public interface StartupErrorHandlingConfiguration {
    @ConfigProperty(defaultValue = "false")
    boolean ignoreVariablesErrors();

    @ConfigProperty(defaultValue = "false")
    boolean ignoreRouteLoadingErrors();
}
