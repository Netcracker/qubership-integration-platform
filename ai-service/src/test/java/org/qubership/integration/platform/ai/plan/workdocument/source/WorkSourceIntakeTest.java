package org.qubership.integration.platform.ai.plan.workdocument.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import org.qubership.integration.platform.ai.plan.workdocument.WorkCommit;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentService;
import org.qubership.integration.platform.ai.plan.workdocument.WorkOutcome;
import org.qubership.integration.platform.ai.productpipeline.store.CommandPayloadConflictException;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunTransition;
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
  void firstAcceptReplayReturnsTheCommittedReceipt() {
    storage.put("uploads/orders.md", "Map status to Not Started");
    SourceBatch batch =
        new SourceBatch(
            List.of(),
            List.of(new SourceFile("uploads/orders.md", "orders.md", "MAP-1", SourceRole.MAPPING)),
            List.of());
    intake.accept(RUN_ID, "doc-sources", batch, "cmd-first");
    StoredSource mapping = source(intake.read(RUN_ID), "MAP-1");
    ProductPipelineRunDocument committed = runStore.load(RUN_ID).orElseThrow();
    RunTransition transition =
        committed.transitions().stream()
            .filter(candidate -> "cmd-first".equals(candidate.commandId()))
            .findFirst()
            .orElseThrow();
    WorkCommit first = documents.committedResult(committed, transition);
    int attempts = committed.attempts().size();
    long runRevision = committed.run().runRevision();

    intake.accept(RUN_ID, "doc-sources", batch, "cmd-first");

    ProductPipelineRunDocument replayed = runStore.load(RUN_ID).orElseThrow();
    WorkCommit second = documents.committedResult(replayed, transition);
    assertEquals(first, second);
    assertEquals(WorkOutcome.PREPARED, second.outcome());
    assertTrue(second.acceptedRecordIds().contains(mapping.id()));
    assertEquals(attempts, replayed.attempts().size());
    assertEquals(runRevision, replayed.run().runRevision());
  }

  @Test
  void laterMappingConflictDoesNotKeepTheEarlierRequirement() {
    storage.put("uploads/first.md", "Retry twice");
    intake.accept(
        RUN_ID,
        "doc-sources",
        new SourceBatch(
            List.of(),
            List.of(new SourceFile("uploads/first.md", "first.md", "MAP-A", null)),
            List.of()),
        "cmd-first");
    assertTrue(
        intake.read(RUN_ID).requirements().stream()
            .anyMatch(requirement -> "Retry twice".equals(requirement.text())));

    storage.put("uploads/second.txt", "Do not retry");
    SourceInventory conflict =
        intake.accept(
            RUN_ID,
            "doc-sources",
            new SourceBatch(
                List.of(),
                List.of(new SourceFile("uploads/second.txt", "second.txt", "MAP-B", null)),
                List.of()),
            "cmd-second");

    assertTrue(conflict.requirements().isEmpty());
    assertEquals(1, conflict.questions().size());
    assertTrue(conflict.questions().getFirst().question().contains("MAP-A"));
    assertTrue(conflict.questions().getFirst().question().contains("MAP-B"));
    assertEquals("Retry twice", source(conflict, "MAP-A").originalText());
    assertEquals("Do not retry", source(conflict, "MAP-B").originalText());
  }

  @Test
  void correctionReplayReturnsTheCommittedReceiptAndRejectsADifferentPayload() {
    storage.put("uploads/orders.md", "Status is Open");
    intake.accept(
        RUN_ID,
        "doc-sources",
        new SourceBatch(
            List.of(),
            List.of(new SourceFile("uploads/orders.md", "orders.md", "MAP-1", SourceRole.MAPPING)),
            List.of()),
        "cmd-original");
    SourceBatch correction =
        new SourceBatch(
            List.of(), List.of(), List.of(new SourceCorrection("Status is Closed", "MAP-1")));
    intake.accept(RUN_ID, "doc-sources", correction, "cmd-correction");
    ProductPipelineRunDocument committed = runStore.load(RUN_ID).orElseThrow();
    RunTransition transition =
        committed.transitions().stream()
            .filter(candidate -> "cmd-correction".equals(candidate.commandId()))
            .findFirst()
            .orElseThrow();
    WorkCommit first = documents.committedResult(committed, transition);
    StoredSource correctionSource =
        intake.read(RUN_ID).sources().stream()
            .filter(source -> source.role() == SourceRole.CORRECTION)
            .findFirst()
            .orElseThrow();
    int attempts = committed.attempts().size();
    long runRevision = committed.run().runRevision();

    intake.accept(RUN_ID, "doc-sources", correction, "cmd-correction");

    ProductPipelineRunDocument replayed = runStore.load(RUN_ID).orElseThrow();
    WorkCommit second = documents.committedResult(replayed, transition);
    assertEquals(first, second);
    assertEquals(WorkOutcome.PREPARED, second.outcome());
    assertEquals(Map.of(), second.aliasToId());
    assertTrue(second.acceptedRecordIds().contains(correctionSource.id()));
    assertEquals(attempts, replayed.attempts().size());
    assertEquals(runRevision, replayed.run().runRevision());
    assertThrows(
        CommandPayloadConflictException.class,
        () ->
            intake.accept(
                RUN_ID,
                "doc-sources",
                new SourceBatch(
                    List.of(),
                    List.of(),
                    List.of(new SourceCorrection("Status is Pending", "MAP-1"))),
                "cmd-correction"));
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
  void markdownSourceKeepsTheOriginalTextAndIndexesPassages() {
    String text = "# Request\n\nMap the order id.\n\n# Failure\n\nReturn the error.\n";
    storage.put("uploads/orders.md", text);

    SourceInventory inventory =
        intake.accept(
            RUN_ID,
            "doc-sources",
            new SourceBatch(
                List.of(),
                List.of(new SourceFile("uploads/orders.md", "orders.md", "MAP-1", SourceRole.MAPPING)),
                List.of()),
            "cmd-passages");

    StoredSource mapping = source(inventory, "MAP-1");
    assertEquals(text, mapping.originalText());
    assertEquals(text, mapping.content());
    assertEquals(2, mapping.passages().size());
    assertEquals("Map the order id.", mapping.passages().get(0).text());
    assertEquals("Request", mapping.passages().get(0).parentHeading());
    assertEquals(mapping.id(), mapping.passages().get(0).sourceId());
    assertEquals("Return the error.", mapping.passages().get(1).text());
    assertEquals("Failure", mapping.passages().get(1).parentHeading());
    assertFalse(mapping.passages().get(0).id().equals(mapping.passages().get(1).id()));
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
