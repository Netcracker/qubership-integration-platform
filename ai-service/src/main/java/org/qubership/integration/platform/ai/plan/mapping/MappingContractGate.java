package org.qubership.integration.platform.ai.plan.mapping;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.qubership.integration.platform.ai.plan.BriefMappingValidator;
import org.qubership.integration.platform.ai.plan.mapping.schema.JsonSchemaMappingContractFactory;
import org.qubership.integration.platform.ai.plan.mapping.schema.MappingBoundarySchemas;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingContract;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/** Blocks mapping generation when classified rules still leave required targets unresolved. */
public final class MappingContractGate {

  private MappingContractGate() {}

  public static Optional<String> blockedMessage(
      MappingIntent intent, MappingBoundarySchemas schemas) {
    Objects.requireNonNull(schemas, "schemas");
    MappingContract source =
        JsonSchemaMappingContractFactory.from(schemas.source().schema());
    MappingContract target =
        JsonSchemaMappingContractFactory.from(schemas.target().schema());
    return blockedMessage(intent, source, target);
  }

  public static Optional<String> blockedMessage(
      MappingIntent intent, MappingContract sourceContract, MappingContract targetContract) {
    Objects.requireNonNull(intent, "intent");
    Optional<MappingIntent> validated =
        BriefMappingValidator.validateBoundary(
            intent.mappingIntentId(),
            intent.sourceRef(),
            intent.sourcePort(),
            intent.targetRef(),
            intent.targetPort(),
            intent.rules(),
            sourceContract,
            targetContract,
            intent.implementationPreference());
    if (validated.isEmpty()) {
      return Optional.empty();
    }
    RequirementBrief brief =
        new RequirementBrief(
            "",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "",
            null,
            "",
            List.of())
            .withMappingIntents(List.of(validated.get()));
    return BriefMappingValidator.unresolvedRequiredMessage(brief);
  }
}
