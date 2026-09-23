package org.qubership.integration.platform.ai.plan.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.mapping.envelope.JsonSchemaMessageSchemaFactory;
import org.qubership.integration.platform.ai.plan.mapping.envelope.MappingEnvelope;
import org.qubership.integration.platform.ai.plan.mapping.schema.MappingSchemaSide;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;

class DirectFieldMappingScriptTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void projectsApprovedBodyCopyWithoutTrustingAClaimedScript() throws Exception {
    MappingIntent intent = intent(new MappingIntentRule("title", "Subject", null));
    DirectFieldMappingScript.Generated generated =
        DirectFieldMappingScript.from(intent, envelope()).orElseThrow();

    assertEquals(List.of("$.Subject"), generated.mappingCoverage());
    assertTrue(generated.script().contains("exchange.in.body = ['Subject': source['title']]"));
    assertFalse(generated.script().contains("headers"));
    SecureGroovyMappingCompiler.compile(generated.script());
  }

  @Test
  void leavesExpressionsAndNestedPathsToTheScriptGenerator() throws Exception {
    assertTrue(
        DirectFieldMappingScript.from(
                intent(new MappingIntentRule("title", "Subject", "uppercase")), envelope())
            .isEmpty());
    assertTrue(
        DirectFieldMappingScript.from(
                intent(new MappingIntentRule("person.title", "Subject", null)), envelope())
            .isEmpty());
  }

  @Test
  void projectsAnApprovedCopyWhenHttpBodySchemasAreUnknown() {
    MappingSchemaSide source =
        new MappingSchemaSide("1", "inbound", null, MappingPort.OUTPUT, null, null, null,
            null, null);
    MappingSchemaSide target =
        new MappingSchemaSide("1", "outbound", null, MappingPort.REQUEST, null, null, null,
            null, null);
    MappingEnvelope unknown = new JsonSchemaMessageSchemaFactory(JSON).fromSides(source, target);

    DirectFieldMappingScript.Generated generated =
        DirectFieldMappingScript.from(
                intent(new MappingIntentRule("title", "Subject", null)), unknown)
            .orElseThrow();

    assertEquals(List.of("$.Subject"), generated.mappingCoverage());
    assertTrue(generated.script().contains("exchange.in.body = ['Subject': source['title']]"));
  }

  private static MappingIntent intent(MappingIntentRule rule) {
    return new MappingIntent(
        "map-title-subject", "inbound", MappingPort.OUTPUT, "outbound", MappingPort.REQUEST,
        List.of(rule));
  }

  private static MappingEnvelope envelope() throws Exception {
    JsonNode source = JSON.readTree("""
        {"type":"object","properties":{"title":{"type":"string"}}}
        """);
    JsonNode target = JSON.readTree("""
        {"type":"object","properties":{"Subject":{"type":"string"}}}
        """);
    MappingSchemaSide sourceSide =
        new MappingSchemaSide("1", "inbound", "op", MappingPort.OUTPUT, "application/json", null,
            "source-hash", "test", source);
    MappingSchemaSide targetSide =
        new MappingSchemaSide("1", "outbound", "op", MappingPort.REQUEST, "application/json", null,
            "target-hash", "test", target);
    return new JsonSchemaMessageSchemaFactory(JSON).fromSides(sourceSide, targetSide);
  }
}
