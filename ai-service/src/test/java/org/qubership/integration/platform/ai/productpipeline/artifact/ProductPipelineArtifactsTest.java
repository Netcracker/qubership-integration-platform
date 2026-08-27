package org.qubership.integration.platform.ai.productpipeline.artifact;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

class ProductPipelineArtifactsTest {

  private static final String RUN_ID = "run-1";
  private static final Instant FIXED_INSTANT = Instant.parse("2026-07-22T09:00:00Z");

  private CompilationArtifacts artifacts;
  private ProductPipelineArtifactStore store;

  @BeforeEach
  void setUp() {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    artifacts =
        new CompilationArtifacts(
            new InMemoryArtifactBlobStore(),
            mapper,
            Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
    store = new ProductPipelineArtifactStore(artifacts);
  }

  @Test
  void appendsRequirementBriefWithProvenanceRoundTrip() {
    ArtifactProvenance provenance =
        new ArtifactProvenance(
            "run-1",
            "requirement-analysis",
            "create-plan",
            "1",
            "profile-sha256",
            "requirement-analysis",
            "1",
            "closure-sha256");
    RequirementBrief brief =
        new RequirementBrief(
            "greet users",
            List.of("name"),
            List.of("no auth"),
            List.of(),
            List.of(),
            "summary");

    Revision revision =
        store.append(
            new AppendCommand(
                RUN_ID,
                Kind.REQUIREMENT_BRIEF,
                "1",
                "requirement-analysis",
                "1",
                brief,
                List.of(),
                null,
                provenance));

    Revision reloaded =
        artifacts.get(RUN_ID, revision.reference()).orElseThrow();
    assertEquals(provenance, reloaded.provenance());
    assertEquals(brief, artifacts.payload(reloaded, RequirementBrief.class));
  }

  @Test
  void roundTripsRunManifest() {
    RunManifest manifest = sampleRunManifest();
    Revision revision = append(Kind.RUN_MANIFEST, manifest);
    assertEquals(manifest, artifacts.payload(revision, RunManifest.class));
  }

  @Test
  void roundTripsUserInput() {
    UserInput input =
        new UserInput("input-1", "collect", "add negative constraint", FIXED_INSTANT);
    Revision revision = append(Kind.USER_INPUT, input);
    assertEquals(input, artifacts.payload(revision, UserInput.class));
  }

  @Test
  void roundTripsApprovalRecordWithExactHash() {
    Revision brief =
        append(
            Kind.REQUIREMENT_BRIEF,
            new RequirementBrief("goal", List.of(), List.of(), List.of(), List.of(), "s"));
    ApprovalRecord approval =
        new ApprovalRecord(
            brief.reference(),
            brief.contentHash(),
            "user-1",
            "looks good",
            FIXED_INSTANT);

    Revision revision = append(Kind.APPROVAL_RECORD, approval, List.of(brief.reference()));
    ApprovalRecord reloaded = artifacts.payload(revision, ApprovalRecord.class);

    assertEquals(brief.reference(), reloaded.target());
    assertEquals(brief.contentHash(), reloaded.targetContentHash());
    assertEquals(approval, reloaded);
  }

  @Test
  void roundTripsApprovalRecordV2WithFullSemanticPin() {
    Revision brief =
        append(
            Kind.REQUIREMENT_BRIEF,
            new RequirementBrief("goal", List.of(), List.of(), List.of(), List.of(), "s"));
    ApprovalRecordV2 approval =
        new ApprovalRecordV2(
            brief.reference(),
            brief.contentHash(),
            List.of(brief.reference()),
            "user-1",
            "looks good",
            FIXED_INSTANT,
            null,
            null,
            Kind.CHAIN_SEMANTIC_REVISION.name(),
            "chain-semantic-revision/v1",
            "revision-1",
            "ab".repeat(32),
            "create-chain-compiler-contract/v1",
            "cd".repeat(32));

    Revision revision = append(Kind.APPROVAL_RECORD, approval, List.of(brief.reference()));
    ApprovalRecordV2 reloaded = artifacts.payload(revision, ApprovalRecordV2.class);

    assertEquals(approval, reloaded);
    assertEquals(Kind.CHAIN_SEMANTIC_REVISION.name(), reloaded.subjectArtifactKind());
    assertEquals("ab".repeat(32), reloaded.subjectSha256());
    assertEquals("cd".repeat(32), reloaded.compilerContractSha256());
  }

  @Test
  void roundTripsRunManifestWithFullCompilerRunPin() {
    CompilerRunPin pin =
        new CompilerRunPin(
            "compiler-v2",
            "1.0.0",
            "pkg-digest",
            2,
            "v1_0_0",
            "index-digest",
            new ResolvedCompilerDag(List.of(), List.of(), "dag-digest"),
            List.of("cip-structure-generator"),
            Map.of("cip-structure-generator", "skill-sha"),
            Map.of("cip-structure-generator", "addon-sha"),
            List.of(new ArtifactTypeRef("graph-assembly-result", 1)),
            Kind.CHAIN_SEMANTIC_REVISION.name(),
            "chain-semantic-revision/v1",
            "revision-1",
            "ab".repeat(32),
            "create-chain-compiler-contract/v1",
            "cd".repeat(32));
    RunManifest manifest =
        new RunManifest(
            RUN_ID,
            null,
            List.of(),
            "product",
            "create-chain",
            "2",
            "profile-sha256",
            "cip-compiler-v2",
            "baseline-sha256",
            List.of(new DependencyClosureEntry("requirement-analysis", "1", "cap-sha256")),
            "closure-sha256",
            new KnowledgePackageRef(
                "knowledge-artifact-1",
                "2026.7.1",
                "1.0.0",
                "checksum-sha256",
                "CERTIFIED",
                "sha256:certificate"),
            "24.4",
            List.of(new ArtifactTypeRef("user-input", 1)),
            pin);
    Revision revision = append(Kind.RUN_MANIFEST, manifest);
    RunManifest reloaded = artifacts.payload(revision, RunManifest.class);
    assertEquals(manifest, reloaded);
    assertEquals(pin, reloaded.compilerRunPin());
    assertEquals("ab".repeat(32), reloaded.compilerRunPin().subjectSha256());
  }

  @Test
  void roundTripsIdsBypass() {
    IdsBypass bypass = new IdsBypass("first-slice-no-ids", "create-plan", "1");
    Revision revision = append(Kind.IDS_BYPASS, bypass);
    assertEquals(bypass, artifacts.payload(revision, IdsBypass.class));
  }

  @Test
  void planValidationResultBlocksApprovalWhenFindingIsBlocker() {
    PlanValidationResult blocked =
        new PlanValidationResult(
            List.of(
                new PlanValidationFinding("GAP", "missing route", true),
                new PlanValidationFinding("NOTE", "style", false)));
    PlanValidationResult eligible =
        new PlanValidationResult(
            List.of(new PlanValidationFinding("NOTE", "style", false)));

    assertFalse(blocked.approvalEligible());
    assertTrue(eligible.approvalEligible());

    Revision revision = append(Kind.PLAN_VALIDATION_RESULT, blocked);
    assertEquals(blocked, artifacts.payload(revision, PlanValidationResult.class));
    assertFalse(artifacts.payload(revision, PlanValidationResult.class).approvalEligible());
  }

  @Test
  void roundTripsFailureRecord() {
    FailureRecord failure =
        new FailureRecord(
            FailureClass.TECHNICAL,
            "planning",
            "attempt-1",
            "timeout talking to sidecar",
            true);
    Revision revision = append(Kind.FAILURE_RECORD, failure);
    assertEquals(failure, artifacts.payload(revision, FailureRecord.class));
  }

  @Test
  void productStoreRejectsNullProvenance() {
    AppendCommand command =
        new AppendCommand(
            RUN_ID,
            Kind.USER_INPUT,
            "1",
            "test",
            "1",
            new UserInput("i1", "collect", "hi", FIXED_INSTANT),
            List.of(),
            null);

    assertNull(command.provenance());
    assertThrows(IllegalArgumentException.class, () -> store.append(command));
  }

  @Test
  void createChainArtifactKindsAreDeclared() {
    assertEquals(Kind.ELEMENT_SKELETON, Kind.valueOf("ELEMENT_SKELETON"));
    assertEquals(Kind.NAMING_MANIFEST, Kind.valueOf("NAMING_MANIFEST"));
    assertEquals(Kind.CONFIGURED_TRIGGER_SET, Kind.valueOf("CONFIGURED_TRIGGER_SET"));
    assertEquals(Kind.CHAIN_STRUCTURE, Kind.valueOf("CHAIN_STRUCTURE"));
    assertEquals(Kind.GRAPH_PATCH_ARTIFACT, Kind.valueOf("GRAPH_PATCH_ARTIFACT"));
    assertEquals(Kind.GRAPH_ASSEMBLY_RESULT, Kind.valueOf("GRAPH_ASSEMBLY_RESULT"));
    assertEquals(Kind.COMPILER_VALIDATION_BUNDLE, Kind.valueOf("COMPILER_VALIDATION_BUNDLE"));
    assertEquals(Kind.MATERIALIZATION_CHECKPOINT, Kind.valueOf("MATERIALIZATION_CHECKPOINT"));
    assertEquals(Kind.MATERIALIZATION_RESULT, Kind.valueOf("MATERIALIZATION_RESULT"));
    assertEquals(Kind.CATALOG_CHAIN_SNAPSHOT, Kind.valueOf("CATALOG_CHAIN_SNAPSHOT"));
    assertEquals(Kind.RECONCILE_RESULT, Kind.valueOf("RECONCILE_RESULT"));
  }

  private Revision append(Kind kind, Object payload) {
    return append(kind, payload, List.of());
  }

  private Revision append(
      Kind kind, Object payload, List<CompilationArtifacts.Reference> inputs) {
    ArtifactProvenance provenance =
        new ArtifactProvenance(
            RUN_ID,
            "stage",
            "create-plan",
            "1",
            "profile-sha256",
            "capability",
            "1",
            "closure-sha256");
    return store.append(
        new AppendCommand(
            RUN_ID, kind, "1", "test-producer", "1", payload, inputs, null, provenance));
  }

  private static RunManifest sampleRunManifest() {
    return new RunManifest(
        RUN_ID,
        null,
        List.of(),
        "product",
        "create-plan",
        "1",
        "profile-sha256",
        "cip-compiler-v2",
        "baseline-sha256",
        List.of(new DependencyClosureEntry("requirement-analysis", "1", "cap-sha256")),
        "closure-sha256",
        new KnowledgePackageRef(
            "knowledge-artifact-1",
            "2026.7.1",
            "1.0.0",
            "checksum-sha256",
            "CERTIFIED",
            "sha256:certificate"),
        "24.4",
        List.of(new ArtifactTypeRef("user-input", 1), new ArtifactTypeRef("fake-plan", 1)),
        null);
  }
}
