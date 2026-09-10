package org.qubership.integration.platform.ai.productpipeline.create;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.plan.mapping.MappingContractEvaluation;
import org.qubership.integration.platform.ai.plan.mapping.MappingContractGate;
import org.qubership.integration.platform.ai.plan.mapping.schema.JsonSchemaMappingContractFactory;
import org.qubership.integration.platform.ai.plan.mapping.schema.MappingSchemaSide;
import org.qubership.integration.platform.ai.plan.mapping.schema.OperationSchemaLoader;
import org.qubership.integration.platform.ai.plan.mapping.schema.OperationSchemaMaps;
import org.qubership.integration.platform.ai.productpipeline.artifact.MappingValidationDetails;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CanonicalPayloadHash;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryEvidence;
import org.qubership.integration.platform.ai.productpipeline.recovery.SemanticFinding;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/** Revalidates a repaired brief against the contracts that established its mapping halt. */
@ApplicationScoped
public class MappingRepairCaptureValidator {

  private final ProductPipelineArtifactStore artifactStore;
  private final ObjectMapper objectMapper;
  private final OperationSchemaLoader schemaLoader;

  @Inject
  public MappingRepairCaptureValidator(
      ProductPipelineArtifactStore artifactStore,
      ObjectMapper objectMapper,
      OperationSchemaLoader schemaLoader) {
    this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.schemaLoader = Objects.requireNonNull(schemaLoader, "schemaLoader");
  }

  public Result validate(
      String runId, String conversationId, String recoveryEvidenceHash, RequirementBrief candidate) {
    if (recoveryEvidenceHash == null || recoveryEvidenceHash.isBlank()) {
      return Result.notApplicable();
    }
    RecoveryEvidence evidence = recoveryEvidence(runId, recoveryEvidenceHash);
    if (evidence == null) {
      return Result.unresolved("Mapping repair evidence is unavailable.");
    }
    if (!"MAPPING_CONTRACT".equals(evidence.observedCauseCode())) {
      return Result.notApplicable();
    }
    Map<String, MappingValidationDetails> priorDetails = mappingDetails(evidence);
    if (priorDetails.isEmpty()) {
      return Result.unresolved("Mapping repair evidence has no typed contract finding.");
    }
    if (candidate == null) {
      return Result.unresolved("Mapping repair did not produce a requirement brief.");
    }

    List<PlanValidationFinding> findings = new ArrayList<>();
    for (MappingValidationDetails details : priorDetails.values()) {
      MappingIntent intent = intent(candidate, details.mappingIntentId());
      if (intent == null) {
        return Result.unresolved(
            "Mapping repair does not contain mapping intent '" + details.mappingIntentId() + "'.");
      }
      SideResolution source =
          resolveSide(conversationId, candidate, intent, details, true);
      if (!source.resolved()) {
        return Result.unresolved(source.limitation());
      }
      SideResolution target =
          resolveSide(conversationId, candidate, intent, details, false);
      if (!target.resolved()) {
        return Result.unresolved(target.limitation());
      }
      MappingContractEvaluation evaluated =
          MappingContractGate.evaluate(
              intent,
              JsonSchemaMappingContractFactory.from(source.side().schema()),
              JsonSchemaMappingContractFactory.from(target.side().schema()));
      findings.addAll(
          MappingContractGate.toPlanFindings(
              evaluated,
              source.side(),
              target.side(),
              details.consumedBriefArtifactId(),
              details.consumedBriefContentHash()));
    }
    return findings.isEmpty() ? Result.passed() : Result.blocked(findings);
  }

  private RecoveryEvidence recoveryEvidence(String runId, String contentHash) {
    if (runId == null || runId.isBlank()) {
      return null;
    }
    for (Revision revision : artifactStore.history(runId, Kind.RECOVERY_EVIDENCE)) {
      if (contentHash.equals(revision.contentHash())) {
        return artifactStore.payload(revision, RecoveryEvidence.class);
      }
    }
    return null;
  }

  private Map<String, MappingValidationDetails> mappingDetails(RecoveryEvidence evidence) {
    Map<String, MappingValidationDetails> detailsByIntent = new LinkedHashMap<>();
    for (SemanticFinding semantic : evidence.findings()) {
      if (semantic == null
          || semantic.rawValidatorJson() == null
          || semantic.rawValidatorJson().isBlank()) {
        continue;
      }
      try {
        PlanValidationFinding finding =
            objectMapper.readValue(semantic.rawValidatorJson(), PlanValidationFinding.class);
        MappingValidationDetails details = finding.mappingDetails();
        if (details.isPresent() && !details.mappingIntentId().isBlank()) {
          detailsByIntent.putIfAbsent(details.mappingIntentId(), details);
        }
      } catch (Exception ignored) {
        // Legacy text-only findings cannot prove that a mapping repair passed.
      }
    }
    return detailsByIntent;
  }

  private SideResolution resolveSide(
      String conversationId,
      RequirementBrief candidate,
      MappingIntent intent,
      MappingValidationDetails details,
      boolean source) {
    String owner = source ? details.sourceSchemaOwner() : details.targetSchemaOwner();
    String direction = source ? details.sourceSchemaDirection() : details.targetSchemaDirection();
    String digest = source ? details.sourceSchemaDigest() : details.targetSchemaDigest();
    String priorRef = source ? details.sourceRef() : details.targetRef();
    String candidateRef = source ? intent.sourceRef() : intent.targetRef();
    MappingPort candidatePort = source ? intent.sourcePort() : intent.targetPort();
    MappingSchemaSide prior = persistedSide(conversationId, owner, direction, digest);
    boolean sameBoundary = sameBoundary(priorRef, candidateRef, direction, candidatePort);
    BindingLookup bindingLookup =
        sameBoundary
            ? binding(candidate, candidateRef, owner, priorRef)
            : binding(candidate, candidateRef);
    if (bindingLookup.failureReason() != null) {
      return SideResolution.unresolved(bindingLookup.failureReason());
    }
    CatalogBindingHint binding = bindingLookup.binding();
    if (requiresOperationContract(prior, binding, sameBoundary)) {
      return operationSide(binding, candidatePort);
    }
    if (!sameBoundary) {
      return SideResolution.unresolved(
          "Cannot resolve the contract for changed mapping boundary '" + candidateRef + "'.");
    }
    if (prior == null || prior.schema() == null || prior.schema().isNull()) {
      return SideResolution.unresolved(
          "Cannot resolve the persisted mapping contract for '" + candidateRef + "'.");
    }
    return SideResolution.resolved(prior);
  }

  private static boolean sameBoundary(
      String priorRef, String candidateRef, String direction, MappingPort candidatePort) {
    return Objects.equals(priorRef, candidateRef)
        && Objects.equals(direction, candidatePort == null ? "" : candidatePort.name());
  }

  private static boolean requiresOperationContract(
      MappingSchemaSide prior, CatalogBindingHint binding, boolean sameBoundary) {
    if (binding == null) {
      return false;
    }
    if (!sameBoundary || prior == null || prior.operationId() == null) {
      return true;
    }
    return !binding.integrationOperationId().equals(prior.operationId());
  }

  private MappingSchemaSide persistedSide(
      String conversationId, String owner, String direction, String digest) {
    if (conversationId == null || conversationId.isBlank()) {
      return null;
    }
    for (Revision revision : artifactStore.history(conversationId, Kind.MAPPING_SCHEMA_SIDE)) {
      MappingSchemaSide side = artifactStore.payload(revision, MappingSchemaSide.class);
      if (side != null
          && Objects.equals(owner, side.serviceCallId())
          && Objects.equals(direction, side.direction() == null ? "" : side.direction().name())
          && Objects.equals(digest, side.sha256())) {
        return side;
      }
    }
    return null;
  }

  private SideResolution operationSide(CatalogBindingHint binding, MappingPort port) {
    String operationId = binding.integrationOperationId();
    OperationSchemaMaps maps;
    try {
      maps = schemaLoader.load(operationId);
    } catch (RuntimeException exception) {
      return SideResolution.unresolved(
          "Cannot resolve mapping contract for operation "
              + operationId
              + ": "
              + exception.getMessage());
    }
    JsonNode schema = schema(maps, port);
    if (schema == null || schema.isNull()) {
      return SideResolution.unresolved(
          "Cannot resolve mapping contract for operation " + operationId + ".");
    }
    String digest = CanonicalPayloadHash.sha256Hex(canonicalJson(schema));
    return SideResolution.resolved(
        new MappingSchemaSide(
            "1",
            binding.interactionId(),
            operationId,
            port,
            "application/json",
            null,
            digest,
            "catalog-operation:" + operationId,
            schema));
  }

  private static JsonNode schema(OperationSchemaMaps maps, MappingPort port) {
    if (maps == null || port == null) {
      return null;
    }
    if (port == MappingPort.REQUEST || port == MappingPort.OUTPUT) {
      return soleSchema(maps.requestByContentType());
    }
    List<JsonNode> success = new ArrayList<>();
    for (Map.Entry<String, Map<String, JsonNode>> entry :
        maps.responseByStatusThenContentType().entrySet()) {
      String status = entry.getKey();
      if (status != null
          && status.length() == 3
          && status.charAt(0) == '2'
          && Character.isDigit(status.charAt(1))
          && Character.isDigit(status.charAt(2))) {
        JsonNode schema = soleSchema(entry.getValue());
        if (schema != null) {
          success.add(schema);
        }
      }
    }
    return success.size() == 1 ? success.getFirst() : null;
  }

  private static JsonNode soleSchema(Map<String, JsonNode> schemas) {
    if (schemas == null) {
      return null;
    }
    List<JsonNode> available = new ArrayList<>();
    for (Map.Entry<String, JsonNode> entry : schemas.entrySet()) {
      if (!"parameters".equals(entry.getKey()) && entry.getValue() != null) {
        available.add(entry.getValue());
      }
    }
    return available.size() == 1 ? available.getFirst() : null;
  }

  private String canonicalJson(JsonNode schema) {
    try {
      return objectMapper.writeValueAsString(schema);
    } catch (Exception exception) {
      throw new IllegalStateException("Cannot serialize mapping contract", exception);
    }
  }

  private static MappingIntent intent(RequirementBrief brief, String mappingIntentId) {
    for (MappingIntent candidate : brief.mappingIntents()) {
      if (candidate != null && Objects.equals(mappingIntentId, candidate.mappingIntentId())) {
        return candidate;
      }
    }
    return null;
  }

  private static BindingLookup binding(RequirementBrief brief, String... interactionIds) {
    Set<String> searched = new LinkedHashSet<>();
    for (String interactionId : interactionIds) {
      if (!searched.add(interactionId)) {
        continue;
      }
      List<CatalogBindingHint> matches = new ArrayList<>();
      for (CatalogBindingHint candidate : brief.catalogBindings()) {
        if (candidate != null && Objects.equals(interactionId, candidate.interactionId())) {
          matches.add(candidate);
        }
      }
      if (matches.size() == 1) {
        return BindingLookup.found(matches.getFirst());
      }
      if (matches.size() > 1) {
        return BindingLookup.failed(
            "Multiple catalog bindings match mapping boundary '" + interactionId + "'.");
      }
    }
    return BindingLookup.absent();
  }

  private record BindingLookup(CatalogBindingHint binding, String failureReason) {
    static BindingLookup found(CatalogBindingHint binding) {
      return new BindingLookup(binding, null);
    }

    static BindingLookup failed(String reason) {
      return new BindingLookup(null, reason);
    }

    static BindingLookup absent() {
      return new BindingLookup(null, null);
    }
  }

  private record SideResolution(MappingSchemaSide side, String limitation) {
    static SideResolution resolved(MappingSchemaSide side) {
      return new SideResolution(side, "");
    }

    static SideResolution unresolved(String limitation) {
      return new SideResolution(null, limitation);
    }

    boolean resolved() {
      return side != null;
    }
  }

  public record Result(Status status, List<PlanValidationFinding> findings, String message) {
    public enum Status {
      NOT_APPLICABLE,
      PASSED,
      BLOCKED,
      UNRESOLVED
    }

    public Result {
      findings = findings == null ? List.of() : List.copyOf(findings);
      message = message == null ? "" : message;
    }

    static Result notApplicable() {
      return new Result(Status.NOT_APPLICABLE, List.of(), "");
    }

    static Result passed() {
      return new Result(Status.PASSED, List.of(), "");
    }

    static Result blocked(List<PlanValidationFinding> findings) {
      StringBuilder message = new StringBuilder();
      for (PlanValidationFinding finding : findings) {
        if (finding == null
            || finding.message() == null
            || finding.message().isBlank()
            || message.indexOf(finding.message()) >= 0) {
          continue;
        }
        if (!message.isEmpty()) {
          message.append(' ');
        }
        message.append(finding.message());
      }
      return new Result(Status.BLOCKED, findings, message.toString());
    }

    static Result unresolved(String message) {
      return new Result(Status.UNRESOLVED, List.of(), message);
    }
  }
}
