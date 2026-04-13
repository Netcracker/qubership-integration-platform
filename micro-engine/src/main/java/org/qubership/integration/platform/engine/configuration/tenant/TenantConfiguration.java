package org.qubership.integration.platform.engine.configuration.tenant;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class TenantConfiguration {
    @Getter
    @ConfigProperty(name = "tenant.default.id")
    @NotBlank
    String defaultTenant;
}
