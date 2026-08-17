package org.qubership.integration.platform.runtime.catalog.cr.naming.strategies;

import org.qubership.integration.platform.runtime.catalog.cr.ResourceBuildContext;
import org.qubership.integration.platform.runtime.catalog.cr.naming.NamingStrategy;
import org.qubership.integration.platform.runtime.catalog.cr.naming.validation.K8sNameValidator;
import org.qubership.integration.platform.runtime.catalog.cr.naming.validation.K8sNameVerifier;
import org.qubership.integration.platform.runtime.catalog.cr.naming.validation.K8sNames;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component("httpRouteEgressNamingStrategy")
public class HttpRouteEgressNamingStrategy extends K8sResourceNamingStrategy<ResourceBuildContext<List<Snapshot>>> {
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy;
    private final K8sNameValidator nameValidator;
    private final String suffix;

    @Autowired
    public HttpRouteEgressNamingStrategy(
            K8sNameVerifier nameVerifier,
            K8sNameValidator nameValidator,

            @Qualifier("integrationResourceNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy,

            @Value("${qip.cr.naming.http-route.egress-suffix:-egress-routes}")
            String suffix
    ) {
        super(nameVerifier);
        this.integrationResourceNamingStrategy = integrationResourceNamingStrategy;
        this.nameValidator = nameValidator;
        this.suffix = suffix;
    }

    @Override
    protected String proposeName(ResourceBuildContext<List<Snapshot>> context) {
        String base = integrationResourceNamingStrategy.getName(context);
        // Reserve room for the full suffix before truncating, so a long base name can never cut
        // into the suffix, the same way the public/private tiers already guard against it.
        int maxBaseLength = K8sNames.K8S_RESOURCE_NAME_LENGTH_LIMIT - suffix.length();
        if (maxBaseLength > 0 && base.length() > maxBaseLength) {
            base = base.substring(0, maxBaseLength);
        }
        return nameValidator.validate(base + suffix);
    }
}
