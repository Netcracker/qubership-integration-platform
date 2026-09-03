package org.qubership.integration.platform.camelk.naming.strategies;

import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.qubership.integration.platform.camelk.naming.NamingStrategy;
import org.qubership.integration.platform.camelk.naming.validation.K8sNameValidator;
import org.qubership.integration.platform.camelk.naming.validation.K8sNameVerifier;
import org.qubership.integration.platform.camelk.naming.validation.K8sNames;
import org.qubership.integration.platform.chain.model.Snapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component("httpRoutePrivateNamingStrategy")
public class HttpRoutePrivateNamingStrategy extends K8sResourceNamingStrategy<ResourceBuildContext<List<Snapshot>>> {
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy;
    private final K8sNameValidator nameValidator;
    private final String suffix;

    @Autowired
    public HttpRoutePrivateNamingStrategy(
            K8sNameVerifier nameVerifier,
            K8sNameValidator nameValidator,

            @Qualifier("integrationResourceNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy,

            @Value("${qip.cr.naming.http-route.private-suffix:-chain-private-routes}")
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
        // into the suffix. Otherwise, for long enough base names, this tier's name and the public
        // tier's name (whose suffix first differs at character 8) would truncate to the identical
        // 63-character string and collide as the same Kubernetes object.
        int maxBaseLength = K8sNames.K8S_RESOURCE_NAME_LENGTH_LIMIT - suffix.length();
        if (maxBaseLength > 0 && base.length() > maxBaseLength) {
            base = base.substring(0, maxBaseLength);
        }
        return nameValidator.validate(base + suffix);
    }
}
