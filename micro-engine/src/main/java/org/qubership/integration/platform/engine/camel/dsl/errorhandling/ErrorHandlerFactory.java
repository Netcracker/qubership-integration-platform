package org.qubership.integration.platform.engine.camel.dsl.errorhandling;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.spi.Resource;
import org.qubership.integration.platform.engine.configuration.camel.StartupErrorHandlingConfiguration;

@Slf4j
@Unremovable
@ApplicationScoped
public class ErrorHandlerFactory {
    @Inject
    StartupErrorHandlingConfiguration configuration;

    public ErrorHandler getErrorHandler(Resource resource) {
        return (context, exception) -> {
            if (configuration.ignoreRouteLoadingErrors()) {
                log.error("Failed to load routes from {}: {}", resource.getURL(), exception.getMessage());
            } else {
                throw exception;
            }
        };
    }
}
