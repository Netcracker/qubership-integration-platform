package org.qubership.integration.platform.ai.productpipeline.create.design.execution;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogSystemReadTool;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;
import org.qubership.integration.platform.ai.productpipeline.artifact.ApprovalRecordV2;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingResolution;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode;
import org.qubership.integration.platform.ai.productpipeline.profile.ApprovalPolicy;

/**
 * Revalidates the catalog bindings an approved design was built on. Requires an implementation
 * approval carrying {@link ApprovalPolicy#CATALOG_FIRST_V1}.
 *
 * <p>Execution reads the catalog and nothing else. A binding that no longer resolves stops the run
 * instead of importing a different operation.
 */
@ApplicationScoped
public class DefaultExecutorCatalogBindingAdapter implements ExecutorCatalogBindingAdapter {

  private static final Pattern METHOD_PATH =
      Pattern.compile("(?i)\\b(GET|POST|PUT|PATCH|DELETE)\\s+(/\\S+)");

  private final CatalogBindingMatcher matcher;
  private final CatalogSystemReadTool catalogReadTool;

  @Inject
  public DefaultExecutorCatalogBindingAdapter(
      CatalogBindingMatcher matcher, CatalogSystemReadTool catalogReadTool) {
    this.matcher = Objects.requireNonNull(matcher, "matcher");
    this.catalogReadTool = Objects.requireNonNull(catalogReadTool, "catalogReadTool");
  }

  @Override
  public List<BindingResolutionResult> resolve(
      String conversationId,
      ChainSemanticRevision revision,
      List<CatalogBindingHint> hints,
      ApprovalRecordV2 approval) {
    Objects.requireNonNull(conversationId, "conversationId");
    Objects.requireNonNull(revision, "revision");
    requireMatchingApproval(approval);
    List<CatalogBindingHint> hintList = hints == null ? List.of() : hints;
    List<SemanticNode.ServiceCall> calls = serviceCalls(revision);
    List<BindingResolutionResult> results = new ArrayList<>();
    List<CatalogBindingResolution> resolved = new ArrayList<>();
    for (SemanticNode.ServiceCall call : calls) {
      BindingResolutionResult result = resolveCall(revision, call, hintList);
      results.add(result);
      if (result instanceof BindingResolutionResult.Resolved success) {
        resolved.add(success.binding());
      }
    }
    if (results.stream().allMatch(BindingResolutionResult.Resolved.class::isInstance)) {
      matcher.match(calls, resolved);
    }
    return List.copyOf(results);
  }

  private BindingResolutionResult resolveCall(
      ChainSemanticRevision revision,
      SemanticNode.ServiceCall call,
      List<CatalogBindingHint> hints) {
    HintLookup lookup = findHint(call, hints);
    if (lookup.failureReason() != null) {
      return new BindingResolutionResult.Failed(
          call.serviceCallId(), lookup.failureReason(), StageOutcomeClass.DOMAIN_FAILURE);
    }
    if (lookup.hint() != null) {
      CatalogBindingHint observed = lookup.hint();
      Optional<CatalogBindingMatcher.CatalogMatch> revalidated = revalidateHint(observed);
      if (revalidated.isPresent()) {
        return toExisting(call.serviceCallId(), revalidated.get(), observed.release());
      }
      return new BindingResolutionResult.Failed(
          call.serviceCallId(),
          "the approved catalog binding no longer resolves (operation "
              + observed.integrationOperationId()
              + "); resolve this service call again before execution",
          StageOutcomeClass.DOMAIN_FAILURE,
          "catalog operation");
    }

    CatalogBindingMatcher.MatchResult match =
        matchOperation(call.operation(), resolveRelease(revision));
    if (match instanceof CatalogBindingMatcher.MatchResult.Exact exact) {
      return toExisting(call.serviceCallId(), exact.match(), resolveRelease(revision));
    }
    if (match instanceof CatalogBindingMatcher.MatchResult.Ambiguous ambiguous) {
      return new BindingResolutionResult.NeedsInput(call.serviceCallId(), ambiguous.candidateIds());
    }
    return new BindingResolutionResult.Failed(
        call.serviceCallId(),
        "no catalog binding for this service call; resolve it during requirement gathering,"
            + " where API Hub discovery and specification import happen",
        StageOutcomeClass.DOMAIN_FAILURE);
  }

  private Optional<CatalogBindingMatcher.CatalogMatch> revalidateHint(CatalogBindingHint hint) {
    List<CatalogRestClient.SystemDto> systems =
        catalogReadTool.searchCatalogSystems(hint.systemId());
    CatalogRestClient.SystemDto system =
        systems.stream().filter(s -> hint.systemId().equals(s.id())).findFirst().orElse(null);
    if (system == null) {
      return Optional.empty();
    }
    CatalogRestClient.SpecificationDto spec =
        catalogReadTool.getApiSpecifications(hint.systemId()).stream()
            .filter(
                s ->
                    hint.specificationId().equals(s.id())
                        && hint.specificationGroupId().equals(s.specificationGroupId()))
            .findFirst()
            .orElse(null);
    if (spec == null) {
      return Optional.empty();
    }
    CatalogRestClient.OperationDto op =
        catalogReadTool
            .listCatalogOperations(hint.specificationId(), hint.systemId(), null)
            .stream()
            .filter(candidate -> hint.integrationOperationId().equals(candidate.id()))
            .findFirst()
            .orElse(null);
    if (op == null) {
      return Optional.empty();
    }
    return Optional.of(
        new CatalogBindingMatcher.CatalogMatch(
            hint.systemId(),
            hint.specificationGroupId(),
            hint.specificationId(),
            hint.integrationOperationId(),
            system.name(),
            system.protocol(),
            op.method(),
            op.path(),
            op.name(),
            hint.evidenceRef()));
  }

  private CatalogBindingMatcher.MatchResult matchOperation(String operation, String release) {
    String query = CatalogStrings.blankToNull(operation);
    if (query == null) {
      return new CatalogBindingMatcher.MatchResult.None();
    }
    ParsedQuery parsed = parseQuery(query);
    List<CatalogBindingMatcher.CatalogMatch> hits = new ArrayList<>();
    for (CatalogRestClient.SystemDto system : catalogReadTool.searchCatalogSystems(query)) {
      if (system == null || CatalogStrings.blankToNull(system.id()) == null) {
        continue;
      }
      for (CatalogRestClient.SpecificationDto spec :
          catalogReadTool.getApiSpecifications(system.id())) {
        if (spec == null
            || CatalogStrings.blankToNull(spec.id()) == null
            || CatalogStrings.blankToNull(spec.specificationGroupId()) == null) {
          continue;
        }
        if (release != null
            && CatalogStrings.blankToNull(spec.name()) != null
            && !spec.name().toLowerCase(Locale.ROOT).contains(release.toLowerCase(Locale.ROOT))) {
          continue;
        }
        for (CatalogRestClient.OperationDto op :
            catalogReadTool.listCatalogOperations(spec.id(), system.id(), null)) {
          if (op == null || CatalogStrings.blankToNull(op.id()) == null) {
            continue;
          }
          if (!operationAgrees(parsed, query, op)) {
            continue;
          }
          hits.add(
              new CatalogBindingMatcher.CatalogMatch(
                  system.id(),
                  spec.specificationGroupId(),
                  spec.id(),
                  op.id(),
                  system.name(),
                  system.protocol(),
                  op.method(),
                  op.path(),
                  op.name(),
                  "catalog-read:" + system.id() + "/" + spec.id() + "/" + op.id()));
        }
      }
    }
    if (hits.isEmpty()) {
      return new CatalogBindingMatcher.MatchResult.None();
    }
    if (hits.size() == 1) {
      return new CatalogBindingMatcher.MatchResult.Exact(hits.getFirst());
    }
    return new CatalogBindingMatcher.MatchResult.Ambiguous(
        hits.stream().map(CatalogBindingMatcher.CatalogMatch::integrationOperationId).toList());
  }

  private static BindingResolutionResult.Resolved toExisting(
      String serviceCallId, CatalogBindingMatcher.CatalogMatch match, String release) {
    return new BindingResolutionResult.Resolved(
        new CatalogBindingResolution(
            serviceCallId,
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
      SemanticNode.ServiceCall call, List<CatalogBindingHint> hints) {
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
        if (call.serviceCallId().equals(hint.serviceCallId())) {
          matches.add(hint);
        }
      }
      if (matches.size() == 1) {
        return HintLookup.found(matches.getFirst());
      }
      if (matches.size() > 1) {
        return HintLookup.failed(
            "multiple catalog binding hints for serviceCallId=" + call.serviceCallId());
      }
      return HintLookup.failed(
          "no catalog binding hint for serviceCallId=" + call.serviceCallId());
    }
    String query = CatalogStrings.blankToNull(call.operation());
    for (CatalogBindingHint hint : v1Hints) {
      if (query != null && query.equals(hint.operationQuery())) {
        return HintLookup.found(hint);
      }
      if (call.serviceCallId().equals(hint.serviceCallId())) {
        return HintLookup.found(hint);
      }
    }
    return HintLookup.none();
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

  private static List<SemanticNode.ServiceCall> serviceCalls(ChainSemanticRevision revision) {
    List<SemanticNode.ServiceCall> calls = new ArrayList<>();
    for (SemanticNode node : revision.nodes()) {
      if (node instanceof SemanticNode.ServiceCall call) {
        calls.add(call);
      }
    }
    return calls;
  }

  private static String resolveRelease(ChainSemanticRevision revision) {
    String fromRevision = keyedValue(revision, "release");
    if (fromRevision != null) {
      return fromRevision;
    }
    return keyedValue(revision, "version");
  }

  private static String keyedValue(ChainSemanticRevision revision, String key) {
    String prefix = key.toLowerCase(Locale.ROOT) + ":";
    for (String line : revision.constraints()) {
      String value = keyedValue(line, prefix);
      if (value != null) {
        return value;
      }
    }
    for (String line : revision.assumptions()) {
      String value = keyedValue(line, prefix);
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  private static String keyedValue(String line, String prefix) {
    if (line == null) {
      return null;
    }
    String trimmed = line.trim();
    if (trimmed.toLowerCase(Locale.ROOT).startsWith(prefix)) {
      String value = trimmed.substring(prefix.length()).trim();
      return value.isEmpty() ? null : value;
    }
    return null;
  }

  private static ParsedQuery parseQuery(String operationQuery) {
    Matcher matcher = METHOD_PATH.matcher(operationQuery);
    String method = null;
    String path = null;
    if (matcher.find()) {
      method = matcher.group(1).toUpperCase(Locale.ROOT);
      path = matcher.group(2);
    }
    return new ParsedQuery(method, path, operationQuery.trim());
  }

  private static boolean operationAgrees(
      ParsedQuery parsed, String operationQuery, CatalogRestClient.OperationDto op) {
    if (parsed.method() != null
        && CatalogStrings.blankToNull(op.method()) != null
        && !parsed.method().equalsIgnoreCase(op.method().trim())) {
      return false;
    }
    if (parsed.path() != null
        && CatalogStrings.blankToNull(op.path()) != null
        && !parsed.path().equalsIgnoreCase(op.path().trim())) {
      return false;
    }
    if (parsed.method() != null && parsed.path() != null) {
      return true;
    }
    String needle = operationQuery.toLowerCase(Locale.ROOT);
    return containsIgnoreCase(op.name(), needle)
        || containsIgnoreCase(op.id(), needle)
        || containsIgnoreCase(op.path(), needle);
  }

  private static boolean containsIgnoreCase(String value, String needle) {
    return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
  }

  private record ParsedQuery(String method, String path, String raw) {}
}
