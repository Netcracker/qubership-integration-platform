package org.qubership.integration.platform.ai.plan.mapping;

import java.util.List;
import java.util.Objects;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument;
import org.qubership.integration.platform.ai.plan.mapping.envelope.MappingEnvelope;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;

/**
 * Runs freeze, parity, mapper-2 contract, and Groovy compile on generator captures. Does not rewrite
 * JSON; representation errors stay on the capture repair loop.
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
    Objects.requireNonNull(intent, "intent");
    Objects.requireNonNull(script, "script");
    SecureGroovyMappingCompiler.compile(script);
    MappingParityValidator.requireScriptCoverage(intent, mappingCoverage);
  }
}
