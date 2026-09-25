package org.qubership.integration.platform.ai.plan.workdocument;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.plan.workdocument.binding.OfflineCatalog;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskModel;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskRequest;
import org.qubership.integration.platform.ai.plan.workdocument.recovery.WorkRecovery;
import org.qubership.integration.platform.ai.productpipeline.profile.RetryPolicy;
import org.qubership.integration.platform.ai.productpipeline.stage.ProductPipelineStageExecutor;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunTransition;
import org.qubership.integration.platform.ai.productpipeline.store.RunSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.StageSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;

/** In-memory run, scripted model, and offline catalog around one filling module. */
final class FillingWorld {

  final String runId;
  final InMemoryArtifactBlobStore blobs;
  final CompilationArtifacts artifacts;
  final ProductPipelineRunStore runs;
  final WorkDocumentService documents;
  final OfflineCatalog catalog;
  final ScriptedWorkModel model;
  final Clock clock;
  WorkDocumentFilling filling;

  private FillingWorld(String runId) {
    this.runId = runId;
    ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    this.blobs = new InMemoryArtifactBlobStore();
    this.clock = Clock.fixed(Instant.parse("2026-09-25T12:00:00Z"), ZoneOffset.UTC);
    this.artifacts = new CompilationArtifacts(blobs, json, clock);
    this.runs = new ProductPipelineRunStore(blobs, json, clock);
    this.documents = new WorkDocumentService(runs, artifacts, json);
    this.catalog = new OfflineCatalog();
    this.model = new ScriptedWorkModel(documents, runId);
    this.filling = openFilling();
  }

  static FillingWorld start(String runId) throws Exception {
    FillingWorld world = new FillingWorld(runId);
    world.runs.create(
        new RunSnapshot(
            runId,
            "conversation-" + runId,
            1L,
            RunStatus.RUNNING,
            "LOGICAL_FLOW",
            List.of(new StageSnapshot("LOGICAL_FLOW", StageStatus.RUNNING, List.of(), null)),
            null));
    String text = Files.readString(fixture("fixtures/om-salesforce.md"));
    WorkSource source =
        new WorkSource(
            "src-om",
            "PRIMARY",
            "",
            sha256(text),
            "om-salesforce.md",
            "",
            List.of(),
            text,
            List.of());
    WorkDocumentState indexed =
        world.documents.indexSourcePassages(WorkDocumentState.create("doc-" + runId, List.of(source)));
    world.documents.intake(runId, indexed, "cmd-intake", new WorkRepairBudget(3));
    return world;
  }

  WorkDocumentFilling reopen() {
    filling = openFilling();
    return filling;
  }

  ChainWorkDocument document() {
    return documents.read(runId).document();
  }

  FillingResult advance(List<String> trace) {
    commands++;
    FillingResult result = filling.advance(runId, "advance-" + commands);
    if (trace != null) {
      trace.add(commands + " " + result.action() + " " + result.taskId() + " " + result.reasons());
    }
    return result;
  }

  FillingResult drive(List<String> trace, int limit) {
    FillingResult last = null;
    for (int step = 0; step < limit; step++) {
      last = advance(trace);
      if (last.action() != FillingResult.Action.ADVANCED) {
        return last;
      }
    }
    return last;
  }

  void replaceProgress(List<WorkTaskRecord> tasks, List<WorkFinding> findings, String commandId) {
    ChainWorkDocument current = document();
    WorkProgress progress = current.progress();
    ChainWorkDocument next =
        new ChainWorkDocument(
            current.schemaVersion(),
            current.documentId(),
            current.sources(),
            current.requirements(),
            current.flow(),
            new WorkProgress(
                tasks,
                findings,
                progress.questions(),
                progress.approvalReference(),
                progress.derivedResultReferences(),
                progress.recheckStages()));
    documents.commitRecoveredDocument(
        runId, next, commandId, commandId, "task-progress", "LOGICAL_FLOW", null);
  }

  int repairCharges() {
    int count = 0;
    for (RunTransition transition : runs.load(runId).orElseThrow().transitions()) {
      String reason = transition.reason();
      if (reason != null && reason.startsWith(ProductPipelineStageExecutor.PRODUCER_REPAIR_REASON_PREFIX)) {
        count++;
      }
    }
    return count;
  }

  private int commands;

  private WorkDocumentFilling openFilling() {
    return new WorkDocumentFilling(
        documents,
        runs,
        artifacts,
        model,
        catalog,
        clock,
        WorkRecovery.create(documents, runs),
        new RetryPolicy(3, 250L));
  }

  private static Path fixture(String suffix) {
    Path module = Path.of("").toAbsolutePath();
    Path sibling = module.resolve("../.scratch/progressive-chain-work-document").resolve(suffix);
    if (Files.exists(sibling)) {
      return sibling;
    }
    return module.resolve(".scratch/progressive-chain-work-document").resolve(suffix);
  }

  static String answerText() throws Exception {
    return Files.readString(
        fixture("after-10-document-filling/fixtures/process-id-answer.txt")).trim();
  }

  private static String sha256(String value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }

  /**
   * Transport fake. Responses follow the committed document and the task kind. Scripted defect
   * bodies are routing evidence when the comment on the test says so.
   */
  static final class ScriptedWorkModel implements WorkTaskModel {
    private final WorkDocumentService documents;
    private final String runId;
    private final List<String> calls = new ArrayList<>();
    private final List<Injection> injections = new ArrayList<>();
    private final AtomicInteger logical = new AtomicInteger();
    private final AtomicInteger binding = new AtomicInteger();
    private final AtomicInteger outline = new AtomicInteger();
    private final AtomicInteger context = new AtomicInteger();
    private final AtomicInteger mapping = new AtomicInteger();
    Runnable duringComplete;
    boolean alwaysMalformedLogical;
    boolean badContextField;
    boolean badMappingField;
    boolean siblingSource;
    boolean urgentPriority;
    boolean wrongServiceCandidate;
    boolean citeRequirement;
    boolean contradictAnswer;
    /** Consumed once. Scripted semantic routing evidence, not production detection. */
    String routingDefectCategory;
    /** Outline accepts without the retained placeholder. The requirement text stays. */
    boolean omitRetainedDeclaration;
    /** Success mapping reports a missing retained declaration when the outline omitted it. */
    boolean reportMissingRetained;
    /** Next selection for this step label returns this known operation id, once. */
    String overrideStepLabel;
    String overrideCandidateId;
    /** Logical repair appends this phrase so affected mappings see a text change. */
    boolean appendCorrectedOperation;
    /** Consumed in order by context calls. Empty uses the real process id path. */
    final List<String> contextFieldPaths = new ArrayList<>();

    ScriptedWorkModel(WorkDocumentService documents, String runId) {
      this.documents = documents;
      this.runId = runId;
    }

    void inject(WorkTaskKind kind, int ordinal, String body) {
      injections.add(new Injection(kind, ordinal, body));
    }

    List<String> calls() {
      return List.copyOf(calls);
    }

    int count(WorkTaskKind kind) {
      int count = 0;
      for (String call : calls) {
        if (call.startsWith(kind.name() + " ")) {
          count++;
        }
      }
      return count;
    }

    @Override
    public String complete(WorkTaskRequest request) {
      calls.add(request.kind().name() + " " + request.taskId());
      int ordinal =
          switch (request.kind()) {
            case LOGICAL_DESIGN -> logical.incrementAndGet();
            case SELECT_OPERATION -> binding.incrementAndGet();
            case DEFINE_TRANSFERS -> outline.incrementAndGet();
            case DESCRIBE_CONTEXT -> context.incrementAndGet();
            case MAP_TRANSFER, REPAIR_RULE -> mapping.incrementAndGet();
            default -> 0;
          };
      if (duringComplete != null) {
        duringComplete.run();
      }
      if (alwaysMalformedLogical && request.kind() == WorkTaskKind.LOGICAL_DESIGN) {
        return "{";
      }
      for (int index = 0; index < injections.size(); index++) {
        Injection injection = injections.get(index);
        if (injection.kind() == request.kind() && injection.ordinal() == ordinal) {
          injections.remove(index);
          if ("CONNECT".equals(injection.body())) {
            throw new IllegalStateException(new java.net.ConnectException("connection refused"));
          }
          return injection.body();
        }
      }
      ChainWorkDocument document = documents.read(runId).document();
      String recordId = recordId(request);
      return switch (request.kind()) {
        case LOGICAL_DESIGN -> logical(document);
        case SELECT_OPERATION -> selection(document, recordId);
        case DEFINE_TRANSFERS -> outline(document, recordId);
        case DESCRIBE_CONTEXT -> context(document, recordId);
        case MAP_TRANSFER, REPAIR_RULE -> mapping(document, recordId);
        default -> throw new IllegalStateException("Unexpected task " + request.kind());
      };
    }

    private static String recordId(WorkTaskRequest request) {
      String id = request.taskId();
      for (String prefix :
          List.of(
              "logical-design-",
              "select-operation-",
              "define-transfers-",
              "describe-context-",
              "map-transfer-",
              "repair-rule-")) {
        if (id.startsWith(prefix)) {
          return id.substring(prefix.length());
        }
      }
      return id;
    }

    private String logical(ChainWorkDocument document) {
      String source = document.sources().get(0).id();
      if (!document.flow().steps().isEmpty() && !document.requirements().isEmpty()) {
        WorkRequirement requirement = document.requirements().get(0);
        String text = requirement.text();
        if (appendCorrectedOperation) {
          appendCorrectedOperation = false;
          text = text + " corrected operation";
        }
        return """
            {"outcome":"PREPARED",
            "requirements":[{"existingId":"%s","alias":"","text":"%s","sourceRefs":["%s"],"supersededRef":""}],
            "steps":[],"connections":[],"sequenceGroups":[],"conditionGroups":[],"splitGroups":[],"loopGroups":[],"retryGroups":[],"errorScopeGroups":[],"deletes":[],
            "question":"","unresolvedChoice":"","clarificationEvidenceIds":[],"defectRecordRef":"","contradiction":"","defectEvidenceIds":[],"issueCategory":""}
            """
            .formatted(requirement.id(), text, source);
      }
      return """
          {"outcome":"PREPARED",
          "requirements":[{"existingId":"","alias":"req","text":"onTaskStart createTask onTaskResult. commandType is the constant completeTask","sourceRefs":["%s"],"supersededRef":""}],
          "steps":[
            {"existingId":"","alias":"start","kind":"TRIGGER","label":"onTaskStart","intent":"Receive the task start","sourceRefs":["%s"],"requirementRefs":["req"]},
            {"existingId":"","alias":"create","kind":"SERVICE_CALL","label":"createTask","intent":"Create the Salesforce task","sourceRefs":["%s"],"requirementRefs":["req"]},
            {"existingId":"","alias":"result","kind":"REPLY","label":"onTaskResult","intent":"Return the task result","sourceRefs":["%s"],"requirementRefs":["req"]}
          ],
          "connections":[
            {"existingId":"","alias":"go","sourceStepRef":"start","outcome":"success","targetStepRef":"create","routingIntent":"Then create the task","evidenceRefs":["%s"]},
            {"existingId":"","alias":"ok","sourceStepRef":"create","outcome":"success","targetStepRef":"result","routingIntent":"Return the synchronous success","evidenceRefs":["%s"]},
            {"existingId":"","alias":"bad","sourceStepRef":"create","outcome":"failure","targetStepRef":"result","routingIntent":"Return the synchronous failure","evidenceRefs":["%s"]}
          ],
          "sequenceGroups":[],"conditionGroups":[],"splitGroups":[],"loopGroups":[],"retryGroups":[],"errorScopeGroups":[],"deletes":[],
          "question":"","unresolvedChoice":"","clarificationEvidenceIds":[],"defectRecordRef":"","contradiction":"","defectEvidenceIds":[],"issueCategory":""}
          """
          .formatted(source, source, source, source, source, source, source);
    }

    static String synchronousReceive(String source) {
      return """
          {"outcome":"PREPARED",
          "requirements":[{"existingId":"","alias":"req","text":"onTaskStart createTask onTaskResult","sourceRefs":["%s"],"supersededRef":""}],
          "steps":[
            {"existingId":"","alias":"start","kind":"TRIGGER","label":"onTaskStart","intent":"Receive the task start","sourceRefs":["%s"],"requirementRefs":["req"]},
            {"existingId":"","alias":"create","kind":"SERVICE_CALL","label":"createTask","intent":"Create the Salesforce task","sourceRefs":["%s"],"requirementRefs":["req"]},
            {"existingId":"","alias":"received","kind":"TRIGGER","label":"Salesforce result","intent":"Receive the synchronous result","sourceRefs":[],"requirementRefs":[]},
            {"existingId":"","alias":"result","kind":"REPLY","label":"onTaskResult","intent":"Return the task result","sourceRefs":["%s"],"requirementRefs":["req"]}
          ],
          "connections":[
            {"existingId":"","alias":"go","sourceStepRef":"start","outcome":"success","targetStepRef":"create","routingIntent":"Then create","evidenceRefs":["%s"]},
            {"existingId":"","alias":"ok","sourceStepRef":"create","outcome":"success","targetStepRef":"received","routingIntent":"Receive the result","evidenceRefs":["%s"]},
            {"existingId":"","alias":"bad","sourceStepRef":"create","outcome":"failure","targetStepRef":"received","routingIntent":"Receive the failure","evidenceRefs":["%s"]},
            {"existingId":"","alias":"done","sourceStepRef":"received","outcome":"success","targetStepRef":"result","routingIntent":"Then reply","evidenceRefs":["%s"]}
          ],
          "sequenceGroups":[],"conditionGroups":[],"splitGroups":[],"loopGroups":[],"retryGroups":[],"errorScopeGroups":[],"deletes":[],
          "question":"","unresolvedChoice":"","clarificationEvidenceIds":[],"defectRecordRef":"","contradiction":"","defectEvidenceIds":[],"issueCategory":""}
          """
          .formatted(source, source, source, source, source, source, source, source);
    }

    private String selection(ChainWorkDocument document, String stepId) {
      LogicalStep step = step(document, stepId);
      String label = step == null ? stepId : step.label();
      if (wrongServiceCandidate && step != null && step.kind() == StepKind.SERVICE_CALL) {
        wrongServiceCandidate = false;
        return "{\"outcome\":\"PREPARED\",\"candidateId\":\"other-operation\"}";
      }
      if (overrideCandidateId != null
          && step != null
          && overrideStepLabel != null
          && overrideStepLabel.equals(step.label())) {
        String candidate = overrideCandidateId;
        overrideCandidateId = null;
        overrideStepLabel = null;
        return "{\"outcome\":\"PREPARED\",\"candidateId\":\"" + candidate + "\"}";
      }
      if (citeRequirement && step != null && !step.requirementIds().isEmpty()) {
        citeRequirement = false;
        String source = document.sources().get(0).id();
        return """
            {"outcome":"INPUT_DEFECT","candidateId":"","question":"","choiceKind":"UNSPECIFIED","evidenceRefs":["%s"],"defectRecordRef":"%s","contradiction":"The step requirement names the wrong operation.","issueCategory":"WRONG_OPERATION"}
            """
            .formatted(source, step.requirementIds().get(0));
      }
      return "{\"outcome\":\"PREPARED\",\"candidateId\":\"" + label + "\"}";
    }

    private String outline(ChainWorkDocument document, String stepId) {
      LogicalStep step = step(document, stepId);
      String requirement = step.requirementIds().isEmpty() ? "" : step.requirementIds().get(0);
      String passage = document.sources().get(0).passages().get(0).id();
      String source = document.sources().get(0).id();
      if (step.kind() == StepKind.TRIGGER) {
        return """
            {"outcome":"PREPARED","transfers":[],"retainedPlaceholders":[],"coverage":[{"requirementId":"%s","passageId":"%s","disposition":"NO_MAPPING"}]}
            """
            .formatted(requirement, passage);
      }
      LogicalStep trigger = byKind(document, StepKind.TRIGGER);
      LogicalStep call = byKind(document, StepKind.SERVICE_CALL);
      String retained = retainedId(document);
      String placeholder = "";
      if (!omitRetainedDeclaration && retained.isBlank()) {
        placeholder =
            "{\"alias\":\"keep-process\",\"producerStepId\":\""
                + trigger.id()
                + "\",\"intendedUse\":\"process id\",\"evidenceRefs\":[\""
                + passage
                + "\"]}";
      }
      if (step.kind() == StepKind.SERVICE_CALL) {
        String retainedRef =
            omitRetainedDeclaration || retained.isBlank() ? "" : "\"" + retained + "\"";
        return """
            {"outcome":"PREPARED","transfers":[{"alias":"to-request","sourceStepId":"%s","sourcePort":"payload","targetPort":"request","outcome":"UNSPECIFIED","requirementIds":["%s"],"requiredRetainedIds":[%s],"decision":""}],"retainedPlaceholders":[%s],"coverage":[{"requirementId":"%s","passageId":"%s","disposition":"ASSIGNED"}]}
            """
            .formatted(trigger.id(), requirement, retainedRef, placeholder, requirement, passage);
      }
      String retainedRef =
          omitRetainedDeclaration ? "" : retained.isBlank() ? "keep-process" : retained;
      return """
          {"outcome":"PREPARED","transfers":[
            {"alias":"to-success","sourceStepId":"%s","sourcePort":"success","targetPort":"request","outcome":"UNSPECIFIED","requirementIds":["%s"],"requiredRetainedIds":["%s"],"decision":""},
            {"alias":"to-failure","sourceStepId":"%s","sourcePort":"failure","targetPort":"request","outcome":"UNSPECIFIED","requirementIds":[],"requiredRetainedIds":[],"decision":""}
          ],"retainedPlaceholders":[%s],"coverage":[{"requirementId":"%s","passageId":"%s","disposition":"ASSIGNED"}]}
          """
          .formatted(call.id(), requirement, retainedRef, call.id(), placeholder, requirement, passage);
    }

    private String context(ChainWorkDocument document, String producerId) {
      String source = document.sources().get(0).id();
      if (badContextField) {
        badContextField = false;
        return contextBody(document, producerId, source, "$.notAField");
      }
      if (!contextFieldPaths.isEmpty()) {
        return contextBody(document, producerId, source, contextFieldPaths.remove(0));
      }
      return contextBody(document, producerId, source, "$.processInstanceId");
    }

    private static String contextBody(
        ChainWorkDocument document, String producerId, String source, String fieldPath) {
      StringBuilder values = new StringBuilder();
      LogicalStep step = step(document, producerId);
      for (RetainedValue value : step.data().retainedValues()) {
        if (value.resolution() == RetainedResolution.RESOLVED) {
          continue;
        }
        if (!values.isEmpty()) {
          values.append(',');
        }
        values
            .append("{\"retainedId\":\"")
            .append(value.id())
            .append("\",\"fieldPath\":\"")
            .append(fieldPath)
            .append("\",\"evidenceRefs\":[\"")
            .append(source)
            .append("\"]}");
      }
      return "{\"outcome\":\"PREPARED\",\"values\":[" + values + "]}";
    }

    private String mapping(ChainWorkDocument document, String recordId) {
      DataTransfer transfer = transfer(document, recordId);
      String source = document.sources().get(0).id();
      String sourcePort = transfer.sourcePorts().isEmpty() ? "" : transfer.sourcePorts().get(0).portName();
      if (reportMissingRetained
          && "success".equals(sourcePort)
          && transfer.requiredRetainedIds().isEmpty()
          && !hasRetainedValue(document)) {
        reportMissingRetained = false;
        omitRetainedDeclaration = false;
        return """
            {"outcome":"INPUT_DEFECT","rules":[],"decision":"","evidenceRefs":[],"question":{"text":"","choiceKind":"UNSPECIFIED","sourceStepId":"","sourcePort":"","sourceField":"","sourceRetainedId":"","targetStepId":"","targetPort":"","targetField":"","targetRetainedId":"","evidenceRefs":[]},"defect":{"recordRef":"%s","category":"MISSING_RETAINED","contradiction":"The outline has no retained declaration for the process id.","evidenceRefs":["%s"]}}
            """
            .formatted(recordId, source);
      }
      if (routingDefectCategory != null) {
        String category = routingDefectCategory;
        routingDefectCategory = null;
        return """
            {"outcome":"INPUT_DEFECT","rules":[],"decision":"","evidenceRefs":[],"question":{"text":"","choiceKind":"UNSPECIFIED","sourceStepId":"","sourcePort":"","sourceField":"","sourceRetainedId":"","targetStepId":"","targetPort":"","targetField":"","targetRetainedId":"","evidenceRefs":[]},"defect":{"recordRef":"%s","category":"%s","contradiction":"Scripted routing evidence for %s.","evidenceRefs":["%s"]}}
            """
            .formatted(recordId, category, category, source);
      }
      if (siblingSource) {
        siblingSource = false;
        return """
            {"outcome":"PREPARED","rules":[{"alias":"rule-sibling","targetPath":"$.Subject","sources":[{"sourceRef":"other-step/payload","fieldPath":"$.name"}],"constants":[],"behavior":"sibling source","evidenceRefs":["%s"]}],"decision":"","evidenceRefs":[]}
            """
            .formatted(source);
      }
      if (badMappingField && !"success".equals(sourcePort)) {
        badMappingField = false;
        String ref = transfer.sourcePorts().get(0).stepId() + "/" + sourcePort;
        return """
            {"outcome":"PREPARED","rules":[{"alias":"rule-bad","targetPath":"$.Subject","sources":[{"sourceRef":"%s","fieldPath":"$.notAField"}],"constants":[],"behavior":"unknown field","evidenceRefs":["%s"]}],"decision":"","evidenceRefs":[]}
            """
            .formatted(ref, source);
      }
      if (urgentPriority && !"failure".equals(sourcePort) && !"success".equals(sourcePort)) {
        urgentPriority = false;
        String trigger = transfer.sourcePorts().get(0).stepId();
        return """
            {"outcome":"PREPARED","rules":[{"alias":"rule-priority","targetPath":"$.Priority","sources":[{"sourceRef":"%s/payload","fieldPath":"$.priority"}],"constants":[{"name":"priority","value":"Urgent"}],"behavior":"constant Urgent","evidenceRefs":["%s"]}],"decision":"","evidenceRefs":[]}
            """
            .formatted(trigger, source);
      }
      if ("failure".equals(sourcePort)) {
        String call = transfer.sourcePorts().get(0).stepId();
        return """
            {"outcome":"PREPARED","rules":[{"alias":"rule-failure","targetPath":"$.error.code","sources":[{"sourceRef":"%s/failure","fieldPath":"$.status"}],"constants":[{"name":"code","value":"SALESFORCE_TASK_CREATE_ERROR"}],"behavior":"failure code","evidenceRefs":["%s"]}],"decision":"","evidenceRefs":[]}
            """
            .formatted(call, source);
      }
      if ("success".equals(sourcePort)) {
        String retained = transfer.requiredRetainedIds().isEmpty() ? "" : transfer.requiredRetainedIds().get(0);
        LogicalStep trigger = byKind(document, StepKind.TRIGGER);
        LogicalStep reply = byKind(document, StepKind.REPLY);
        if (!answered(document) || contradictAnswer) {
          contradictAnswer = false;
          return """
              {"outcome":"NEEDS_CLARIFICATION","rules":[],"decision":"","evidenceRefs":[],"question":{"text":"Field processId does not match source processInstanceId. Record the relationship or choose another field.","choiceKind":"FIELD_RELATIONSHIP","sourceStepId":"%s","sourcePort":"payload","sourceField":"processInstanceId","sourceRetainedId":"%s","targetStepId":"%s","targetPort":"request","targetField":"processId","targetRetainedId":"","evidenceRefs":["%s"]}}
              """
              .formatted(trigger.id(), retained, reply.id(), source);
        }
        return """
            {"outcome":"PREPARED","rules":[{"alias":"rule-process","targetPath":"$.processId","sources":[{"sourceRef":"retained/%s","fieldPath":"$.processInstanceId"}],"constants":[],"behavior":"processId reads retained processInstanceId","evidenceRefs":["%s"],"relationship":{"sourceField":"processInstanceId","targetField":"processId","evidenceRefs":["%s"]}}],"decision":"","evidenceRefs":[]}
            """
            .formatted(retained, source, source);
      }
      String trigger = transfer.sourcePorts().get(0).stepId();
      return """
          {"outcome":"PREPARED","rules":[
            {"alias":"rule-subject","targetPath":"$.Subject","sources":[{"sourceRef":"%s/payload","fieldPath":"$.name"},{"sourceRef":"%s/payload","fieldPath":"$.subRequestType"},{"sourceRef":"%s/payload","fieldPath":"$.orderId"}],"constants":[],"behavior":"name, or a formatted fallback","evidenceRefs":["%s"]},
            {"alias":"rule-priority","targetPath":"$.Priority","sources":[{"sourceRef":"%s/payload","fieldPath":"$.priority"}],"constants":[],"behavior":"high, urgent, or critical to High; low to Low; otherwise Normal","evidenceRefs":["%s"]},
            {"alias":"rule-status","targetPath":"$.Status","sources":[],"constants":[{"name":"status","value":"Not Started"}],"behavior":"constant Not Started","evidenceRefs":["%s"]},
            {"alias":"rule-activity","targetPath":"$.ActivityDate","sources":[{"sourceRef":"%s/payload","fieldPath":"$.parameters.orderCreationDate"}],"constants":[],"behavior":"order creation date, else today","evidenceRefs":["%s"]},
            {"alias":"rule-description","targetPath":"$.Description","sources":[{"sourceRef":"%s/payload","fieldPath":"$.taskId"}],"constants":[],"behavior":"serialized string","evidenceRefs":["%s"]}
          ],"decision":"","evidenceRefs":[]}
          """
          .formatted(trigger, trigger, trigger, source, trigger, source, source, trigger, source, trigger, source);
    }

    private static boolean answered(ChainWorkDocument document) {
      for (WorkQuestion question : document.progress().questions()) {
        if (question.resolution() == QuestionResolution.ANSWERED && !question.answerSourceIds().isEmpty()) {
          return true;
        }
      }
      for (WorkSource source : document.sources()) {
        if ("answer".equals(source.role())) {
          return true;
        }
      }
      return false;
    }

    private static boolean hasRetainedValue(ChainWorkDocument document) {
      return !retainedId(document).isBlank();
    }

    private static String retainedId(ChainWorkDocument document) {
      for (LogicalStep step : document.flow().steps()) {
        for (RetainedValue value : step.data().retainedValues()) {
          return value.id();
        }
      }
      return "";
    }

    private static DataTransfer transfer(ChainWorkDocument document, String recordId) {
      for (LogicalStep step : document.flow().steps()) {
        for (DataTransfer transfer : step.data().transfers()) {
          if (recordId.equals(transfer.id())) {
            return transfer;
          }
          for (MappingRule rule : transfer.rules()) {
            if (recordId.equals(rule.id())) {
              return transfer;
            }
          }
        }
      }
      throw new IllegalStateException("Transfer " + recordId + " is not on the document.");
    }

    private static LogicalStep step(ChainWorkDocument document, String id) {
      for (LogicalStep step : document.flow().steps()) {
        if (step.id().equals(id)) {
          return step;
        }
      }
      return null;
    }

    private static LogicalStep byKind(ChainWorkDocument document, StepKind kind) {
      for (LogicalStep step : document.flow().steps()) {
        if (step.kind() == kind) {
          return step;
        }
      }
      throw new IllegalStateException("Missing step " + kind);
    }

    private record Injection(WorkTaskKind kind, int ordinal, String body) {}
  }
}
