package org.qubership.integration.platform.camelk.naming.strategies;

import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.qubership.integration.platform.camelk.naming.NamingStrategy;
import org.qubership.integration.platform.camelk.naming.validation.K8sNameValidator;
import org.qubership.integration.platform.camelk.naming.validation.K8sNameVerifier;
import org.qubership.integration.platform.chain.model.Snapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Function;

@Component("httpRoutePublicNamingStrategy")
public class HttpRoutePublicNamingStrategy extends ChainHttpRouteNamingStrategy {
    @Autowired
    public HttpRoutePublicNamingStrategy(
            K8sNameVerifier nameVerifier,
            K8sNameValidator nameValidator,

            @Qualifier("integrationResourceNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy,

            @Qualifier("suffixGenerator")
            Function<Long, String> suffixGenerator,

            @Value("${cip.cr.naming.http-route.public-suffix:-chain-public-routes}")
            String suffix
    ) {
        super(nameVerifier, nameValidator, integrationResourceNamingStrategy, suffixGenerator, suffix);
    }
}
