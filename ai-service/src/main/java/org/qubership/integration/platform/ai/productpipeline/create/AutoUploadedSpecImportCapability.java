package org.qubership.integration.platform.ai.productpipeline.create;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.attachment.AttachmentKeys;
import org.qubership.integration.platform.ai.chat.attachment.UploadedSpecAttachment;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.decision.UploadedSpecsApprovalHandler;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.integration.catalog.materialize.UploadedSpecImportOutcome;
import org.qubership.integration.platform.ai.integration.catalog.pipeline.CatalogMutationGateway;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.productpipeline.artifact.ApprovalRecordV2;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.SkillActivitySupport;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.CatalogBindingMatcher;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;

/**
 * Pipeline stage that imports every uploaded API specification into the runtime catalog after the
 * user approves the import decision. The stage reads attachment keys from the conversation state,
 * resolves the {@code approval-record} artifact, and delegates the actual import to the catalog
 * mutation gateway.
 */
@ApplicationScoped
public class AutoUploadedSpecImportCapability implements StageCapability {

  public static final String CAPABILITY_ID = "auto-uploaded-spec-import";

  private static final Logger LOG = Logger.getLogger(AutoUploadedSpecImportCapability.class);

  /** Allowed S3 object-key characters: alphanumerics plus AWS-safe punctuation and path separators. */
  private static final Pattern SAFE_S3_KEY =
      Pattern.compile("^[A-Za-z0-9!_.*'()\\-/]+$");

  private static final Pattern UPLOADED_SPEC_FACT =
      Pattern.compile(
          "(?i)^Uploaded (OPENAPI|ASYNCAPI) spec (.+) operation (\\S+) (?:channel|path) (.+)$");

  private final CatalogMutationGateway catalogMutationGateway;
  private final ConversationService conversationService;
  private final ProductPipelineArtifactStore artifactStore;
  private final UploadedSpecsApprovalHandler uploadedSpecsApprovalHandler;
  private final CatalogBindingMatcher catalogBindingMatcher;
  private final RequirementDraftStore draftStore;

  @Inject
  public AutoUploadedSpecImportCapability(
      CatalogMutationGateway catalogMutationGateway,
      ConversationService conversationService,
      ProductPipelineArtifactStore artifactStore,
      UploadedSpecsApprovalHandler uploadedSpecsApprovalHandler,
      CatalogBindingMatcher catalogBindingMatcher,
      RequirementDraftStore draftStore) {
    this.catalogMutationGateway =
        Objects.requireNonNull(catalogMutationGateway, "catalogMutationGateway");
    this.conversationService =
        Objects.requireNonNull(conversationService, "conversationService");
    this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
    this.uploadedSpecsApprovalHandler =
        Objects.requireNonNull(uploadedSpecsApprovalHandler, "uploadedSpecsApprovalHandler");
    this.catalogBindingMatcher = catalogBindingMatcher;
    this.draftStore = draftStore;
  }

  @Override
  public String capabilityId() {
    return CAPABILITY_ID;
  }

  @Override
  public Multi<CapabilitySignal> execute(StageExecutionContext context) {
    Objects.requireNonNull(context, "context");
    String conversationId = context.conversationId();

    Optional<RequirementDraft> inputDraft = resolveDraftFromInputRefs(context);
    if (inputDraft.isPresent() && hasCatalogBinding(inputDraft.get())) {
      RequirementDraft draft = inputDraft.get();
      LOG.infof(
          "Skipped uploaded-spec import: requirement draft already has catalog binding conversationId=%s",
          conversationId);
      return Multi.createFrom().item(succeededWithDraft(context, draft));
    }

    Optional<CompilationArtifacts.Reference> approvalRef = findApprovalReference(context);
    List<String> approvalKeys =
        approvalRef.map(ref -> attachmentKeys(context, ref)).orElse(List.of());
    List<String> currentKeys = conversationService.getAllowedAttachmentKeys(conversationId);
    LOG.infof(
        "auto-uploaded-spec-import conversationId=%s currentKeys=%d approvalKeys=%d inputDraft=%s",
        conversationId,
        currentKeys == null ? 0 : currentKeys.size(),
        approvalKeys.size(),
        inputDraft.map(d -> "present[binding=" + hasCatalogBinding(d) + "]").orElse("none"));
    if ((currentKeys == null || currentKeys.isEmpty()) && approvalKeys.isEmpty()) {
      LOG.infof(
          "auto-uploaded-spec-import: no attachment keys conversationId=%s; skipping import",
          conversationId);
      return Multi.createFrom()
          .item(succeeded(context, "No uploaded API specifications to import", null));
    }

    boolean approved =
        approvalRef.isPresent() && isApproved(context.runId(), conversationId, approvalRef.get());
    LOG.infof(
        "auto-uploaded-spec-import: approvalRef=%s approved=%s conversationId=%s",
        approvalRef.map(CompilationArtifacts.Reference::toString).orElse("none"),
        approved,
        conversationId);
    if (!approved) {
      return Multi.createFrom()
          .item(
              completed(
                  StageOutcome.of(
                      StageOutcomeClass.NEEDS_INPUT,
                      "Approval required to import uploaded API specifications")));
    }

    List<String> keys =
        (currentKeys != null && !currentKeys.isEmpty()) ? currentKeys : approvalKeys;
    keys = AttachmentKeys.normalize(keys);
    List<UploadedSpecAttachment> attachments = new ArrayList<>();
    for (String key : keys) {
      if (key == null || key.isBlank()) {
        LOG.warnf("Skipping blank uploaded-spec key conversationId=%s", conversationId);
        continue;
      }
      if (!SAFE_S3_KEY.matcher(key).matches()) {
        LOG.warnf(
            "Skipping uploaded-spec key with unsafe characters conversationId=%s key=%s",
            conversationId, key);
        continue;
      }
      attachments.add(new UploadedSpecAttachment(key, filenameFromKey(key)));
    }
    if (attachments.isEmpty()) {
      LOG.warnf(
          "auto-uploaded-spec-import: all attachment keys were unsafe or blank conversationId=%s",
          conversationId);
      return Multi.createFrom()
          .item(
              completed(
                  StageOutcome.of(
                      StageOutcomeClass.NEEDS_INPUT,
                      "No valid uploaded API specification keys to import")));
    }

    SkillActivitySupport.bindParents(CAPABILITY_ID);
    return Multi.createBy()
        .concatenating()
        .streams(
            Multi.createFrom().item(SkillActivitySupport.running(CAPABILITY_ID)),
            importAttachments(context, attachments))
        .onTermination()
        .invoke((failure, cancelled) -> SkillActivitySupport.clearParents());
  }

  private Multi<CapabilitySignal> importAttachments(
      StageExecutionContext context, List<UploadedSpecAttachment> attachments) {
    String conversationId = context.conversationId();
    Uni<CapabilitySignal.Completed> importWork =
        Multi.createFrom()
            .iterable(attachments)
            .onItem()
            .transformToUniAndMerge(
                attachment ->
                    catalogMutationGateway
                        .importUploadedSpec(conversationId, attachment)
                        .onFailure()
                        .recoverWithItem(
                            error -> {
                              LOG.errorf(
                                  error,
                                  "Failed to import uploaded spec conversationId=%s s3Key=%s",
                                  conversationId,
                                  attachment.s3Key());
                              return null;
                            }))
            .collect()
            .asList()
            .onItem()
            .transform(outcomes -> importOutcome(context, outcomes));

    return Multi.createFrom()
        .uni(importWork)
        .onItem()
        .transformToMultiAndConcatenate(
            completed -> {
              if (completed.outcome().outcomeClass() == StageOutcomeClass.NEEDS_INPUT) {
                return Multi.createFrom()
                    .items(SkillActivitySupport.error(CAPABILITY_ID), completed);
              }
              return Multi.createFrom()
                  .iterable(SkillActivitySupport.wrapTerminal(CAPABILITY_ID, List.of(completed)));
            })
        .onFailure()
        .recoverWithMulti(
            error -> {
              LOG.errorf(
                  error,
                  "auto-uploaded-spec-import failed conversationId=%s",
                  conversationId);
              return Multi.createFrom()
                  .items(
                      SkillActivitySupport.error(CAPABILITY_ID),
                      completed(
                          StageOutcome.of(
                              StageOutcomeClass.NEEDS_INPUT,
                              "Failed to import uploaded API specifications")));
            });
  }

  private CapabilitySignal.Completed importOutcome(
      StageExecutionContext context, List<UploadedSpecImportOutcome> outcomes) {
    long succeeded = outcomes.stream().filter(Objects::nonNull).count();
    if (succeeded == 0) {
      return completed(
          StageOutcome.of(
              StageOutcomeClass.NEEDS_INPUT, "Failed to import all uploaded API specifications"));
    }
    if (succeeded < outcomes.size()) {
      LOG.warnf(
          "Imported %d of %d uploaded specifications conversationId=%s",
          succeeded,
          outcomes.size(),
          context.conversationId());
    }
    UploadedSpecImportOutcome first = outcomes.stream().filter(Objects::nonNull).findFirst().orElse(null);
    return succeeded(context, "Uploaded API specifications imported", first);
  }

  private Optional<CompilationArtifacts.Reference> findApprovalReference(
      StageExecutionContext context) {
    String conversationId = context.conversationId();
    String currentHash = uploadedSpecsApprovalHandler.attachmentHash(conversationId);
    Optional<CompilationArtifacts.Reference> fromInputs =
        context.inputRefs().stream()
            .filter(ref -> ref != null && ref.kind() == CompilationArtifacts.Kind.APPROVAL_RECORD)
            .filter(ref -> isApproved(context.runId(), conversationId, ref))
            .findFirst();
    if (fromInputs.isPresent()) {
      return fromInputs;
    }
    // Fallback: any approved uploaded-spec record for this run, even if current attachment keys are
    // no longer available (approval happened on a prior turn).
    Optional<CompilationArtifacts.Revision> anyApproved =
        artifactStore.findLatestApprovalRecord(
            context.runId(), UploadedSpecsApprovalHandler.ARTIFACT_TYPE, currentHash);
    if (anyApproved.isPresent()) {
      return anyApproved.map(CompilationArtifacts.Revision::reference);
    }
    return artifactStore
        .findLatestApprovalRecord(context.runId(), UploadedSpecsApprovalHandler.ARTIFACT_TYPE, null)
        .filter(rev -> isApproved(context.runId(), conversationId, rev.reference()))
        .map(CompilationArtifacts.Revision::reference);
  }

  private List<String> attachmentKeys(
      StageExecutionContext context, CompilationArtifacts.Reference approvalRef) {
    List<String> currentKeys = conversationService.getAllowedAttachmentKeys(context.conversationId());
    if (currentKeys != null && !currentKeys.isEmpty()) {
      return currentKeys;
    }
    Optional<ApprovalRecordV2> record =
        artifactStore
            .get(context.runId(), approvalRef)
            .map(rev -> artifactStore.payload(rev, ApprovalRecordV2.class));
    return record
        .map(ApprovalRecordV2::attachmentKeys)
        .filter(keys -> keys != null && !keys.isEmpty())
        .orElse(List.of());
  }

  private boolean isApproved(
      String runId, String conversationId, CompilationArtifacts.Reference reference) {
    Optional<CompilationArtifacts.Revision> revision = artifactStore.get(runId, reference);
    if (revision.isEmpty()) {
      return false;
    }
    ApprovalRecordV2 record = artifactStore.payload(revision.get(), ApprovalRecordV2.class);
    if (record == null || record.target() == null) {
      return false;
    }
    String currentHash = uploadedSpecsApprovalHandler.attachmentHash(conversationId);
    CompilationArtifacts.Reference target = record.target();
    boolean validTarget =
        target.artifactId().startsWith(UploadedSpecsApprovalHandler.ARTIFACT_TYPE + ":");
    // When current attachment keys are no longer present (e.g., approval happened on a prior turn),
    // accept the approved record by target identity alone.
    return validTarget && (currentHash == null || currentHash.equals(target.contentHash()));
  }

  private CapabilitySignal.Completed succeeded(
      StageExecutionContext context, String message, UploadedSpecImportOutcome outcome) {
    RequirementDraft draft = resolveDraft(context);
    if (draft == null) {
      return completed(StageOutcome.of(StageOutcomeClass.SUCCEEDED, message));
    }
    RequirementDraft updatedDraft = draft;
    FactRewrite rewrite = rewriteFactsAndHints(updatedDraft, context.conversationId());
    RequirementDraft rewrittenDraft = rewrite.draft();
    if (draftStore != null) {
      draftStore.put(context.conversationId(), rewrittenDraft);
    }
    LOG.infof(
        "auto-uploaded-spec-import: rewriting facts conversationId=%s facts=%d hints=%d",
        context.conversationId(),
        rewrittenDraft.facts().size(),
        rewrite.hints().size());
    List<ArtifactCandidate> candidates = new ArrayList<>();
    candidates.add(
        new ArtifactCandidate(
            CompilationArtifacts.Kind.REQUIREMENT_DRAFT, rewrittenDraft, context.inputRefs()));
    candidates.addAll(rewrite.hints());
    return completed(
        new StageOutcome(StageOutcomeClass.SUCCEEDED, candidates, message, null));
  }

  private record FactRewrite(List<ArtifactCandidate> hints, RequirementDraft draft) {}

  /**
   * Rewrites uploaded-spec SERVICE_CALL facts into a catalog-bound form and emits a catalog-binding
   * hint for each exact local-catalog match. The imported specification is already in the runtime
   * catalog, so a read-only match is enough to pin the integration operation id.
   */
  private FactRewrite rewriteFactsAndHints(RequirementDraft draft, String conversationId) {
    if (catalogBindingMatcher == null
        || draft == null
        || draft.facts() == null
        || draft.facts().isEmpty()) {
      return new FactRewrite(List.of(), draft);
    }
    List<ArtifactCandidate> hints = new ArrayList<>();
    List<RequirementFact> rewrittenFacts = new ArrayList<>();
    for (RequirementFact call : draft.facts()) {
      if (call == null
          || call.polarity() != RequirementFactPolarity.POSITIVE
          || call.kind() != RequirementFactKind.SERVICE_CALL
          || call.text() == null
          || call.text().isBlank()) {
        rewrittenFacts.add(call);
        continue;
      }
      String serviceName = blankToNull(call.participant());
      String operationQuery = blankToNull(call.operation());
      if (operationQuery == null) {
        operationQuery = call.text().trim();
      }
      UploadedSpecFact parsed = parseUploadedSpecFact(call.text());
      if (parsed != null) {
        if (serviceName == null) {
          serviceName = parsed.specTitle();
        }
        operationQuery = parsed.operationId() + " " + parsed.channel();
      }
      CatalogBindingMatcher.MatchResult match =
          catalogBindingMatcher.match(
              "service-call", serviceName, operationQuery, conversationId);
      LOG.infof(
          "auto-uploaded-spec-import: probing fact factId=%s service=%s query=%s match=%s",
          call.sourceFactId(),
          serviceName,
          operationQuery,
          match.getClass().getSimpleName());
      if (!(match instanceof CatalogBindingMatcher.MatchResult.Exact exact)) {
        rewrittenFacts.add(call);
        continue;
      }
      CatalogBindingMatcher.CatalogMatch hit = exact.match();
      String boundText =
          String.format(
              "Call catalog-bound %s %s operation, %s %s",
              hit.systemName(), hit.operationName(), hit.method(), hit.path());
      rewrittenFacts.add(
          new RequirementFact(
              call.sourceFactId(),
              call.polarity(),
              call.kind(),
              call.capabilityKey(),
              boundText,
              call.participant(),
              call.operation(),
              call.topic(),
              call.httpMethod(),
              call.path(),
              call.serviceCallId()));
      hints.add(
          new ArtifactCandidate(
              CompilationArtifacts.Kind.CATALOG_BINDING_HINT,
              catalogHint(call, hit, boundText),
              List.of()));
    }
    RequirementDraft rewritten = draft.withFacts(List.copyOf(rewrittenFacts));
    for (ArtifactCandidate candidate : hints) {
      if (!(candidate.payload() instanceof CatalogBindingHint hint)) {
        continue;
      }
      rewritten = rewritten.withBoundInteraction(hint.interactionId(), hint);
    }
    return new FactRewrite(List.copyOf(hints), rewritten);
  }

  private static CatalogBindingHint catalogHint(
      RequirementFact call, CatalogBindingMatcher.CatalogMatch hit, String operationQuery) {
    String serviceCallId =
        call.serviceCallId() == null || call.serviceCallId().isBlank()
            ? call.sourceFactId()
            : call.serviceCallId();
    String release =
        hit.systemName() == null || hit.systemName().isBlank() ? "default" : hit.systemName();
    return new CatalogBindingHint(
        CatalogBindingHint.SCHEMA_VERSION,
        serviceCallId,
        call.sourceFactId(),
        operationQuery,
        hit.systemId(),
        hit.specificationGroupId(),
        hit.specificationId(),
        hit.integrationOperationId(),
        hit.protocol(),
        hit.method(),
        hit.path(),
        release,
        Instant.now(),
        hit.evidenceRef());
  }

  private static UploadedSpecFact parseUploadedSpecFact(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    var matcher = UPLOADED_SPEC_FACT.matcher(text.trim());
    if (!matcher.matches()) {
      return null;
    }
    return new UploadedSpecFact(matcher.group(2).trim(), matcher.group(3), matcher.group(4).trim());
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private record UploadedSpecFact(String specTitle, String operationId, String channel) {}

  private RequirementDraft resolveDraft(StageExecutionContext context) {
    Object approved = context.attributes().get("approvedDraft");
    return approved instanceof RequirementDraft draft ? draft : null;
  }

  private Optional<RequirementDraft> resolveDraftFromInputRefs(StageExecutionContext context) {
    return context.inputRefs().stream()
        .filter(ref -> ref != null && ref.kind() == CompilationArtifacts.Kind.REQUIREMENT_DRAFT)
        .findFirst()
        .flatMap(ref -> artifactStore.get(context.runId(), ref))
        .filter(revision -> "2".equals(revision.schemaVersion()))
        .map(revision -> artifactStore.payload(revision, RequirementDraft.class));
  }

  private static boolean hasCatalogBinding(RequirementDraft draft) {
    return draft != null && draft.selectedImportCallAlreadyBound();
  }

  private static CapabilitySignal.Completed succeededWithDraft(
      StageExecutionContext context, RequirementDraft draft) {
    return completed(
        new StageOutcome(
            StageOutcomeClass.SUCCEEDED,
            List.of(
                new ArtifactCandidate(
                    CompilationArtifacts.Kind.REQUIREMENT_DRAFT, draft, context.inputRefs())),
            "Skipped uploaded-spec import: requirement draft already has catalog binding",
            null));
  }

  private static CapabilitySignal.Completed completed(StageOutcome outcome) {
    return new CapabilitySignal.Completed(outcome);
  }

  private static String filenameFromKey(String key) {
    int slash = key.lastIndexOf('/');
    return slash >= 0 ? key.substring(slash + 1) : key;
  }
}
