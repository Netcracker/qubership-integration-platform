package org.qubership.integration.platform.camelk.naming.strategies;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.camelk.model.BuildInfo;
import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.qubership.integration.platform.camelk.model.options.ResourceBuildOptions;
import org.qubership.integration.platform.camelk.naming.NamingStrategy;
import org.qubership.integration.platform.camelk.naming.validation.K8sNameValidator;
import org.qubership.integration.platform.camelk.naming.validation.K8sNameVerifier;
import org.qubership.integration.platform.camelk.sources.IntegrationServiceCatalog;
import org.qubership.integration.platform.chain.model.Chain;
import org.qubership.integration.platform.chain.model.Snapshot;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HttpRoutePrivateNamingStrategyTest {

    @Test
    void proposesNameWithDefaultSuffix() {
        assertEquals("my-domain-v1-abc1234-chain-private-routes", strategy("-chain-private-routes").getName(context()));
    }

    private HttpRoutePrivateNamingStrategy strategy(String suffix) {
        NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy =
                context -> "my-domain-v1";
        return new HttpRoutePrivateNamingStrategy(
                new K8sNameVerifier(),
                new K8sNameValidator(),
                integrationResourceNamingStrategy,
                seed -> "abc1234",
                suffix);
    }

    private ResourceBuildContext<Snapshot> context() {
        Chain chain = mock(Chain.class);
        when(chain.getId()).thenReturn("chain-1");
        Snapshot snapshot = mock(Snapshot.class);
        when(snapshot.getChain()).thenReturn(chain);
        return ResourceBuildContext.create(
                BuildInfo.builder().options(ResourceBuildOptions.builder().name("my-domain").build()).build(),
                mock(IntegrationServiceCatalog.class)
        ).updateTo(snapshot);
    }
}
