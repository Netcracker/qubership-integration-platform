package org.qubership.integration.platform.ai.plan.mapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.qubership.integration.platform.ai.plan.mapping.envelope.MappingEnvelope;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingValue;

/** Emits COPY from frozen origins and compiler-owned retention, with fail-fast missing values. */
public final class DirectFieldMappingScript {
  private DirectFieldMappingScript() {}

  public static Optional<Generated> from(MappingIntent intent, MappingEnvelope envelope) {
    if (intent == null) return Optional.empty();
    CopyMappingPlan plan = envelope == null ? null : envelope.verifiedCopyPlan();
    if (plan == null || plan.reads().isEmpty() || plan.reads().size() != intent.rules().size()) {
      throw new IllegalArgumentException("Mapping COPY requires a verified origin plan; recapture and approve the mapping");
    }
    StringBuilder script = new StringBuilder("import groovy.json.JsonSlurper\nimport groovy.json.JsonOutput\n"
        + "def source = exchange.in.body\n"
        + "if (source instanceof String) source = new JsonSlurper().parseText(source)\n"
        + "if (!(source instanceof Map)) throw new IllegalArgumentException('Expected a JSON object body')\n");
    for (CopyMappingPlan.Save save : plan.saves()) {
      if (!save.fromOutput()) appendSave(script, save, "source");
    }
    List<String> coverage = new ArrayList<>();
    script.append("def result = [:]\n");
    for (int i = 0; i < plan.reads().size(); i++) {
      CopyMappingPlan.Read read = plan.reads().get(i);
      MappingIntentRule rule = intent.rules().get(i);
      CopyMappingPlanner.requireUnconditional(rule);
      if (!(rule.value() instanceof MappingValue.Copy copy)
          || !read.origin().equals(copy.source()) || !"ERROR".equals(rule.missingSourcePolicy())
          || !("$." + read.targetField()).equals(rule.targetPath())
          || !java.util.Objects.equals(rule.id(), read.ruleId())
          || rule.schemaPins() == null
          || !java.util.Objects.equals(read.schemaDigest(), rule.schemaPins().get("source"))
          || !java.util.Objects.equals(read.targetSchemaDigest(), rule.schemaPins().get("target"))) {
        throw new IllegalArgumentException("Mapping COPY plan no longer matches the approved operands");
      }
      String input = "source";
      if (read.contextId() != null) {
        if (!CopyMappingPlanner.contextId(read.origin()).equals(read.contextId())) {
          throw new IllegalArgumentException("Mapping retention identity does not match its origin");
        }
        input = "retained" + i;
        script.append("def ").append(input).append("Raw = exchange.getProperty('")
            .append(read.contextId()).append("')\n")
            .append("if (!(").append(input).append("Raw instanceof String)) throw new IllegalArgumentException('Missing retained mapping source')\n")
            .append("def ").append(input).append(" = new JsonSlurper().parseText(").append(input).append("Raw)\n");
      }
      String field = read.contextId() == null ? read.origin().path().substring(2) : "value";
      appendPresent(script, input, field, read.nullable());
      script.append("result['").append(read.targetField()).append("'] = ")
          .append(input).append("['").append(field).append("']\n");
      coverage.add("$." + read.targetField());
    }
    for (CopyMappingPlan.Save save : plan.saves()) {
      if (save.fromOutput()) appendSave(script, save, "result");
    }
    script.append("exchange.in.body = result\nreturn exchange.in.body\n");
    return Optional.of(new Generated(script.toString(), coverage));
  }

  public static Generated saveOnly(CopyMappingPlan plan) {
    if (plan == null || !plan.reads().isEmpty() || plan.saves().isEmpty()
        || plan.saves().stream().anyMatch(CopyMappingPlan.Save::fromOutput)) {
      throw new IllegalArgumentException("A retention-only site requires its frozen save plan");
    }
    StringBuilder script = new StringBuilder("import groovy.json.JsonSlurper\nimport groovy.json.JsonOutput\n"
        + "def source = exchange.in.body\n"
        + "if (source instanceof String) source = new JsonSlurper().parseText(source)\n"
        + "if (!(source instanceof Map)) throw new IllegalArgumentException('Expected a JSON object body')\n");
    for (CopyMappingPlan.Save save : plan.saves()) appendSave(script, save, "source");
    script.append("return exchange.in.body\n");
    return new Generated(script.toString(), List.of());
  }

  private static void appendSave(StringBuilder script, CopyMappingPlan.Save save, String source) {
    appendPresent(script, source, save.sourceField(), save.nullable());
    script.append("exchange.setProperty('").append(save.contextId())
        .append("', JsonOutput.toJson([value: ").append(source).append("['")
        .append(save.sourceField()).append("']]))\n");
  }

  private static void appendPresent(StringBuilder script, String source, String field, boolean nullable) {
    script.append("if (!").append(source).append(".containsKey('").append(field)
        .append("')) throw new IllegalArgumentException('Missing COPY source: ").append(field).append("')\n");
    if (!nullable) {
      script.append("if (").append(source).append("['").append(field)
          .append("'] == null) throw new IllegalArgumentException('Null COPY source: ").append(field).append("')\n");
    }
  }

  public record Generated(String script, List<String> mappingCoverage) {
    public Generated { mappingCoverage = List.copyOf(mappingCoverage); }
  }
}
