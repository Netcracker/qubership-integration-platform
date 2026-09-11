package org.qubership.integration.platform.ai.plan.mapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.qubership.integration.platform.ai.plan.BriefMappingValidator;
import org.qubership.integration.platform.ai.plan.mapping.schema.JsonSchemaMappingContractFactory;
import org.qubership.integration.platform.ai.plan.mapping.schema.MappingBoundarySchemas;
import org.qubership.integration.platform.ai.plan.mapping.schema.MappingSchemaSide;
import org.qubership.integration.platform.ai.productpipeline.artifact.MappingValidationDetails;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingContract;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;

/** Blocks mapping generation when classified rules still leave required targets unresolved. */
public final class MappingContractGate {

  private MappingContractGate() {}

  public static Optional<String> blockedMessage(
      MappingIntent intent, MappingBoundarySchemas schemas) {
    MappingContractEvaluation evaluated = evaluate(intent, schemas);
    return evaluated.blocked() ? Optional.of(evaluated.blockedMessage()) : Optional.empty();
  }

  public static Optional<String> blockedMessage(
      MappingIntent intent, MappingContract sourceContract, MappingContract targetContract) {
    MappingContractEvaluation evaluated = evaluate(intent, sourceContract, targetContract);
    return evaluated.blocked() ? Optional.of(evaluated.blockedMessage()) : Optional.empty();
  }

  public static MappingContractEvaluation evaluate(
      MappingIntent intent, MappingBoundarySchemas schemas) {
    Objects.requireNonNull(schemas, "schemas");
    MappingContract source = JsonSchemaMappingContractFactory.from(schemas.source().schema());
    MappingContract target = JsonSchemaMappingContractFactory.from(schemas.target().schema());
    return evaluate(intent, source, target);
  }

  public static MappingContractEvaluation evaluate(
      MappingIntent intent, MappingContract sourceContract, MappingContract targetContract) {
    Objects.requireNonNull(intent, "intent");
    return BriefMappingValidator.evaluateBoundary(
        intent.mappingIntentId(),
        intent.sourceRef(),
        intent.sourcePort(),
        intent.targetRef(),
        intent.targetPort(),
        intent.rules(),
        sourceContract,
        targetContract,
        intent.implementationPreference());
  }

  public static List<PlanValidationFinding> toPlanFindings(
      MappingContractEvaluation evaluated, MappingSchemaSide source, MappingSchemaSide target) {
    return toPlanFindings(evaluated, source, target, "", "");
  }

  public static List<PlanValidationFinding> toPlanFindings(
      MappingContractEvaluation evaluated,
      MappingSchemaSide source,
      MappingSchemaSide target,
      String briefArtifactId,
      String briefContentHash) {
    if (evaluated == null || !evaluated.blocked()) {
      return List.of();
    }
    List<PlanValidationFinding> findings = new ArrayList<>();
    for (MappingRuleFinding finding : evaluated.blockerFindings()) {
      findings.add(toPlanFinding(finding, source, target, briefArtifactId, briefContentHash));
    }
    return List.copyOf(findings);
  }

  public static PlanValidationFinding toPlanFinding(
      MappingRuleFinding finding,
      MappingSchemaSide source,
      MappingSchemaSide target,
      String briefArtifactId,
      String briefContentHash) {
    MappingValidationDetails details =
        new MappingValidationDetails(
            finding.mappingIntentId(),
            finding.sourceRef(),
            finding.sourcePort() == null ? "" : finding.sourcePort().name(),
            finding.targetRef(),
            finding.targetPort() == null ? "" : finding.targetPort().name(),
            finding.sourcePath(),
            finding.targetPath(),
            finding.expression(),
            finding.ruleStatus(),
            briefArtifactId,
            briefContentHash,
            schemaOwner(source),
            schemaDirection(source),
            schemaDigest(source),
            schemaProvenance(source),
            schemaOwner(target),
            schemaDirection(target),
            schemaDigest(target),
            schemaProvenance(target),
            finding.expectedContract(),
            finding.observed());
    return new PlanValidationFinding(finding.code().name(), finding.message(), true, details);
  }

  private static String schemaOwner(MappingSchemaSide side) {
    if (side == null) {
      return "";
    }
    if (side.serviceCallId() != null && !side.serviceCallId().isBlank()) {
      return side.serviceCallId();
    }
    return side.operationId() == null ? "" : side.operationId();
  }

  private static String schemaDirection(MappingSchemaSide side) {
    return side == null || side.direction() == null ? "" : side.direction().name();
  }

  private static String schemaDigest(MappingSchemaSide side) {
    return side == null || side.sha256() == null ? "" : side.sha256();
  }

  private static String schemaProvenance(MappingSchemaSide side) {
    return side == null || side.provenance() == null ? "" : side.provenance();
  }
}
