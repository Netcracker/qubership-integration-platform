package org.qubership.integration.platform.ai.productpipeline.create.design.execution;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;
import org.qubership.integration.platform.ai.productpipeline.artifact.ApprovalRecordV2;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingResolution;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.NormalizedDesignFlow;
import org.qubership.integration.platform.ai.productpipeline.profile.ApprovalPolicy;

/**
 * Revalidates the catalog bindings an approved design was built on. Requires an implementation
 * approval carrying {@link ApprovalPolicy#CATALOG_FIRST_V1}.
 *
 * <p>Execution reads the catalog and nothing else. API resolution happened during briefing, and the
 * reader approved a design around the operations it chose; picking a different operation here — or
 * importing one from API Hub — would quietly build something other than what was approved. A
 * binding that no longer resolves stops the run instead.
 */
@ApplicationScoped
public class DefaultExecutorCatalogBindingAdapter implements ExecutorCatalogBindingAdapter {

  private final CatalogBindingMatcher matcher;

  @Inject
  public DefaultExecutorCatalogBindingAdapter(CatalogBindingMatcher matcher) {
    this.matcher = Objects.requireNonNull(matcher, "matcher");
  }

  @Override
  public List<BindingResolutionResult> resolve(
      String conversationId,
      NormalizedDesignFlow flow,
      List<CatalogBindingHint> hints,
      ApprovalRecordV2 approval) {
    Objects.requireNonNull(conversationId, "conversationId");
    Objects.requireNonNull(flow, "flow");
    requireMatchingApproval(approval);
    List<CatalogBindingHint> hintList = hints == null ? List.of() : hints;
    List<BindingResolutionResult> results = new ArrayList<>();
    for (NormalizedDesignFlow.Step step : flow.steps()) {
      if (step == null || !"service-call".equalsIgnoreCase(step.kind())) {
        continue;
      }
      results.add(resolveStep(flow, step, hintList));
    }
    return List.copyOf(results);
  }

  private BindingResolutionResult resolveStep(
      NormalizedDesignFlow flow, NormalizedDesignFlow.Step step, List<CatalogBindingHint> hints) {
    HintLookup lookup = findHint(step, hints);
    if (lookup.failureReason() != null) {
      return new BindingResolutionResult.Failed(
          step.stepId(), lookup.failureReason(), StageOutcomeClass.DOMAIN_FAILURE);
    }
    if (lookup.hint() != null) {
      CatalogBindingHint observed = lookup.hint();
      Optional<CatalogBindingMatcher.CatalogMatch> revalidated =
          matcher.revalidateHint(
              flow,
              step,
              observed.systemId(),
              observed.specificationGroupId(),
              observed.specificationId(),
              observed.integrationOperationId());
      if (revalidated.isPresent()) {
        return toExisting(step.stepId(), revalidated.get(), observed.release());
      }
      return new BindingResolutionResult.Failed(
          step.stepId(),
          "the approved catalog binding no longer resolves (operation "
              + observed.integrationOperationId()
              + "); resolve this service call again before execution",
          StageOutcomeClass.DOMAIN_FAILURE,
          "catalog operation");
    }

    CatalogBindingMatcher.MatchResult match = matcher.match(flow, step);
    if (match instanceof CatalogBindingMatcher.MatchResult.Exact exact) {
      return toExisting(step.stepId(), exact.match(), resolveRelease(flow));
    }
    if (match instanceof CatalogBindingMatcher.MatchResult.Ambiguous ambiguous) {
      return new BindingResolutionResult.NeedsInput(step.stepId(), ambiguous.candidateIds());
    }
    return new BindingResolutionResult.Failed(
        step.stepId(),
        "no catalog binding for this service call; resolve it during requirement gathering,"
            + " where API Hub discovery and specification import happen",
        StageOutcomeClass.DOMAIN_FAILURE);
  }

  private static BindingResolutionResult.Resolved toExisting(
      String stepId, CatalogBindingMatcher.CatalogMatch match, String release) {
    return new BindingResolutionResult.Resolved(
        new CatalogBindingResolution(
            stepId,
            CatalogBindingResolution.Source.EXISTING_CATALOG,
            match.systemId(),
            match.specificationGroupId(),
            match.specificationId(),
            match.integrationOperationId(),
            null,
            CatalogStrings.blankToNull(release) == null ? "catalog" : release,
            match.evidenceRef()));
  }

  private static HintLookup findHint(
      NormalizedDesignFlow.Step step, List<CatalogBindingHint> hints) {
    List<CatalogBindingHint> v2Hints = new ArrayList<>();
    List<CatalogBindingHint> v1Hints = new ArrayList<>();
    for (CatalogBindingHint hint : hints) {
      if (hint == null) {
        continue;
      }
      if ("2".equals(hint.schemaVersion())) {
        v2Hints.add(hint);
      } else {
        v1Hints.add(hint);
      }
    }
    if (!v2Hints.isEmpty()) {
      List<CatalogBindingHint> matches = new ArrayList<>();
      for (CatalogBindingHint hint : v2Hints) {
        if (step.sourceFactIds().contains(hint.serviceCallId())) {
          matches.add(hint);
        }
      }
      if (matches.size() == 1) {
        return HintLookup.found(matches.getFirst());
      }
      String callId = callIdForStep(step, matches);
      if (matches.size() > 1) {
        return HintLookup.failed(
            "multiple catalog binding hints for serviceCallId="
                + callId
                + " on step "
                + step.stepId());
      }
      return HintLookup.failed(
          "no catalog binding hint for serviceCallId=" + callId + " on step " + step.stepId());
    }
    String query = CatalogStrings.blankToNull(step.operationQuery());
    for (CatalogBindingHint hint : v1Hints) {
      if (query != null && query.equals(hint.operationQuery())) {
        return HintLookup.found(hint);
      }
      for (String factId : step.sourceFactIds()) {
        if (factId != null && factId.equals(hint.serviceCallId())) {
          return HintLookup.found(hint);
        }
      }
    }
    return HintLookup.none();
  }

  private static String callIdForStep(
      NormalizedDesignFlow.Step step, List<CatalogBindingHint> matches) {
    if (!matches.isEmpty()) {
      return matches.getFirst().serviceCallId();
    }
    for (String factId : step.sourceFactIds()) {
      if (factId != null && !factId.isBlank()) {
        return factId;
      }
    }
    return step.stepId();
  }

  private record HintLookup(CatalogBindingHint hint, String failureReason) {
    static HintLookup found(CatalogBindingHint hint) {
      return new HintLookup(hint, null);
    }

    static HintLookup none() {
      return new HintLookup(null, null);
    }

    static HintLookup failed(String reason) {
      return new HintLookup(null, reason);
    }
  }

  static void requireMatchingApproval(ApprovalRecordV2 approval) {
    if (approval == null) {
      throw new IllegalArgumentException(
          "implementation approval record is required before catalog binding resolution");
    }
    if (!ApprovalPolicy.CATALOG_FIRST_V1.equals(approval.bindingResolutionPolicy())
        || !ApprovalPolicy.CATALOG_FIRST_V1_HASH.equals(approval.bindingResolutionPolicyHash())) {
      throw new IllegalArgumentException(
          "approval must carry bindingResolutionPolicy=CATALOG_FIRST_V1 with the pinned hash");
    }
  }

  private static String resolveRelease(NormalizedDesignFlow flow) {
    String fromFlow = flowRelease(flow);
    return fromFlow != null ? fromFlow : "catalog";
  }

  private static String flowRelease(NormalizedDesignFlow flow) {
    for (String constraint : flow.constraints()) {
      String value = keyedValue(constraint, "release");
      if (value != null) {
        return value;
      }
      value = keyedValue(constraint, "version");
      if (value != null) {
        return value;
      }
    }
    for (String assumption : flow.assumptions()) {
      String value = keyedValue(assumption, "release");
      if (value != null) {
        return value;
      }
      value = keyedValue(assumption, "version");
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  private static String keyedValue(String line, String key) {
    if (line == null) {
      return null;
    }
    String prefix = key.toLowerCase(Locale.ROOT) + ":";
    String trimmed = line.trim();
    if (trimmed.toLowerCase(Locale.ROOT).startsWith(prefix)) {
      String value = trimmed.substring(prefix.length()).trim();
      return value.isEmpty() ? null : value;
    }
    return null;
  }
}
