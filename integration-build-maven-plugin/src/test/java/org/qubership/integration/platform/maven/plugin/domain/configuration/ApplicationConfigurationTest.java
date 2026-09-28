package org.qubership.integration.platform.maven.plugin.domain.configuration;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.camelk.builders.EngineRoutesResourceBuilder;
import org.qubership.integration.platform.camelk.builders.chain.EgressRouteResourceBuilder;
import org.qubership.integration.platform.camelk.builders.chain.HttpRouteResourceBuilder;
import org.qubership.integration.platform.camelk.locations.LibraryLocationGetter;
import org.qubership.integration.platform.io.readers.migrations.ImportFileMigration;
import org.qubership.integration.platform.io.readers.migrations.chain.ChainImportFileMigration;
import org.qubership.integration.platform.io.readers.migrations.versions.VersionsGetterService;
import org.qubership.integration.platform.maven.plugin.domain.services.TemplateBasedLibraryLocationGetter;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

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

    /**
     * {@code AssumeActualVersion} answers for any document, so it has to run after every strategy that
     * reads the document's own metadata. Asked first, it would report a {@code fileVersion} export as
     * current and the reader would skip its migrations.
     */
    @Test
    void asksTheAssumeActualVersionFallbackAfterTheFileVersionStrategy() throws Exception {
        try (AnnotationConfigApplicationContext context =
                 new AnnotationConfigApplicationContext(ApplicationConfiguration.class)) {
            VersionsGetterService versionsGetterService = context.getBean(VersionsGetterService.class);
            List<Integer> chainMigrationVersions = context.getBeansOfType(ChainImportFileMigration.class)
                .values().stream()
                .map(ImportFileMigration::getVersion)
                .sorted()
                .toList();
            ObjectNode fileVersionDocument = JsonNodeFactory.instance.objectNode().put("fileVersion", 2);
            ObjectNode documentWithoutMetadata = JsonNodeFactory.instance.objectNode();

            assertEquals(List.of(1, 2), List.copyOf(versionsGetterService.getVersions(fileVersionDocument)));
            assertEquals(chainMigrationVersions,
                versionsGetterService.getVersions(documentWithoutMetadata).stream().sorted().toList());
        }
    }

    /**
     * The shared module registers the catalog getter too. The plugin's template getter has to win the
     * injection, or the library URLs ignore {@code libraryUrlTemplate}.
     */
    @Test
    void resolvesTheLibraryLocationFromTheTemplate() {
        try (AnnotationConfigApplicationContext context =
                 new AnnotationConfigApplicationContext(ApplicationConfiguration.class)) {
            assertInstanceOf(TemplateBasedLibraryLocationGetter.class, context.getBean(LibraryLocationGetter.class));
        }
    }
}
