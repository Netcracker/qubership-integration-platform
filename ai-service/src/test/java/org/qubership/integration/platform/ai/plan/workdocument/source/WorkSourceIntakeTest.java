package org.qubership.integration.platform.ai.plan.workdocument.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentService;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.StageSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;

class WorkSourceIntakeTest {

  private static final Instant FIXED = Instant.parse("2026-09-24T12:00:00Z");
  private static final String RUN_ID = "run-sources-1";

  private InMemoryArtifactBlobStore blobStore;
  private CompilationArtifacts artifacts;
  private ProductPipelineRunStore runStore;
  private WorkDocumentService documents;
  private Map<String, String> storage;
  private WorkSourceIntake intake;

  @BeforeEach
  void setUp() {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    blobStore = new InMemoryArtifactBlobStore();
    Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);
    artifacts = new CompilationArtifacts(blobStore, mapper, clock);
    runStore = new ProductPipelineRunStore(blobStore, mapper, clock);
    documents = new WorkDocumentService(runStore, artifacts, mapper);
    runStore.create(
        new RunSnapshot(
            RUN_ID,
            "conversation-sources-1",
            1L,
            RunStatus.RUNNING,
            "LOGICAL_FLOW",
            List.of(new StageSnapshot("LOGICAL_FLOW", StageStatus.RUNNING, List.of(), null)),
            null));
    storage = new LinkedHashMap<>();
    intake = new WorkSourceIntake(documents, runStore, artifacts, storage::get);
  }

  @Test
  void mappingFileIsNotASpecificationImport() {
    storage.put("uploads/orders.md", "Map status to Not Started");
    storage.put("uploads/orders-api.yaml", "openapi: 3.0.0");

    SourceInventory inventory =
        intake.accept(
            RUN_ID,
            "doc-sources",
            new SourceBatch(
                List.of(),
                List.of(
                    new SourceFile("uploads/orders.md", "orders.md", "MAP-1", null),
                    new SourceFile("uploads/orders-api.yaml", "orders-api.yaml", "", null)),
                List.of()),
            "cmd-roles");

    assertEquals(List.of("uploads/orders-api.yaml"), inventory.specificationImportKeys());
    StoredSource mapping = source(inventory, "MAP-1");
    assertEquals(SourceRole.MAPPING, mapping.role());
    assertEquals("Map status to Not Started", mapping.originalText());
    assertEquals(SourceRole.API_SPECIFICATION, byName(inventory, "orders-api.yaml").role());
  }

  @Test
  void explicitCorrectionReplacesInterpretationAndKeepsOriginalEvidence() {
    storage.put("uploads/orders.md", "Status is Open");
    intake.accept(
        RUN_ID,
        "doc-sources",
        new SourceBatch(
            List.of(new SourceNote("Status is Open", SourceRole.MESSAGE)),
            List.of(new SourceFile("uploads/orders.md", "orders.md", "MAP-1", SourceRole.MAPPING)),
            List.of()),
        "cmd-original");

    SourceInventory corrected =
        intake.accept(
            RUN_ID,
            "doc-sources",
            new SourceBatch(
                List.of(),
                List.of(),
                List.of(new SourceCorrection("Status is Closed", "MAP-1"))),
            "cmd-correction");

    StoredSource original = source(corrected, "MAP-1");
    assertEquals("Status is Open", original.originalText());
    assertEquals("uploads/orders.md", original.contentReference());
    StoredRequirement current =
        corrected.requirements().stream()
            .filter(requirement -> "Status is Closed".equals(requirement.text()))
            .findFirst()
            .orElseThrow();
    assertFalse(current.supersededRequirementId().isBlank());
    assertTrue(
        corrected.requirements().stream()
            .anyMatch(requirement -> requirement.id().equals(current.supersededRequirementId())));
    assertTrue(
        corrected.sources().stream().anyMatch(source -> source.role() == SourceRole.CORRECTION));
    assertTrue(
        corrected.sources().stream()
            .filter(source -> source.role() == SourceRole.CORRECTION)
            .anyMatch(source -> source.correctionOf().contains(original.id())));
  }

  @Test
  void uploadOrderDoesNotChooseBetweenConflictingSources() {
    storage.put("uploads/first.md", "Retry twice");
    storage.put("uploads/second.txt", "Do not retry");

    SourceInventory inventory =
        intake.accept(
            RUN_ID,
            "doc-sources",
            new SourceBatch(
                List.of(),
                List.of(
                    new SourceFile("uploads/first.md", "first.md", "MAP-A", null),
                    new SourceFile("uploads/second.txt", "second.txt", "MAP-B", null)),
                List.of()),
            "cmd-conflict");

    assertEquals(2, inventory.sources().stream().filter(source -> source.role() == SourceRole.MAPPING).count());
    assertTrue(inventory.requirements().isEmpty());
    assertEquals(1, inventory.questions().size());
    String question = inventory.questions().getFirst().question();
    assertTrue(question.contains("MAP-A"));
    assertTrue(question.contains("MAP-B"));
    assertFalse(question.contains("second.txt") && question.contains("authoritative") && !question.contains("MAP-A"));
  }

  @Test
  void suppliedMappingIdentifierAndOriginalTextSurviveRestartAndRepair() {
    storage.put("uploads/orders.md", "Map orderId from the trigger");
    intake.accept(
        RUN_ID,
        "doc-sources",
        new SourceBatch(
            List.of(),
            List.of(new SourceFile("uploads/orders.md", "orders.md", "MAP-9", SourceRole.MAPPING)),
            List.of()),
        "cmd-mapping");

    WorkSourceIntake restarted =
        new WorkSourceIntake(documents, runStore, artifacts, reference -> null);
    SourceInventory afterRestart = restarted.read(RUN_ID);
    StoredSource mapping = source(afterRestart, "MAP-9");
    assertEquals("Map orderId from the trigger", mapping.originalText());
    assertEquals("uploads/orders.md", mapping.contentReference());

    SourceInventory repaired =
        restarted.accept(
            RUN_ID,
            "doc-sources",
            new SourceBatch(
                List.of(),
                List.of(),
                List.of(new SourceCorrection("Map orderId from the header", "MAP-9"))),
            "cmd-repair");

    StoredSource kept = source(repaired, "MAP-9");
    assertEquals("Map orderId from the trigger", kept.originalText());
    assertEquals(mapping.contentHash(), kept.contentHash());
    assertEquals("MAP-9", kept.suppliedIdentifier());
  }

  @Test
  void unsupportedFormatStaysVisibleAndIsNotEmptyMappingInput() {
    storage.put("uploads/rules.pdf", "");

    SourceInventory inventory =
        intake.accept(
            RUN_ID,
            "doc-sources",
            new SourceBatch(
                List.of(),
                List.of(new SourceFile("uploads/rules.pdf", "rules.pdf", "MAP-PDF", null)),
                List.of()),
            "cmd-pdf");

    StoredSource file = source(inventory, "MAP-PDF");
    assertEquals(SourceRole.UNSUPPORTED, file.role());
    assertEquals("uploads/rules.pdf", file.contentReference());
    assertFalse(file.readerLimitation().isBlank());
    assertTrue(inventory.requirements().isEmpty());
    assertTrue(inventory.specificationImportKeys().isEmpty());
    assertTrue(inventory.questions().stream().anyMatch(question -> question.question().contains("rules.pdf")));
  }

  private static StoredSource source(SourceInventory inventory, String suppliedIdentifier) {
    return inventory.sources().stream()
        .filter(source -> suppliedIdentifier.equals(source.suppliedIdentifier()))
        .findFirst()
        .orElseThrow();
  }

  private static StoredSource byName(SourceInventory inventory, String originalName) {
    return inventory.sources().stream()
        .filter(source -> originalName.equals(source.originalName()))
        .findFirst()
        .orElseThrow();
  }

}
