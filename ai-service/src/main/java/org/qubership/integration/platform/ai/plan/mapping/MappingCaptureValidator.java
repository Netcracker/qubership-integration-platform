package org.qubership.integration.platform.ai.plan.mapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.Attribute;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.ObjectSchema;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.ObjectType;
import org.qubership.integration.platform.ai.plan.mapping.envelope.MappingEnvelope;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingContract;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;

/**
 * Runs freeze, parity, mapper-2 contract, and Groovy compile on generator captures. Does not rewrite
 * JSON; representation errors stay on the capture repair loop. Successful Groovy compilation is
 * not semantic equivalence; SCRIPT coverage must still equal the approved target-path set.
 */
public final class MappingCaptureValidator {

  public void validateMapper2(
      MappingEnvelope envelope, MappingIntent intent, MappingDescriptionDocument captured) {
    Objects.requireNonNull(envelope, "envelope");
    Objects.requireNonNull(intent, "intent");
    Objects.requireNonNull(captured, "captured");
    MappingParityValidator.requireMapper2(envelope, intent, captured);
    Mapper2ContractValidator.validate(envelope, captured);
  }

  public void validateScript(MappingIntent intent, String script, List<String> mappingCoverage) {
    validateScript(intent, script, mappingCoverage, MappingContract.unknown());
  }

  public void validateScript(
      MappingIntent intent,
      String script,
      List<String> mappingCoverage,
      MappingEnvelope envelope) {
    validateScript(intent, script, mappingCoverage, contractFromEnvelope(envelope));
  }

  /**
   * Keep coverage paths that touch the hop target body. Preserve-for-later context such as
   * {@code $.response.*} is dropped from the list; Groovy may still assign those paths.
   */
  public List<String> hopBodyCoverage(List<String> mappingCoverage, MappingEnvelope envelope) {
    if (mappingCoverage == null) {
      return null;
    }
    return contractFromEnvelope(envelope).hopBodyFieldsCoveredBy(mappingCoverage);
  }

  public void validateScript(
      MappingIntent intent,
      String script,
      List<String> mappingCoverage,
      MappingContract targetContract) {
    Objects.requireNonNull(intent, "intent");
    Objects.requireNonNull(script, "script");
    SecureGroovyMappingCompiler.compile(script);
    MappingParityValidator.requireScriptCoverage(intent, mappingCoverage, targetContract);
  }

  private static MappingContract contractFromEnvelope(MappingEnvelope envelope) {
    if (envelope == null) {
      return MappingContract.unknown();
    }
    return MappingContract.fromHopPaths(targetBodyPaths(envelope));
  }

  private static List<String> targetBodyPaths(MappingEnvelope envelope) {
    if (envelope.target() == null
        || !(envelope.target().body() instanceof ObjectType objectType)) {
      return List.of();
    }
    ObjectSchema schema = objectType.schema();
    if (schema == null) {
      return List.of();
    }
    List<String> paths = new ArrayList<>();
    for (Attribute attribute : schema.attributes()) {
      String path = envelope.idToPath().get(attribute.id());
      if (path != null && !path.isBlank()) {
        paths.add(path);
      }
    }
    return paths;
  }
}
