package org.qubership.integration.platform.ai.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.qubership.integration.platform.ai.plan.RequirementCaptureEditor.Issue;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.Polarity;
import org.qubership.integration.platform.ai.plan.RequirementElementIntent.Placement;
import org.qubership.integration.platform.ai.plan.mapping.MappingMechanismSelector;

/** Finds element settings that cannot be carried into a chain design. */
public final class RequirementElementConstraints {

  public static final String EXPLICIT_UNSUPPORTED_ELEMENT = "EXPLICIT_UNSUPPORTED_ELEMENT";

  private static final String TIMEOUT_SETTING =
      "(?iu)\\b(?:connectTimeout|connection[ \t]+timeout|\u0442\u0430\u0439\u043c[- ]?\u0430\u0443\u0442[ \t]+\u0441\u043e\u0435\u0434\u0438\u043d\u0435\u043d\u0438\u044f)"
          + "\\b[ \t\"':=]*(?:(?:of|to|is|at|\u0432|\u043d\u0430)[ \t]+)?";

  private RequirementElementConstraints() {}

  public static List<Issue> issues(DraftInput draft, String authorText) {
    if (draft == null || draft.facts() == null) return List.of();
    Set<String> chainRefs = draft.facts().stream().filter(Objects::nonNull)
        .map(fact -> fact.elementIntent()).filter(Objects::nonNull)
        .filter(intent -> intent.placement() == Placement.CHAIN)
        .map(RequirementElementIntent::elementRef).filter(Objects::nonNull).collect(Collectors.toSet());
    List<Issue> issues = new ArrayList<>();
    for (var fact : draft.facts()) {
      if (fact == null || fact.polarity() != Polarity.POSITIVE || fact.elementIntent() == null) continue;
      var intent = fact.elementIntent();
      String path = "/draft/facts/" + fact.sourceFactId() + "/elementIntent";
      if (intent.placement() == Placement.LOCAL_NODE && intent.elementType() != null
          && !intent.elementType().equals(MappingMechanismSelector.canonicalTransformElementType(intent.elementType()))) {
        boolean explicit = mentionsElementType(authorText, intent.elementType())
            || authorText == null && fact.evidence() != null
                && mentionsElementType(fact.evidence().quote(), intent.elementType());
        issues.add(new Issue(explicit ? EXPLICIT_UNSUPPORTED_ELEMENT : "DERIVED_UNSUPPORTED_ELEMENT", path,
            fact.sourceFactId(), explicit
                ? "The request mentions '" + intent.elementType()
                    + "', which is disabled. Edit the requirements to choose a supported implementation."
                : "Keep this behavior and its source evidence. Remove the element intent for '"
                    + intent.elementType() + "'. Choose a supported implementation during design."));
      }
      if (intent.parentElementRef() != null && chainRefs.contains(intent.parentElementRef())) {
        issues.add(new Issue("CHAIN_IS_NOT_AN_ELEMENT_PARENT", path + "/parentElementRef", fact.sourceFactId(),
            "Keep the top-level element and its execution order. Clear parentElementRef because the chain is not a container element."));
      }
      if ("http-trigger".equals(intent.elementType()) && intent.requestedProperties() != null
          && authorText != null && intent.requestedProperties().stream().filter(Objects::nonNull)
              .anyMatch(property -> "connectTimeout".equals(property.key())
                  && !supportedTimeout(authorText, property.value()))) {
        issues.add(new Issue("UNPROVEN_ELEMENT_PROPERTY", path + "/requestedProperties", fact.sourceFactId(),
            "Omit connectTimeout and keep the other trigger properties unless the source specifies this timeout value."));
      }
    }
    return List.copyOf(issues);
  }

  private static boolean supportedTimeout(String text, String value) {
    if (value == null || value.isBlank()) return false;
    String literal = Pattern.quote(value)
        + "(?:[ \t]*(?:milliseconds?|msecs?|ms|\u043c\u0438\u043b\u043b\u0438\u0441\u0435\u043a\u0443\u043d\u0434\\p{L}*|\u043c\u0441)\\b"
        + "|(?=[ \t]*(?:[,;!?\"'\\r\\n}]|\\.(?!\\d)|$)))";
    if (value.matches("[1-9][0-9]{0,8}000")) {
      String seconds = value.substring(0, value.length() - 3);
      literal += "|" + seconds + "[ \t]*(?:seconds?|secs?|s|\u0441\u0435\u043a\u0443\u043d\u0434\\p{L}*|\u0441)";
    }
    return Pattern.compile(TIMEOUT_SETTING + "(?<![\\p{L}\\p{N}])(?:" + literal + ")(?![\\p{L}\\p{N}])")
        .matcher(text).find();
  }

  private static boolean mentionsElementType(String text, String type) {
    if (text == null) return false;
    return Pattern.compile("(?iu)(?<![\\p{L}\\p{N}_-])" + Pattern.quote(type) + "(?![\\p{L}\\p{N}_-])")
        .matcher(text).find();
  }
}
