package org.qubership.integration.platform.ai.productpipeline.create.design.execution;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubMcpTools;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubRequirementRefs;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubSearchAuthorizations;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubSearchHitParser;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ApiHubSpecificationImportResult;
import org.qubership.integration.platform.ai.integration.catalog.pipeline.CatalogMutationGateway;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;
import org.qubership.integration.platform.ai.productpipeline.artifact.ApprovalRecordV2;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingResolution;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.NormalizedDesignFlow;
import org.qubership.integration.platform.ai.productpipeline.profile.ApprovalPolicy;

/**
 * Default catalog-first binding adapter. Requires an implementation approval carrying {@link
 * ApprovalPolicy#CATALOG_FIRST_V1}. Local catalog hits never call APIHub; a miss queries APIHub and
 * imports through {@link CatalogMutationGateway} with one technical retry for transport/session
 * failures.
 */
@ApplicationScoped
public class DefaultExecutorCatalogBindingAdapter implements ExecutorCatalogBindingAdapter {

  /** Profile {@code design-execution} allows one technical retry ({@code maxTechnicalRetries: 1}). */
  static final int MAX_TECHNICAL_RETRIES = 1;

  static final Duration IMPORT_TIMEOUT = Duration.ofSeconds(60);

  private final CatalogBindingMatcher matcher;
  private final ApiHubMcpTools apiHubMcpTools;
  private final CatalogMutationGateway catalogMutationGateway;
  private final ApiHubSearchAuthorizations searchAuthorizations;

  @Inject
  public DefaultExecutorCatalogBindingAdapter(
      CatalogBindingMatcher matcher,
      ApiHubMcpTools apiHubMcpTools,
      CatalogMutationGateway catalogMutationGateway,
      ApiHubSearchAuthorizations searchAuthorizations) {
    this.matcher = Objects.requireNonNull(matcher, "matcher");
    this.apiHubMcpTools = Objects.requireNonNull(apiHubMcpTools, "apiHubMcpTools");
    this.catalogMutationGateway =
        Objects.requireNonNull(catalogMutationGateway, "catalogMutationGateway");
    this.searchAuthorizations =
        Objects.requireNonNull(searchAuthorizations, "searchAuthorizations");
  }

  /** Test constructor for the paths that never reach API Hub. */
  public DefaultExecutorCatalogBindingAdapter(
      CatalogBindingMatcher matcher,
      ApiHubMcpTools apiHubMcpTools,
      CatalogMutationGateway catalogMutationGateway) {
    this(matcher, apiHubMcpTools, catalogMutationGateway, new ApiHubSearchAuthorizations());
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
      results.add(resolveStep(conversationId, flow, step, hintList));
    }
    return List.copyOf(results);
  }

  private BindingResolutionResult resolveStep(
      String conversationId,
      NormalizedDesignFlow flow,
      NormalizedDesignFlow.Step step,
      List<CatalogBindingHint> hints) {
    Optional<CatalogBindingHint> hint = findHint(step, hints);
    if (hint.isPresent()) {
      CatalogBindingHint observed = hint.get();
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
      // Stale or partial hint: fall through to normal catalog-first resolution.
    }

    CatalogBindingMatcher.MatchResult match = matcher.match(flow, step);
    if (match instanceof CatalogBindingMatcher.MatchResult.Exact exact) {
      return toExisting(step.stepId(), exact.match(), resolveRelease(flow, exact.match()));
    }
    if (match instanceof CatalogBindingMatcher.MatchResult.Ambiguous ambiguous) {
      return new BindingResolutionResult.NeedsInput(step.stepId(), ambiguous.candidateIds());
    }
    if (flow.bindingResolutionPolicy()
        == NormalizedDesignFlow.BindingResolutionPolicy.CATALOG_ONLY) {
      return new BindingResolutionResult.Failed(
          step.stepId(),
          "operation was not found in the local catalog; APIHub lookup is disabled for this flow",
          StageOutcomeClass.DOMAIN_FAILURE);
    }
    return resolveViaApiHub(conversationId, flow, step);
  }

  private BindingResolutionResult resolveViaApiHub(
      String conversationId, NormalizedDesignFlow flow, NormalizedDesignFlow.Step step) {
    String query = CatalogStrings.blankToNull(step.operationQuery());
    if (query == null) {
      return new BindingResolutionResult.Failed(
          step.stepId(),
          "service-call step requires an operationQuery",
          StageOutcomeClass.DOMAIN_FAILURE);
    }
    String release = flowRelease(flow);
    int attempts = MAX_TECHNICAL_RETRIES + 1;
    BindingResolutionResult lastTechnical = null;
    for (int attempt = 1; attempt <= attempts; attempt++) {
      try {
        return searchAndImport(conversationId, step, query, release);
      } catch (TechnicalBindingException technical) {
        lastTechnical =
            new BindingResolutionResult.Failed(
                step.stepId(),
                technical.getMessage(),
                StageOutcomeClass.RETRYABLE_TECHNICAL_FAILURE);
      } catch (DomainBindingException domain) {
        return new BindingResolutionResult.Failed(
            step.stepId(), domain.getMessage(), StageOutcomeClass.DOMAIN_FAILURE);
      } catch (AmbiguousBindingException ambiguous) {
        return new BindingResolutionResult.NeedsInput(step.stepId(), ambiguous.candidateIds());
      }
    }
    return lastTechnical != null
        ? lastTechnical
        : new BindingResolutionResult.Failed(
            step.stepId(),
            "APIHub resolution failed",
            StageOutcomeClass.RETRYABLE_TECHNICAL_FAILURE);
  }

  private BindingResolutionResult searchAndImport(
      String conversationId, NormalizedDesignFlow.Step step, String query, String release) {
    // Execution reaches API Hub only where it established the miss itself. #692 removes this
    // branch: a step without a binding will return to API resolution instead of searching here.
    searchAuthorizations.issue(conversationId, step.stepId(), query, "execution catalog miss");
    String toolResult;
    try {
      toolResult =
          apiHubMcpTools.searchApiOperations(
              query, ApiHubRequirementRefs.DEFAULT_API_TYPE, release, 0, 100, null);
    } catch (RuntimeException ex) {
      if (isTechnicalFailure(ex)) {
        throw new TechnicalBindingException(
            "APIHub transport/session failure: " + safeMessage(ex), ex);
      }
      throw new DomainBindingException("APIHub search rejected: " + safeMessage(ex), ex);
    }
    if (isTechnicalToolResult(toolResult)) {
      throw new TechnicalBindingException(toolResult);
    }
    if (isEmptyOrErrorToolResult(toolResult)) {
      throw new DomainBindingException("APIHub operation not found for query: " + query);
    }
    List<String> candidateIds = ApiHubSearchHitParser.collectOperationIds(toolResult);
    ApiHubRequirementRefs refs =
        ApiHubSearchHitParser.parseSingleClearHit(
            toolResult, ApiHubRequirementRefs.DEFAULT_API_TYPE, null);
    if (refs == null) {
      if (candidateIds.size() > 1) {
        throw new AmbiguousBindingException(candidateIds);
      }
      throw new DomainBindingException("APIHub operation not found for query: " + query);
    }
    if (!refs.hasImportableRefs()) {
      throw new DomainBindingException("APIHub hit lacks importable refs for query: " + query);
    }
    ApiHubSpecificationImportResult imported;
    try {
      Uni<ApiHubSpecificationImportResult> importUni =
          catalogMutationGateway.importApiHubSpecification(conversationId, refs);
      imported = importUni.await().atMost(IMPORT_TIMEOUT);
    } catch (Exception ex) {
      if (isTechnicalFailure(ex)) {
        throw new TechnicalBindingException(
            "APIHub import transport/session failure: " + safeMessage(ex), ex);
      }
      throw new DomainBindingException("APIHub specification import rejected: " + safeMessage(ex), ex);
    }
    if (imported == null
        || CatalogStrings.blankToNull(imported.systemId()) == null
        || CatalogStrings.blankToNull(imported.specificationGroupId()) == null
        || CatalogStrings.blankToNull(imported.specificationId()) == null) {
      throw new DomainBindingException("APIHub specification import returned incomplete catalog IDs");
    }
    String operationId =
        imported.catalogOperationId().orElse(CatalogStrings.blankToNull(refs.operationId()));
    if (operationId == null) {
      throw new DomainBindingException("APIHub import did not resolve integrationOperationId");
    }
    String evidence =
        "apihub-import:"
            + (imported.importId() == null ? imported.specificationId() : imported.importId());
    return new BindingResolutionResult.Resolved(
        new CatalogBindingResolution(
            step.stepId(),
            CatalogBindingResolution.Source.APIHUB_IMPORT,
            imported.systemId(),
            imported.specificationGroupId(),
            imported.specificationId(),
            operationId,
            refs.packageId(),
            CatalogStrings.blankToNull(refs.version()) == null ? "unknown" : refs.version(),
            evidence));
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

  private static Optional<CatalogBindingHint> findHint(
      NormalizedDesignFlow.Step step, List<CatalogBindingHint> hints) {
    String query = CatalogStrings.blankToNull(step.operationQuery());
    for (CatalogBindingHint hint : hints) {
      if (hint == null) {
        continue;
      }
      if (query != null && query.equals(hint.operationQuery())) {
        return Optional.of(hint);
      }
      for (String factId : step.sourceFactIds()) {
        if (factId != null && factId.equals(hint.serviceCallSourceFactId())) {
          return Optional.of(hint);
        }
      }
    }
    return Optional.empty();
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

  private static String resolveRelease(
      NormalizedDesignFlow flow, CatalogBindingMatcher.CatalogMatch match) {
    String fromFlow = flowRelease(flow);
    if (fromFlow != null) {
      return fromFlow;
    }
    return "catalog";
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

  static boolean isTechnicalFailure(Throwable error) {
    Throwable cursor = error;
    while (cursor != null) {
      if (cursor instanceof TimeoutException) {
        return true;
      }
      String message = cursor.getMessage();
      if (message != null) {
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("timeout")
            || lower.contains("timed out")
            || lower.contains("invalid session")
            || lower.contains("mcp-session")
            || lower.contains("connection reset")
            || lower.contains("connection refused")
            || lower.contains("unavailable")
            || lower.contains("http 5")
            || lower.contains("transport")) {
          return true;
        }
      }
      cursor = cursor.getCause();
    }
    return false;
  }

  static boolean isTechnicalToolResult(String toolResult) {
    if (toolResult == null || toolResult.isBlank()) {
      return false;
    }
    String lower = toolResult.toLowerCase(Locale.ROOT);
    return lower.startsWith("error ")
        && (lower.contains("invalid session")
            || lower.contains("timeout")
            || lower.contains("timed out")
            || lower.contains("transport")
            || lower.contains("connection"));
  }

  private static boolean isEmptyOrErrorToolResult(String toolResult) {
    if (toolResult == null || toolResult.isBlank()) {
      return true;
    }
    String lower = toolResult.toLowerCase(Locale.ROOT);
    if (lower.startsWith("error ")) {
      return true;
    }
    return lower.contains("no results") || lower.contains("\"operations\":[]");
  }

  private static String safeMessage(Throwable error) {
    if (error == null || error.getMessage() == null || error.getMessage().isBlank()) {
      return "unknown failure";
    }
    return error.getMessage();
  }

  static final class TechnicalBindingException extends RuntimeException {
    TechnicalBindingException(String message) {
      super(message);
    }

    TechnicalBindingException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  static final class DomainBindingException extends RuntimeException {
    DomainBindingException(String message) {
      super(message);
    }

    DomainBindingException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  static final class AmbiguousBindingException extends RuntimeException {
    private final List<String> candidateIds;

    AmbiguousBindingException(List<String> candidateIds) {
      super("ambiguous APIHub matches");
      this.candidateIds = List.copyOf(candidateIds);
    }

    List<String> candidateIds() {
      return candidateIds;
    }
  }
}
