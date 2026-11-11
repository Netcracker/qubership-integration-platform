package org.qubership.integration.platform.ai.qipknowledge.artifact;

import com.fasterxml.jackson.databind.node.TextNode;
import java.util.List;

/** Declares the message occurrence and port explicitly in mapping fixtures. */
public final class MappingRuleFixtures {
  private MappingRuleFixtures() {}

  public static MappingIntentRule copy(String source, MappingPort port, String path, String target) {
    return copy(source, port, path, target, MappingRuleStatus.PROPOSED);
  }

  public static MappingIntentRule copy(String source, MappingPort port, String path, String target,
      MappingRuleStatus status) {
    return new MappingIntentRule("", List.of(), target,
        new MappingValue.Copy(new MappingSource.Message(source, port, path)), MappingCondition.always(), status);
  }

  public static MappingIntentRule constant(String target, String value, MappingRuleStatus status) {
    return value(target, new MappingValue.Copy(new MappingSource.Constant(
        TextNode.valueOf(value))), status);
  }

  public static MappingIntentRule context(String target, String id, MappingRuleStatus status) {
    return value(target, new MappingValue.Copy(new MappingSource.Context(id)), status);
  }

  public static MappingIntentRule value(String target, MappingValue value, MappingRuleStatus status) {
    return new MappingIntentRule("", List.of(), target, value, MappingCondition.always(), status);
  }
}
