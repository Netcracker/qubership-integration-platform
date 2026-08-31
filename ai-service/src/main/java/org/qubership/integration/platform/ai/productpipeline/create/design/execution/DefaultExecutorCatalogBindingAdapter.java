package org.qubership.integration.platform.ai.productpipeline.create.design.execution;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.qubership.integration.platform.ai.catalog.binding.CatalogOperationProjector;
import org.qubership.integration.platform.ai.catalog.binding.ResolvedServiceCallBinding;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogMatch;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogSystemReadTool;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;
import org.qubership.integration.platform.ai.plan.mapping.schema.OperationSchemaLoader;
import org.qubership.integration.platform.ai.plan.mapping.schema.OperationSchemaMaps;
import org.qubership.integration.platform.ai.productpipeline.artifact.ApprovalRecordV2;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
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

  private final CatalogSystemReadTool catalogReadTool;
  private final OperationSchemaLoader schemaLoader;

  @Inject
  public DefaultExecutorCatalogBindingAdapter(
      CatalogSystemReadTool catalogReadTool, OperationSchemaLoader schemaLoader) {
    this.catalogReadTool = Objects.requireNonNull(catalogReadTool, "catalogReadTool");
    this.schemaLoader = Objects.requireNonNull(schemaLoader, "schemaLoader");
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
    List<ResolvedServiceCallBinding> resolved = new ArrayList<>();
    for (SemanticNode.ServiceCall call : calls) {
      BindingResolutionResult result =
          resolveOccurrence(
              conversationId, call.nodeId(), call.serviceCallId(), hintList, true);
      results.add(result);
      if (result instanceof BindingResolutionResult.Resolved success) {
        resolved.add(success.binding());
        persistSchemas(conversationId, success.binding());
      }
    }
    for (SemanticNode.Trigger trigger : triggers(revision)) {
      boolean required = "async-api-trigger".equals(trigger.capabilityKey());
      BindingResolutionResult result =
          resolveOccurrence(
              conversationId, trigger.nodeId(), trigger.interactionId(), hintList, required);
      if (result == null) {
        continue;
      }
      results.add(result);
      if (result instanceof BindingResolutionResult.Resolved success) {
        resolved.add(success.binding());
        persistSchemas(conversationId, success.binding());
      }
    }
    List<String> callIds =
        calls.stream().map(SemanticNode.ServiceCall::serviceCallId).toList();
    List<ResolvedServiceCallBinding> callBindings =
        resolved.stream().filter(binding -> callIds.contains(binding.serviceCallId())).toList();
    if (callBindings.size() == callIds.size()) {
      ResolvedServiceCallBinding.requireExactOwners(callIds, callBindings);
    }
    return List.copyOf(results);
  }

  private BindingResolutionResult resolveOccurrence(
      String conversationId,
      String targetNodeId,
      String occurrenceId,
      List<CatalogBindingHint> hints,
      boolean required) {
    HintLookup lookup = findHint(occurrenceId, hints);
    if (lookup.failureReason() != null) {
      return new BindingResolutionResult.Failed(
          occurrenceId, lookup.failureReason(), StageOutcomeClass.DOMAIN_FAILURE);
    }
    if (lookup.hint() == null) {
      if (!required) {
        return null;
      }
      return new BindingResolutionResult.Failed(
          occurrenceId,
          "no catalog binding hint for interactionId=" + occurrenceId,
          StageOutcomeClass.DOMAIN_FAILURE);
    }
    CatalogBindingHint observed = lookup.hint();
    Optional<RevalidatedCatalogMatch> revalidated = revalidateHint(conversationId, observed);
    if (revalidated.isPresent()) {
      return toExisting(targetNodeId, occurrenceId, revalidated.get(), observed.release());
    }
    return new BindingResolutionResult.Failed(
        occurrenceId,
        "the approved catalog binding no longer resolves (operation "
            + observed.integrationOperationId()
            + "); resolve this interaction again before execution",
        StageOutcomeClass.DOMAIN_FAILURE,
        "catalog operation");
  }

  private Optional<RevalidatedCatalogMatch> revalidateHint(
      String conversationId, CatalogBindingHint hint) {
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
            .listCatalogOperations(
                conversationId, hint.specificationId(), hint.systemId(), null)
            .stream()
            .filter(candidate -> hint.integrationOperationId().equals(candidate.id()))
            .findFirst()
            .orElse(null);
    if (op == null) {
      return Optional.empty();
    }
    return Optional.of(
        new RevalidatedCatalogMatch(
            new CatalogMatch(
                hint.systemId(),
                hint.specificationGroupId(),
                hint.specificationId(),
                hint.integrationOperationId(),
                system.name(),
                system.protocol(),
                op.method(),
                op.path(),
                op.name(),
                hint.evidenceRef()),
            system.type(),
            op));
  }

  private void persistSchemas(String compilationId, ResolvedServiceCallBinding binding) {
    OperationSchemaMaps maps = schemaLoader.load(binding.operationId());
    if (maps == null) {
      return;
    }
    soleContentType(maps.requestByContentType())
        .ifPresent(
            contentType ->
                schemaLoader.persistRequest(
                    compilationId,
                    binding.serviceCallId(),
                    binding.operationId(),
                    contentType));
    soleHttpSuccessResponse(maps.responseByStatusThenContentType())
        .ifPresent(
            selection ->
                schemaLoader.persistResponse(
                    compilationId,
                    binding.serviceCallId(),
                    binding.operationId(),
                    selection.contentType(),
                    selection.responseCode()));
  }

  private static Optional<String> soleContentType(Map<String, ?> byContentType) {
    if (byContentType == null || byContentType.isEmpty()) {
      return Optional.empty();
    }
    List<String> keys = byContentType.keySet().stream().filter(k -> !"parameters".equals(k)).toList();
    return keys.size() == 1 ? Optional.of(keys.getFirst()) : Optional.empty();
  }

  private static Optional<ResponseSchemaSelection> soleHttpSuccessResponse(
      Map<String, Map<String, JsonNode>> byStatusThenContentType) {
    if (byStatusThenContentType == null || byStatusThenContentType.isEmpty()) {
      return Optional.empty();
    }
    List<Map.Entry<String, Map<String, JsonNode>>> success = new ArrayList<>();
    for (Map.Entry<String, Map<String, JsonNode>> entry : byStatusThenContentType.entrySet()) {
      if (isHttpSuccessStatus(entry.getKey())) {
        success.add(entry);
      }
    }
    if (success.size() != 1) {
      return Optional.empty();
    }
    Map.Entry<String, Map<String, JsonNode>> statusEntry = success.getFirst();
    return soleContentType(statusEntry.getValue())
        .map(contentType -> new ResponseSchemaSelection(statusEntry.getKey(), contentType));
  }

  private static boolean isHttpSuccessStatus(String status) {
    return status != null
        && status.length() == 3
        && status.charAt(0) == '2'
        && Character.isDigit(status.charAt(1))
        && Character.isDigit(status.charAt(2));
  }

  private record ResponseSchemaSelection(String responseCode, String contentType) {}

  private static BindingResolutionResult toExisting(
      String targetNodeId,
      String serviceCallId,
      RevalidatedCatalogMatch revalidated,
      String release) {
    try {
      CatalogMatch match = revalidated.match();
      ResolvedServiceCallBinding binding =
          CatalogOperationProjector.project(
              targetNodeId,
              serviceCallId,
              new CatalogRestClient.SystemDto(
                  match.systemId(), match.systemName(), revalidated.systemType(), match.protocol()),
              match.specificationGroupId(),
              match.specificationId(),
              revalidated.operation(),
              ResolvedServiceCallBinding.Source.EXISTING_CATALOG,
              CatalogStrings.blankToNull(release) == null ? "catalog" : release,
              match.evidenceRef(),
              null);
      return new BindingResolutionResult.Resolved(binding);
    } catch (IllegalArgumentException exception) {
      return new BindingResolutionResult.Failed(
          serviceCallId, exception.getMessage(), StageOutcomeClass.DOMAIN_FAILURE);
    }
  }

  private static HintLookup findHint(String occurrenceId, List<CatalogBindingHint> hints) {
    List<CatalogBindingHint> matches = new ArrayList<>();
    for (CatalogBindingHint hint : hints) {
      if (hint == null) {
        continue;
      }
      if (!"3".equals(hint.schemaVersion())) {
        return HintLookup.failed(
            "catalog binding hint must use schemaVersion=3, got " + hint.schemaVersion());
      }
      if (occurrenceId.equals(hint.interactionId())) {
        matches.add(hint);
      }
    }
    if (matches.size() == 1) {
      return HintLookup.found(matches.getFirst());
    }
    if (matches.size() > 1) {
      return HintLookup.failed(
          "multiple catalog binding hints for interactionId=" + occurrenceId);
    }
    return HintLookup.absent();
  }

  private record HintLookup(CatalogBindingHint hint, String failureReason) {
    static HintLookup found(CatalogBindingHint hint) {
      return new HintLookup(hint, null);
    }

    static HintLookup failed(String reason) {
      return new HintLookup(null, reason);
    }

    static HintLookup absent() {
      return new HintLookup(null, null);
    }
  }

  private record RevalidatedCatalogMatch(
      CatalogMatch match, String systemType, CatalogRestClient.OperationDto operation) {}

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

  private static List<SemanticNode.Trigger> triggers(ChainSemanticRevision revision) {
    List<SemanticNode.Trigger> triggers = new ArrayList<>();
    for (SemanticNode node : revision.nodes()) {
      if (node instanceof SemanticNode.Trigger trigger) {
        triggers.add(trigger);
      }
    }
    return triggers;
  }
}
