package org.qubership.integration.platform.ai.productpipeline.profile;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignEntryRoute;

/**
 * Conditional stage skip declared on a profile stage. Evaluated against the committed
 * requirement-draft and design-entry route before capability execution (ADR 0001 decision 9).
 */
public record SkipPolicy(List<String> whenAny) {

  public static final String NO_APIHUB_CANDIDATE = "no-apihub-candidate";
  public static final String CATALOG_BINDING_PRESENT = "catalog-binding-present";
  public static final String PROVIDED_DESIGN_ROUTE = "provided-design-route";

  public enum SkipAction {
    REQUIREMENT_DRAFT_PASSTHROUGH,
    NO_OUTPUT
  }

  public record SkipEvaluationContext(
      RequirementDraft requirementDraft, DesignEntryRoute designEntryRoute) {}

  public SkipPolicy {
    whenAny = whenAny == null ? List.of() : List.copyOf(whenAny);
  }

  /** True when any declared condition matches the draft (OR semantics). */
  public boolean matches(RequirementDraft draft) {
    return evaluate(new SkipEvaluationContext(draft, null)).isPresent();
  }

  public Optional<SkipAction> evaluate(SkipEvaluationContext context) {
    Objects.requireNonNull(context, "context");
    for (String condition : whenAny) {
      if (condition == null || condition.isBlank()) {
        continue;
      }
      String normalized = condition.trim().toLowerCase(Locale.ROOT);
      switch (normalized) {
        case NO_APIHUB_CANDIDATE -> {
          RequirementDraft draft = context.requirementDraft();
          // Without a draft this condition cannot be evaluated. Returning a
          // REQUIREMENT_DRAFT_PASSTHROUGH here would beat later whenAny entries such as
          // provided-design-route (OR, first match wins) and break the PROVIDED IDS skip path.
          if (draft == null) {
            continue;
          }
          if (draft.apiHubCandidate() == null && !draft.importIntent()) {
            return Optional.of(SkipAction.REQUIREMENT_DRAFT_PASSTHROUGH);
          }
        }
        case CATALOG_BINDING_PRESENT -> {
          RequirementDraft draft = context.requirementDraft();
          if (draft != null && draft.catalogBinding() != null) {
            return Optional.of(SkipAction.REQUIREMENT_DRAFT_PASSTHROUGH);
          }
        }
        case PROVIDED_DESIGN_ROUTE -> {
          if (context.designEntryRoute() == DesignEntryRoute.PROVIDE) {
            return Optional.of(SkipAction.NO_OUTPUT);
          }
        }
        default -> throw new IllegalArgumentException("unknown skip condition: " + condition);
      }
    }
    return Optional.empty();
  }

  public static void requireKnownConditions(SkipPolicy policy) {
    if (policy == null) {
      return;
    }
    for (String condition : policy.whenAny()) {
      Objects.requireNonNull(condition, "skip condition");
      String normalized = condition.trim().toLowerCase(Locale.ROOT);
      if (!NO_APIHUB_CANDIDATE.equals(normalized)
          && !CATALOG_BINDING_PRESENT.equals(normalized)
          && !PROVIDED_DESIGN_ROUTE.equals(normalized)) {
        throw new IllegalArgumentException("unknown skip condition: " + condition);
      }
    }
  }
}
