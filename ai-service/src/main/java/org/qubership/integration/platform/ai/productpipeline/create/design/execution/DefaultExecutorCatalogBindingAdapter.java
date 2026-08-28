package org.qubership.integration.platform.ai.productpipeline.create.design.execution;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
      BindingResolutionResult result = resolveCall(call, hintList);
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
      SemanticNode.ServiceCall call, List<CatalogBindingHint> hints) {
    HintLookup lookup = findHint(call, hints);
    if (lookup.failureReason() != null) {
      return new BindingResolutionResult.Failed(
          call.serviceCallId(), lookup.failureReason(), StageOutcomeClass.DOMAIN_FAILURE);
    }
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
    List<CatalogBindingHint> matches = new ArrayList<>();
    for (CatalogBindingHint hint : hints) {
      if (hint == null) {
        continue;
      }
      if (!"2".equals(hint.schemaVersion())) {
        return HintLookup.failed("catalog binding hint must use schemaVersion=2");
      }
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

  private record HintLookup(CatalogBindingHint hint, String failureReason) {
    static HintLookup found(CatalogBindingHint hint) {
      return new HintLookup(hint, null);
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

}
