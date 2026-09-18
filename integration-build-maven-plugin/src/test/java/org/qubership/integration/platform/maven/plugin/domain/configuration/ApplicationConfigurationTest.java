package org.qubership.integration.platform.maven.plugin.domain.configuration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.camelk.builders.EngineRoutesResourceBuilder;
import org.qubership.integration.platform.camelk.builders.chain.EgressRouteResourceBuilder;
import org.qubership.integration.platform.camelk.builders.chain.HttpRouteResourceBuilder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApplicationConfigurationTest {
    private static final String MESH_TYPE_PROPERTY = "qip.control-plane.mesh-type";
    private static final String ISTIO_ENABLED_PROPERTY = "qip.istio.enabled";

    @AfterEach
    void clearProperties() {
        System.clearProperty(MESH_TYPE_PROPERTY);
        System.clearProperty(ISTIO_ENABLED_PROPERTY);
    }

    /**
     * The route builders this module subclasses register themselves when the mesh-type properties are
     * set. Both are readable from the Maven JVM's system properties, so without the scan exclusion the
     * context would hold a base class next to its subclass and the build would fail on a duplicate
     * resource. One bean of each type means the exclusion held.
     */
    @Test
    void registersOneRouteBuilderPerTypeEvenWhenTheMeshPropertiesAreSet() {
        System.setProperty(MESH_TYPE_PROPERTY, "Istio");
        System.setProperty(ISTIO_ENABLED_PROPERTY, "true");

        try (AnnotationConfigApplicationContext context =
                 new AnnotationConfigApplicationContext(ApplicationConfiguration.class)) {
            assertEquals(1, context.getBeansOfType(HttpRouteResourceBuilder.class).size());
            assertEquals(1, context.getBeansOfType(EgressRouteResourceBuilder.class).size());
            assertEquals(1, context.getBeansOfType(EngineRoutesResourceBuilder.class).size());
        }
    }
}
