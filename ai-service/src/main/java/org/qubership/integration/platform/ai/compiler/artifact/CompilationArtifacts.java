package org.qubership.integration.platform.ai.compiler.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.qubership.integration.platform.ai.productpipeline.artifact.ArtifactProvenance;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;

/**
 * Owns immutable compiler artifact revisions, exact-content decisions, and lineage impact.
 *
 * <p>The module stores history as append-only JSON documents. A changed upstream artifact does
 * not mutate its descendants; {@link #changeImpact(String, Reference)} derives which descendants
 * are stale for the replacement revision.
 */
@ApplicationScoped
public class CompilationArtifacts {

  private static final String ROOT_PREFIX = "compiler-artifacts/";
  private static final Comparator<Revision> REVISION_ORDER =
      Comparator.comparingLong(Revision::sequence).thenComparing(Revision::artifactId);
  private static final Comparator<ArtifactDecision> DECISION_ORDER =
      Comparator.comparingLong(ArtifactDecision::sequence)
          .thenComparing(ArtifactDecision::decisionId);

  private final ArtifactBlobStore blobStore;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  @Inject
  CompilationArtifacts(S3ArtifactBlobStore blobStore, ObjectMapper objectMapper) {
    this(blobStore, canonicalMapper(objectMapper), Clock.systemUTC());
  }

  public CompilationArtifacts(
      ArtifactBlobStore blobStore, ObjectMapper objectMapper, Clock clock) {
    this.blobStore = Objects.requireNonNull(blobStore, "blobStore");
    this.objectMapper = canonicalMapper(Objects.requireNonNull(objectMapper, "objectMapper"));
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Appends an immutable revision after verifying every referenced input. */
  public Revision append(AppendCommand command) {
    Objects.requireNonNull(command, "command");
    validateAppendCommand(command);

    JsonNode payload = objectMapper.valueToTree(command.payload());
    String contentHash = sha256(write(payload));
    Instant createdAt = clock.instant();
    String artifactId = UUID.randomUUID().toString();
    String lineageId =
        Optional.ofNullable(command.revisesArtifactId())
            .flatMap(revisedId -> findById(command.compilationId(), revisedId))
            .map(Revision::lineageId)
            .orElseGet(() -> UUID.randomUUID().toString());
    // ponytail: Compilation writes are serialized per conversation today. Add a conditional S3
    // head object before supporting multiple writers for one compilation.
    long sequence =
        allRevisions(command.compilationId()).stream()
                .mapToLong(Revision::sequence)
                .max()
                .orElse(0L)
            + 1L;
    Revision revision =
        new Revision(
            artifactId,
            command.compilationId(),
            command.kind(),
            lineageId,
            sequence,
            command.schemaVersion(),
            createdAt,
            command.producerId(),
            command.producerVersion(),
            contentHash,
            command.inputs(),
            command.revisesArtifactId(),
            payload,
            command.provenance());
    blobStore.put(revisionKey(revision), write(revision));
    return revision;
  }

  /** Returns one exact artifact revision when both its ID and content hash match. */
  public Optional<Revision> get(String compilationId, Reference reference) {
    requireText(compilationId, "compilationId");
    Objects.requireNonNull(reference, "reference");
    return history(compilationId, reference.kind()).stream()
        .filter(revision -> revision.artifactId().equals(reference.artifactId()))
        .filter(revision -> revision.contentHash().equals(reference.contentHash()))
        .findFirst();
  }

  /** Returns the latest appended revision of one kind. */
  public Optional<Revision> latest(String compilationId, Kind kind) {
    return history(compilationId, kind).stream().max(REVISION_ORDER);
  }

  /** Returns all revisions of one kind in creation order. */
  public List<Revision> history(String compilationId, Kind kind) {
    requireText(compilationId, "compilationId");
    Objects.requireNonNull(kind, "kind");
    return readDocuments(revisionPrefix(compilationId, kind), Revision.class).stream()
        .sorted(REVISION_ORDER)
        .toList();
  }

  /** Records a human decision for one exact artifact revision. */
  public ArtifactDecision recordDecision(DecisionCommand command) {
    Objects.requireNonNull(command, "command");
    requireText(command.compilationId(), "compilationId");
    Objects.requireNonNull(command.target(), "target");
    Objects.requireNonNull(command.decision(), "decision");
    requireText(command.actor(), "actor");
    requireExistingReference(command.compilationId(), command.target(), "decision target");

    long sequence =
        decisions(command.compilationId()).stream()
                .mapToLong(ArtifactDecision::sequence)
                .max()
                .orElse(0L)
            + 1L;
    ArtifactDecision decision =
        new ArtifactDecision(
            UUID.randomUUID().toString(),
            command.compilationId(),
            sequence,
            command.target(),
            command.decision(),
            command.actor(),
            clock.instant(),
            command.comment());
    blobStore.put(decisionKey(decision), write(decision));
    return decision;
  }

  /** Returns whether the latest decision for the exact artifact content is approval. */
  public boolean isApproved(String compilationId, Reference reference) {
    requireText(compilationId, "compilationId");
    Objects.requireNonNull(reference, "reference");
    return decisions(compilationId).stream()
        .filter(decision -> decision.target().equals(reference))
        .max(DECISION_ORDER)
        .map(decision -> decision.decision() == Decision.APPROVED)
        .orElse(false);
  }

  /**
   * Returns transitive descendants that still consume the artifact replaced by {@code
   * replacement}.
   */
  public ChangeImpact changeImpact(String compilationId, Reference replacement) {
    Revision replacementRevision =
        get(compilationId, replacement)
            .orElseThrow(() -> new IllegalArgumentException("replacement artifact was not found"));
    List<Revision> revisions = allRevisions(compilationId);
    Set<String> affectedIds = new HashSet<>();
    ArrayDeque<String> queue = new ArrayDeque<>();
    revisions.stream()
        .filter(candidate -> candidate.lineageId().equals(replacementRevision.lineageId()))
        .filter(candidate -> !candidate.artifactId().equals(replacementRevision.artifactId()))
        .map(Revision::artifactId)
        .forEach(queue::addLast);
    while (!queue.isEmpty()) {
      String affectedInputId = queue.removeFirst();
      for (Revision candidate : revisions) {
        if (affectedIds.contains(candidate.artifactId())) {
          continue;
        }
        boolean consumesAffectedInput =
            candidate.inputs().stream()
                .anyMatch(input -> input.artifactId().equals(affectedInputId));
        if (consumesAffectedInput) {
          affectedIds.add(candidate.artifactId());
          queue.addLast(candidate.artifactId());
        }
      }
    }

    List<Revision> staleDescendants =
        revisions.stream()
            .filter(revision -> affectedIds.contains(revision.artifactId()))
            .sorted(REVISION_ORDER)
            .toList();
    return new ChangeImpact(replacement, staleDescendants);
  }

  /** Deserializes a stored payload into its domain type. */
  public <T> T payload(Revision revision, Class<T> payloadType) {
    Objects.requireNonNull(revision, "revision");
    Objects.requireNonNull(payloadType, "payloadType");
    if (revision.kind() == Kind.CHAIN_SEMANTIC_REVISION) {
      requireSemanticSchemaVersion(revision.schemaVersion());
      if (payloadType != ChainSemanticRevision.class) {
        throw new IllegalArgumentException(
            "CHAIN_SEMANTIC_REVISION payload decodes only as ChainSemanticRevision");
      }
    }
    return objectMapper.convertValue(revision.payload(), payloadType);
  }

  private void validateAppendCommand(AppendCommand command) {
    requireText(command.compilationId(), "compilationId");
    Objects.requireNonNull(command.kind(), "kind");
    requireText(command.schemaVersion(), "schemaVersion");
    requireText(command.producerId(), "producerId");
    Objects.requireNonNull(command.payload(), "payload");
    for (Reference input : command.inputs()) {
      requireExistingReference(command.compilationId(), input, "input");
    }
    if (command.revisesArtifactId() != null) {
      Revision revised =
          findById(command.compilationId(), command.revisesArtifactId())
              .orElseThrow(() -> new IllegalArgumentException("revised artifact was not found"));
      if (revised.kind() != command.kind()) {
        throw new IllegalArgumentException("a revision must keep the artifact kind");
      }
    }
    if (command.kind() == Kind.CHAIN_SEMANTIC_REVISION) {
      requireSemanticSchemaVersion(command.schemaVersion());
      try {
        objectMapper.convertValue(command.payload(), ChainSemanticRevision.class);
      } catch (IllegalArgumentException e) {
        throw e;
      } catch (Exception e) {
        throw new IllegalArgumentException(
            "CHAIN_SEMANTIC_REVISION payload must be a ChainSemanticRevision", e);
      }
    }
  }

  private static void requireSemanticSchemaVersion(String schemaVersion) {
    if (!ChainSemanticRevision.CURRENT_SCHEMA_VERSION.equals(schemaVersion)) {
      throw new IllegalArgumentException(
          "Unsupported semantic schema version: " + schemaVersion);
    }
  }

  private void requireExistingReference(
      String compilationId, Reference reference, String label) {
    Objects.requireNonNull(reference, label);
    if (get(compilationId, reference).isEmpty()) {
      throw new IllegalArgumentException(label + " does not match a stored artifact revision");
    }
  }

  private Optional<Revision> findById(String compilationId, String artifactId) {
    return allRevisions(compilationId).stream()
        .filter(revision -> revision.artifactId().equals(artifactId))
        .findFirst();
  }

  private List<Revision> allRevisions(String compilationId) {
    requireText(compilationId, "compilationId");
    return readDocuments(artifactPrefix(compilationId), Revision.class).stream()
        .sorted(REVISION_ORDER)
        .toList();
  }

  private List<ArtifactDecision> decisions(String compilationId) {
    return readDocuments(decisionPrefix(compilationId), ArtifactDecision.class).stream()
        .sorted(DECISION_ORDER)
        .toList();
  }

  private <T> List<T> readDocuments(String prefix, Class<T> type) {
    List<T> result = new ArrayList<>();
    for (String key : blobStore.list(prefix).stream().filter(k -> k.endsWith(".json")).toList()) {
      byte[] content =
          blobStore
              .get(key)
              .orElseThrow(
                  () ->
                      new IllegalStateException("artifact document disappeared: " + key));
      result.add(read(content, type));
    }
    return List.copyOf(result);
  }

  private byte[] write(Object value) {
    try {
      return objectMapper.writeValueAsBytes(value);
    } catch (Exception e) {
      throw new IllegalStateException("cannot serialize compiler artifact", e);
    }
  }

  private <T> T read(byte[] content, Class<T> type) {
    try {
      return objectMapper.readValue(content, type);
    } catch (Exception e) {
      throw new IllegalStateException("cannot deserialize compiler artifact", e);
    }
  }

  private static ObjectMapper canonicalMapper(ObjectMapper source) {
    return source.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
  }

  private static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
  }

  private static String revisionKey(Revision revision) {
    return revisionPrefix(revision.compilationId(), revision.kind())
        + timestamp(revision.createdAt())
        + "-"
        + revision.artifactId()
        + ".json";
  }

  private static String decisionKey(ArtifactDecision decision) {
    return decisionPrefix(decision.compilationId())
        + timestamp(decision.decidedAt())
        + "-"
        + decision.decisionId()
        + ".json";
  }

  private static String artifactPrefix(String compilationId) {
    return compilationPrefix(compilationId) + "artifacts/";
  }

  private static String revisionPrefix(String compilationId, Kind kind) {
    return artifactPrefix(compilationId) + kind.name().toLowerCase(Locale.ROOT) + "/";
  }

  private static String decisionPrefix(String compilationId) {
    return compilationPrefix(compilationId) + "decisions/";
  }

  private static String compilationPrefix(String compilationId) {
    String encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(compilationId.getBytes(StandardCharsets.UTF_8));
    return ROOT_PREFIX + encoded + "/";
  }

  private static String timestamp(Instant instant) {
    return String.format("%019d", instant.toEpochMilli());
  }

  public enum Kind {
    REQUIREMENT_DRAFT,
    CHAIN_PLAN_GRAPH,
    IMPLEMENTATION_PLAN,
    RUN_MANIFEST,
    USER_INPUT,
    APPROVAL_RECORD,
    REQUIREMENT_BRIEF,
    IDS_BYPASS,
    PLAN_VALIDATION_RESULT,
    FAILURE_RECORD,
    ELEMENT_SKELETON,
    NAMING_MANIFEST,
    CONFIGURED_TRIGGER_SET,
    CHAIN_STRUCTURE,
    GRAPH_PATCH_ARTIFACT,
    GRAPH_ASSEMBLY_RESULT,
    COMPILER_VALIDATION_BUNDLE,
    MATERIALIZATION_CHECKPOINT,
    MATERIALIZATION_RESULT,
    CATALOG_CHAIN_SNAPSHOT,
    RECONCILE_RESULT,
    DESIGN_MODE,
    DESIGN_ENTRY_ROUTE,
    IDS_DOCUMENT,
    NORMALIZED_DESIGN_FLOW,
    CHAIN_SEMANTIC_REVISION,
    CATALOG_BINDING_HINT,
    DESIGN_PLAN_REPORT,
    DESIGN_EXECUTION_PLAN,
    CATALOG_BINDING_RESOLUTIONS,
    EXECUTION_TRACE,
    API_OPERATION_BINDINGS,
    ORDERED_GRAPH_PATCHES,
    EXECUTOR_VALIDATION_BUNDLE,
    VALIDATED_EXECUTION_BUNDLE,
    MATERIALIZATION_REQUEST,
    DESIGN_EXECUTION_CHECKPOINT,
    DESIGN_EXECUTION_RESULT
  }

  public enum Decision {
    APPROVED,
    REJECTED
  }

  public record Reference(Kind kind, String artifactId, String contentHash) {

    public Reference {
      Objects.requireNonNull(kind, "kind");
      requireText(artifactId, "artifactId");
      requireText(contentHash, "contentHash");
    }
  }

  public record AppendCommand(
      String compilationId,
      Kind kind,
      String schemaVersion,
      String producerId,
      String producerVersion,
      Object payload,
      List<Reference> inputs,
      String revisesArtifactId,
      ArtifactProvenance provenance) {

    public AppendCommand {
      inputs = inputs == null ? List.of() : List.copyOf(inputs);
    }

    /** Legacy nine-field constructor; provenance is {@code null}. */
    public AppendCommand(
        String compilationId,
        Kind kind,
        String schemaVersion,
        String producerId,
        String producerVersion,
        Object payload,
        List<Reference> inputs,
        String revisesArtifactId) {
      this(
          compilationId,
          kind,
          schemaVersion,
          producerId,
          producerVersion,
          payload,
          inputs,
          revisesArtifactId,
          null);
    }
  }

  public record Revision(
      String artifactId,
      String compilationId,
      Kind kind,
      String lineageId,
      long sequence,
      String schemaVersion,
      Instant createdAt,
      String producerId,
      String producerVersion,
      String contentHash,
      List<Reference> inputs,
      String revisesArtifactId,
      JsonNode payload,
      ArtifactProvenance provenance) {

    public Revision {
      inputs = inputs == null ? List.of() : List.copyOf(inputs);
    }

    public Reference reference() {
      return new Reference(kind, artifactId, contentHash);
    }
  }

  public record DecisionCommand(
      String compilationId,
      Reference target,
      Decision decision,
      String actor,
      String comment) {}

  public record ArtifactDecision(
      String decisionId,
      String compilationId,
      long sequence,
      Reference target,
      Decision decision,
      String actor,
      Instant decidedAt,
      String comment) {}

  public record ChangeImpact(Reference replacement, List<Revision> staleDescendants) {

    public ChangeImpact {
      staleDescendants =
          staleDescendants == null ? List.of() : List.copyOf(staleDescendants);
    }
  }
}
