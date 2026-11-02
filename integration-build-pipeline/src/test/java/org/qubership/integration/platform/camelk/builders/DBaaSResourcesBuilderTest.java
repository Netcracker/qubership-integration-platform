package org.qubership.integration.platform.camelk.builders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.github.jknack.handlebars.EscapingStrategy;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.io.ClassPathTemplateLoader;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.camelk.model.BuildInfo;
import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.qubership.integration.platform.camelk.model.options.DBaaSOperatorType;
import org.qubership.integration.platform.camelk.model.options.DBaaSOptions;
import org.qubership.integration.platform.camelk.model.options.ResourceBuildOptions;
import org.qubership.integration.platform.camelk.naming.validation.K8sNameValidator;
import org.qubership.integration.platform.camelk.sources.IntegrationServiceCatalog;
import org.qubership.integration.platform.chain.model.Snapshot;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class DBaaSResourcesBuilderTest {

    // MicroDomainService.delete() finds a domain's custom resources by this label. Without it,
    // deleting a domain left both InternalDatabase resources behind.
    @Test
    void everyInternalDatabaseCarriesTheIntegrationLabel() throws Exception {
        Handlebars handlebars = new Handlebars()
                .with(new ClassPathTemplateLoader("/cr/templates", ".hbs"))
                .with(EscapingStrategy.NOOP);
        handlebars.prettyPrint(true);
        DBaaSResourcesBuilder builder = new DBaaSResourcesBuilder(
                handlebars, ctx -> "my-domain-v1", ctx -> "my-domain-integration", new K8sNameValidator());
        ReflectionTestUtils.setField(builder, "domainLabel", "my-domain-label");
        ResourceBuildContext<List<Snapshot>> context = ResourceBuildContext.create(
                BuildInfo.builder().options(ResourceBuildOptions.builder()
                        .name("my-domain")
                        .dbaas(DBaaSOptions.builder().operatorType(DBaaSOperatorType.DBAAS).build())
                        .build()).build(),
                mock(IntegrationServiceCatalog.class)
        ).updateTo(Collections.<Snapshot>emptyList());

        List<JsonNode> documents = new YAMLMapper()
                .readerFor(JsonNode.class)
                .<JsonNode>readValues(builder.build(context))
                .readAll()
                .stream()
                .filter(document -> !document.isEmpty())
                .toList();

        assertEquals(2, documents.size());
        for (JsonNode document : documents) {
            assertEquals("my-domain-integration",
                    document.at("/metadata/labels/camel.apache.org~1integration").asText());
        }
    }
}
