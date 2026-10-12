package org.qubership.integration.platform.camelk.naming.strategies;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.camelk.model.BuildInfo;
import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.qubership.integration.platform.camelk.model.options.ResourceBuildOptions;
import org.qubership.integration.platform.camelk.naming.generator.StringGenerator;
import org.qubership.integration.platform.camelk.naming.validation.K8sNameValidator;
import org.qubership.integration.platform.camelk.naming.validation.K8sNameVerifier;
import org.qubership.integration.platform.camelk.naming.validation.K8sNames;
import org.qubership.integration.platform.camelk.sources.IntegrationServiceCatalog;
import org.qubership.integration.platform.chain.model.Chain;
import org.qubership.integration.platform.chain.model.Snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChainHttpRouteNamingStrategyTest {

    private static final String SUFFIX = "-chain-public-routes";

    @Test
    void keepsTheNameOfAChainAcrossItsSnapshots() {
        HttpRoutePublicNamingStrategy strategy = strategy("my-domain-v1");

        assertEquals(
                strategy.getName(context(snapshot("snapshot-1", "chain-1"))),
                strategy.getName(context(snapshot("snapshot-2", "chain-1"))));
    }

    @Test
    void givesEveryChainItsOwnName() {
        HttpRoutePublicNamingStrategy strategy = strategy("my-domain-v1");

        assertNotEquals(
                strategy.getName(context(snapshot("snapshot-1", "chain-1"))),
                strategy.getName(context(snapshot("snapshot-2", "chain-2"))));
    }

    @Test
    void truncatesTheIntegrationNameRatherThanTheSuffix() {
        String name = strategy("a".repeat(K8sNames.K8S_RESOURCE_NAME_LENGTH_LIMIT))
                .getName(context(snapshot("snapshot-1", "chain-1")));

        assertEquals(K8sNames.K8S_RESOURCE_NAME_LENGTH_LIMIT, name.length());
        assertTrue(name.endsWith(SUFFIX), name);
    }

    private HttpRoutePublicNamingStrategy strategy(String integrationName) {
        StringGenerator generator = new StringGenerator();
        return new HttpRoutePublicNamingStrategy(
                new K8sNameVerifier(),
                new K8sNameValidator(),
                context -> integrationName,
                seed -> generator.generate(7, seed),
                SUFFIX);
    }

    private Snapshot snapshot(String snapshotId, String chainId) {
        Chain chain = mock(Chain.class);
        when(chain.getId()).thenReturn(chainId);
        Snapshot snapshot = mock(Snapshot.class);
        when(snapshot.getId()).thenReturn(snapshotId);
        when(snapshot.getChain()).thenReturn(chain);
        return snapshot;
    }

    private ResourceBuildContext<Snapshot> context(Snapshot snapshot) {
        return ResourceBuildContext.create(
                BuildInfo.builder().options(ResourceBuildOptions.builder().name("my-domain").build()).build(),
                mock(IntegrationServiceCatalog.class)
        ).updateTo(snapshot);
    }
}
