package org.qubership.integration.platform.ai.plan.mapping;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.qubership.integration.platform.ai.compiler.CompilerSkillContextBuilder;
import org.qubership.integration.platform.ai.compiler.CompilerSkillDocument;
import org.qubership.integration.platform.ai.compiler.CompilerSkillInputSnapshot;
import org.qubership.integration.platform.ai.compiler.CompilerSkillRuntimeEligibility;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonContext;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonRepository;
import org.qubership.integration.platform.ai.compiler.catalog.CompilerSkillCatalog;
import org.qubership.integration.platform.ai.compiler.policy.CompilerGeneratorSpecIndex;
import org.qubership.integration.platform.ai.plan.mapping.envelope.JsonSchemaMessageSchemaFactory;
import org.qubership.integration.platform.ai.plan.mapping.envelope.MappingEnvelope;
import org.qubership.integration.platform.ai.plan.mapping.schema.MappingSchemaSide;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.productpipeline.knowledge.FakeKnowledgeClient;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingRuleStatus;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackRepository;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackVersion;
import org.qubership.integration.platform.ai.qipknowledge.skill.QipKnowledgeCapabilityPhase;

class MappingGeneratorContextTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private CompilerSkillContextBuilder builder;
  private MappingSchemaSide sourceSide;
  private MappingSchemaSide targetSide;
  private MappingEnvelope envelope;

  @BeforeEach
  void setUp() throws Exception {
    QipKnowledgePackRepository repository = Mockito.mock(QipKnowledgePackRepository.class);
    CompilerSkillAddonRepository addonRepository = Mockito.mock(CompilerSkillAddonRepository.class);
    when(addonRepository.loadForSkill(Mockito.anyString()))
        .thenReturn(CompilerSkillAddonContext.empty());
    when(repository.loadCompilerGeneratorSpecIndex())
        .thenReturn(new CompilerGeneratorSpecIndex(List.of()));
    when(repository.loadCompilerSkillCatalog()).thenReturn(new CompilerSkillCatalog(List.of()));

    builder =
        new CompilerSkillContextBuilder(
            MAPPER,
            repository,
            addonRepository,
            Mockito.mock(CompilerSkillRuntimeEligibility.class),
            FakeKnowledgeClient.defaultFixture(),
            FakeKnowledgeClient.defaultFixture());

    JsonNode orderSchema =
        MAPPER.readTree(
            """
            {
              "type": "object",
              "properties": { "orderId": { "type": "string" } },
              "required": ["orderId"]
            }
            """);
    sourceSide = side("trigger-http", MappingPort.OUTPUT, orderSchema, "sha-source-artifact");
    targetSide = side("call-1", MappingPort.REQUEST, orderSchema, "sha-target-artifact");
    envelope = new JsonSchemaMessageSchemaFactory(MAPPER).fromSides(sourceSide, targetSide);
  }

  @Test
  void userMessageContainsHashesAndIdToPath() {
    MappingIntent intent = identityOrderId();
    String mappingContext =
        builder.renderMappingGenerationContext(intent, envelope, sourceSide, targetSide);
    CompilerSkillDocument document = transformationGeneratorDocument();
    CompilerSkillInputSnapshot snapshot =
        new CompilerSkillInputSnapshot(
            "Map order id",
            "Copy orderId to the service call",
            "",
            sampleGraph(),
            "",
            null,
            mappingContext);

    String message = builder.buildUserMessage(document, snapshot);

    assertTrue(message.contains("Mapping generation"));
    assertTrue(message.contains(sourceSide.sha256()));
    assertTrue(message.contains("$.orderId"));
    assertTrue(message.contains("must be copied unchanged"));
  }

  private static MappingIntent identityOrderId() {
    return new MappingIntent(
        "map-init",
        "trigger-http",
        MappingPort.OUTPUT,
        "call-1",
        MappingPort.REQUEST,
        List.of(
            new MappingIntentRule(
                "$.orderId", "$.orderId", null, MappingRuleStatus.USER_DEFINED)));
  }

  private static MappingSchemaSide side(
      String serviceCallId, MappingPort direction, JsonNode schema, String sha256) {
    return new MappingSchemaSide(
        "1",
        serviceCallId,
        "op-1",
        direction,
        "application/json",
        null,
        sha256,
        "test-provenance",
        schema);
  }

  private static CompilerSkillDocument transformationGeneratorDocument() {
    return new CompilerSkillDocument(
        "cip-transformation-generator",
        "cip-transformation-generator",
        "skills/cip-transformation-generator.md",
        "Transformation generator",
        QipKnowledgeCapabilityPhase.GENERATOR,
        true,
        new QipKnowledgePackVersion("test", "test-pack"),
        "generator-id: GEN-TRANSFORM\n");
  }

  private static ChainPlanGraph sampleGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("chain-1", "Chain"),
        List.of(new ChainPlanNode("mapper-1", "mapper-2", "Mapper", null, null, List.of())),
        List.of());
  }
}
