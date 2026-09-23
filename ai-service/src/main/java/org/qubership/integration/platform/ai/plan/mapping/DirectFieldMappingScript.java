package org.qubership.integration.platform.ai.plan.mapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.ObjectType;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.NullType;
import org.qubership.integration.platform.ai.plan.mapping.envelope.MappingEnvelope;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingContract;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingRuleStatus;

/** Projects approved direct JSON body copies into a Groovy script. */
public final class DirectFieldMappingScript {

  private static final Pattern TOP_LEVEL_FIELD = Pattern.compile("\\$\\.[A-Za-z_][A-Za-z0-9_]*");

  private DirectFieldMappingScript() {}

  public static Optional<Generated> from(MappingIntent intent, MappingEnvelope envelope) {
    if (intent == null
        || envelope == null
        || envelope.source() == null
        || envelope.target() == null
        || !isObjectOrUnknown(envelope.source().body())
        || !isObjectOrUnknown(envelope.target().body())
        || intent.rules().isEmpty()) {
      return Optional.empty();
    }
    List<String> targets = new ArrayList<>();
    List<String> entries = new ArrayList<>();
    for (MappingIntentRule rule : intent.rules()) {
      String source = MappingContract.canonicalPath(rule.sourcePath());
      String target = MappingContract.canonicalPath(rule.targetPath());
      if (rule.status() == MappingRuleStatus.UNRESOLVED
          || rule.expression() != null
          || !TOP_LEVEL_FIELD.matcher(source).matches()
          || !TOP_LEVEL_FIELD.matcher(target).matches()) {
        return Optional.empty();
      }
      String sourceField = source.substring(2);
      String targetField = target.substring(2);
      entries.add("'" + targetField + "': source['" + sourceField + "']");
      targets.add(target);
    }
    List<String> coverage = new MappingCaptureValidator().hopBodyCoverage(targets, envelope);
    if (coverage.size() != targets.size()) {
      return Optional.empty();
    }
    String script =
        "import groovy.json.JsonSlurper\n"
            + "def source = exchange.in.body\n"
            + "if (source instanceof String) source = new JsonSlurper().parseText(source)\n"
            + "if (!(source instanceof Map)) throw new IllegalArgumentException('Expected a JSON object body')\n"
            + "exchange.in.body = [" + String.join(", ", entries) + "]\n"
            + "return exchange.in.body\n";
    return Optional.of(new Generated(script, coverage));
  }

  private static boolean isObjectOrUnknown(Object body) {
    return body instanceof ObjectType || body instanceof NullType;
  }

  public record Generated(String script, List<String> mappingCoverage) {
    public Generated {
      mappingCoverage = List.copyOf(mappingCoverage);
    }
  }
}
