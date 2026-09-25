package org.qubership.integration.platform.ai.plan.workdocument.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.plan.workdocument.ChainWorkDocument;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentService;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentState;
import org.qubership.integration.platform.ai.plan.workdocument.WorkRepairBudget;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunTransition;

/**
 * Records original source evidence and a separate interpretation. Attachment role is chosen
 * before any API specification import.
 */
public final class WorkSourceIntake {

  private static final String PRODUCER_ID = "source-intake";

  private final WorkDocumentService documents;
  private final ProductPipelineRunStore runs;
  private final CompilationArtifacts artifacts;
  private final Function<String, String> reader;
  private final ObjectMapper json = new ObjectMapper();

  public WorkSourceIntake(
      WorkDocumentService documents,
      ProductPipelineRunStore runs,
      CompilationArtifacts artifacts,
      Function<String, String> reader) {
    this.documents = documents;
    this.runs = runs;
    this.artifacts = artifacts;
    this.reader = reader;
  }

  public SourceInventory accept(String runId, String documentId, SourceBatch batch, String commandId) {
    String payloadHash = payloadHash(batch);
    ProductPipelineRunDocument current = runs.load(runId).orElseThrow();
    Optional<RunTransition> replay = current.appliedCommand(commandId, payloadHash);
    if (replay.isPresent()) {
      return read(runId);
    }
    ObjectNode document = loadOrCreate(runId, documentId);
    Map<String, SourceEvidence> evidence = evidenceBySource(runId);
    ArrayNode sources = array(document, "sources");
    ArrayNode requirements = array(document, "requirements");
    ArrayNode questions = array(document, "questions");
    Set<String> existingIds = new LinkedHashSet<>();
    existingIds.addAll(ids(sources));
    existingIds.addAll(ids(requirements));
    List<PendingSource> added = new ArrayList<>();
    for (SourceNote note : batch.notes()) {
      added.add(addNote(runId, sources, note, evidence));
    }
    for (SourceFile file : batch.files()) {
      added.add(addFile(runId, sources, file, evidence));
    }
    for (SourceCorrection correction : batch.corrections()) {
      added.add(addCorrection(runId, sources, requirements, correction, evidence));
    }
    if (conflictingMappings(sources, evidence)) {
      dropConflictingInterpretations(requirements, sources);
      addConflictQuestion(sources, questions, evidence);
    } else {
      for (PendingSource pending : added) {
        if (pending.interprets()) {
          addRequirement(requirements, pending.sourceId(), pending.text());
        }
      }
    }
    List<String> accepted = new ArrayList<>();
    for (String id : ids(sources)) {
      if (!existingIds.contains(id)) {
        accepted.add(id);
      }
    }
    for (String id : ids(requirements)) {
      if (!existingIds.contains(id)) {
        accepted.add(id);
      }
    }
    publish(runId, document, commandId, accepted, payloadHash);
    return inventory(document, evidence);
  }

  public SourceInventory read(String runId) {
    ObjectNode document = loadOrCreate(runId, "");
    return inventory(document, evidenceBySource(runId));
  }

  private PendingSource addNote(
      String runId, ArrayNode sources, SourceNote note, Map<String, SourceEvidence> evidence) {
    String text = note.text() == null ? "" : note.text();
    String sourceId = nextId(sources, "src-");
    String hash = sha256(text);
    SourceRole role = note.role() == null ? SourceRole.MESSAGE : note.role();
    sources.add(sourceNode(sourceId, role, "message:" + hash, hash, "message", "", List.of(), text));
    remember(runId, evidence, sourceId, text, "");
    return new PendingSource(sourceId, text, role != SourceRole.UNSUPPORTED);
  }

  private PendingSource addFile(
      String runId, ArrayNode sources, SourceFile file, Map<String, SourceEvidence> evidence) {
    SourceRole role = classify(file);
    String sourceId = nextId(sources, "src-");
    String supplied = file.suppliedIdentifier() == null ? "" : file.suppliedIdentifier();
    if (role == SourceRole.UNSUPPORTED) {
      String limitation = readerLimitation(file.originalName());
      String hash = sha256(file.storageReference() + "\n" + limitation);
      sources.add(
          sourceNode(sourceId, role, file.storageReference(), hash, file.originalName(), supplied, List.of(), ""));
      remember(runId, evidence, sourceId, "", limitation);
      return new PendingSource(sourceId, "", false);
    }
    String text = reader.apply(file.storageReference());
    if (text == null) {
      text = "";
    }
    String hash = sha256(text);
    sources.add(
        sourceNode(sourceId, role, file.storageReference(), hash, file.originalName(), supplied, List.of(), text));
    remember(runId, evidence, sourceId, text, "");
    boolean interprets = role == SourceRole.MAPPING || role == SourceRole.MARKDOWN_IDS || role == SourceRole.MESSAGE;
    return new PendingSource(sourceId, text, interprets);
  }

  private PendingSource addCorrection(
      String runId,
      ArrayNode sources,
      ArrayNode requirements,
      SourceCorrection correction,
      Map<String, SourceEvidence> evidence) {
    String originalId = sourceIdForSupplied(sources, correction.correctsSuppliedIdentifier());
    String sourceId = nextId(sources, "src-");
    String text = correction.text() == null ? "" : correction.text();
    String hash = sha256(text);
    sources.add(
        sourceNode(
            sourceId,
            SourceRole.CORRECTION,
            "correction:" + hash,
            hash,
            "correction",
            "",
            List.of(originalId),
            text));
    remember(runId, evidence, sourceId, text, "");
    String superseded = requirementIdForSource(requirements, originalId);
    if (!superseded.isBlank()) {
      addRequirement(requirements, sourceId, text, superseded);
    }
    return new PendingSource(sourceId, text, false);
  }

  private static SourceRole classify(SourceFile file) {
    if (file.explicitRole() != null) {
      return file.explicitRole();
    }
    if (AttachmentRoles.isMappingName(file.originalName())) {
      return SourceRole.MAPPING;
    }
    if (AttachmentRoles.importsAsSpecification(file.originalName())) {
      return SourceRole.API_SPECIFICATION;
    }
    return SourceRole.UNSUPPORTED;
  }

  private static boolean conflictingMappings(ArrayNode sources, Map<String, SourceEvidence> evidence) {
    List<String> texts = new ArrayList<>();
    for (JsonNode source : sources) {
      if (!SourceRole.MAPPING.name().equals(source.path("role").asText())) {
        continue;
      }
      if (corrected(sources, source.path("id").asText())) {
        continue;
      }
      SourceEvidence body = evidence.get(source.path("id").asText());
      texts.add(body == null ? "" : body.originalText());
    }
    return texts.stream().distinct().count() > 1;
  }

  private static void dropConflictingInterpretations(ArrayNode requirements, ArrayNode sources) {
    Set<String> conflicting = new LinkedHashSet<>();
    for (JsonNode source : sources) {
      if (SourceRole.MAPPING.name().equals(source.path("role").asText())
          && !corrected(sources, source.path("id").asText())) {
        conflicting.add(source.path("id").asText());
      }
    }
    for (int index = requirements.size() - 1; index >= 0; index--) {
      boolean citesConflict = false;
      for (JsonNode sourceId : requirements.get(index).path("sourceIds")) {
        if (conflicting.contains(sourceId.asText())) {
          citesConflict = true;
        }
      }
      if (citesConflict) {
        requirements.remove(index);
      }
    }
  }

  private static boolean corrected(ArrayNode sources, String sourceId) {
    for (JsonNode source : sources) {
      if (!SourceRole.CORRECTION.name().equals(source.path("role").asText())) {
        continue;
      }
      for (JsonNode correctedId : source.path("correctionOf")) {
        if (sourceId.equals(correctedId.asText())) {
          return true;
        }
      }
    }
    return false;
  }

  private void addConflictQuestion(
      ArrayNode sources, ArrayNode questions, Map<String, SourceEvidence> evidence) {
    List<String> ids = new ArrayList<>();
    List<String> labels = new ArrayList<>();
    for (JsonNode source : sources) {
      if (!SourceRole.MAPPING.name().equals(source.path("role").asText()) || corrected(sources, source.path("id").asText())) {
        continue;
      }
      ids.add(source.path("id").asText());
      String supplied = source.path("suppliedIdentifier").asText("");
      labels.add(supplied.isBlank() ? source.path("originalName").asText() : supplied);
    }
    if (labels.size() < 2 || questionExists(questions, ids)) {
      return;
    }
    ObjectNode question = json.createObjectNode();
    question.put("id", nextId(questions, "q-"));
    question.put("choice", "conflicting-sources");
    question.put(
        "question",
        "Sources " + String.join(" and ", labels) + " disagree. Upload order does not choose which source applies.");
    question.putArray("evidenceIds").addAll(strings(ids));
    questions.add(question);
    for (JsonNode source : sources) {
      if (SourceRole.UNSUPPORTED.name().equals(source.path("role").asText())) {
        addUnsupportedQuestion(questions, source, evidence);
      }
    }
  }

  private void addUnsupportedQuestion(
      ArrayNode questions, JsonNode source, Map<String, SourceEvidence> evidence) {
    String sourceId = source.path("id").asText();
    if (questionExists(questions, List.of(sourceId))) {
      return;
    }
    SourceEvidence body = evidence.get(sourceId);
    String limitation = body == null ? readerLimitation(source.path("originalName").asText()) : body.readerLimitation();
    ObjectNode question = json.createObjectNode();
    question.put("id", nextId(questions, "q-"));
    question.put("choice", "unsupported-reader");
    question.put("question", limitation);
    question.putArray("evidenceIds").add(sourceId);
    questions.add(question);
  }

  private static boolean questionExists(ArrayNode questions, List<String> evidenceIds) {
    for (JsonNode question : questions) {
      List<String> ids = new ArrayList<>();
      question.path("evidenceIds").forEach(id -> ids.add(id.asText()));
      if (ids.equals(evidenceIds)) {
        return true;
      }
    }
    return false;
  }

  private void addRequirement(ArrayNode requirements, String sourceId, String text) {
    addRequirement(requirements, sourceId, text, "");
  }

  private void addRequirement(ArrayNode requirements, String sourceId, String text, String supersededId) {
    ObjectNode requirement = json.createObjectNode();
    requirement.put("id", nextId(requirements, "req-"));
    requirement.put("text", text);
    requirement.putArray("sourceIds").add(sourceId);
    requirement.put("supersededRequirementId", supersededId);
    requirements.add(requirement);
  }

  private ObjectNode sourceNode(
      String id,
      SourceRole role,
      String contentReference,
      String contentHash,
      String originalName,
      String suppliedIdentifier,
      List<String> correctionOf,
      String content) {
    ObjectNode source = json.createObjectNode();
    source.put("id", id);
    source.put("role", role.name());
    source.put("contentReference", contentReference);
    source.put("contentHash", contentHash);
    source.put("originalName", originalName);
    source.put("suppliedIdentifier", suppliedIdentifier);
    source.putArray("correctionOf").addAll(strings(correctionOf));
    source.put("content", content == null ? "" : content);
    source.putArray("passages");
    return source;
  }

  private void remember(
      String runId,
      Map<String, SourceEvidence> evidence,
      String sourceId,
      String originalText,
      String readerLimitation) {
    SourceEvidence body = new SourceEvidence(sourceId, originalText, readerLimitation);
    evidence.put(sourceId, body);
    artifacts.append(
        new AppendCommand(
            runId, Kind.USER_INPUT, "1", PRODUCER_ID, "1", body, List.of(), null));
  }

  private ObjectNode loadOrCreate(String runId, String documentId) {
    try {
      WorkDocumentState state = documents.read(runId);
      JsonNode tree = json.valueToTree(state.document());
      return (ObjectNode) tree;
    } catch (IllegalArgumentException missing) {
      ObjectNode document = json.createObjectNode();
      document.put("schemaVersion", ChainWorkDocument.SCHEMA_VERSION);
      document.put("documentId", documentId);
      document.putArray("sources");
      document.putArray("requirements");
      document.set("flow", json.createObjectNode());
      ObjectNode progress = json.createObjectNode();
      progress.putArray("questions");
      document.set("progress", progress);
      return document;
    }
  }

  private static ArrayNode array(ObjectNode document, String name) {
    if ("questions".equals(name)) {
      ObjectNode progress = (ObjectNode) document.with("progress");
      if (!progress.has("questions") || !progress.get("questions").isArray()) {
        return progress.putArray("questions");
      }
      return (ArrayNode) progress.get("questions");
    }
    if (!document.has(name) || !document.get(name).isArray()) {
      return document.putArray(name);
    }
    return (ArrayNode) document.get(name);
  }

  private void publish(
      String runId,
      ObjectNode document,
      String commandId,
      List<String> acceptedRecordIds,
      String payloadHash) {
    ensureUnsupportedQuestions(document);
    ChainWorkDocument payload = json.convertValue(document, ChainWorkDocument.class);
    WorkDocumentState indexed = documents.indexSourcePassages(new WorkDocumentState(commandId, payload));
    copyIndexedPassages(document, indexed);
    ProductPipelineRunDocument current = runs.load(runId).orElseThrow();
    WorkRepairBudget budget = current.run().workDocumentRef() == null ? new WorkRepairBudget(3) : null;
    documents.intake(
        runId,
        indexed,
        commandId,
        budget,
        payloadHash,
        acceptedRecordIds);
  }

  /** Copies indexed passages onto the working tree so the returned inventory matches the commit. */
  private void copyIndexedPassages(ObjectNode document, WorkDocumentState indexed) {
    JsonNode indexedSources = json.valueToTree(indexed.document()).path("sources");
    for (JsonNode source : array(document, "sources")) {
      if (!(source instanceof ObjectNode node)) {
        continue;
      }
      String sourceId = node.path("id").asText();
      for (JsonNode indexedSource : indexedSources) {
        if (!sourceId.equals(indexedSource.path("id").asText())) {
          continue;
        }
        JsonNode passages = indexedSource.get("passages");
        if (passages != null) {
          node.set("passages", passages);
        }
        break;
      }
    }
  }

  private void ensureUnsupportedQuestions(ObjectNode document) {
    ArrayNode sources = array(document, "sources");
    ArrayNode questions = array(document, "questions");
    for (JsonNode source : sources) {
      if (!SourceRole.UNSUPPORTED.name().equals(source.path("role").asText())) {
        continue;
      }
      String sourceId = source.path("id").asText();
      addUnsupportedQuestion(
          questions,
          source,
          Map.of(
              sourceId,
              new SourceEvidence(sourceId, "", readerLimitation(source.path("originalName").asText()))));
    }
  }

  private SourceInventory inventory(ObjectNode document, Map<String, SourceEvidence> evidence) {
    List<StoredSource> sources = new ArrayList<>();
    List<String> specificationKeys = new ArrayList<>();
    for (JsonNode source : array(document, "sources")) {
      String sourceId = source.path("id").asText();
      SourceEvidence body = evidence.get(sourceId);
      SourceRole role = SourceRole.valueOf(source.path("role").asText());
      if (role == SourceRole.API_SPECIFICATION) {
        specificationKeys.add(source.path("contentReference").asText());
      }
      List<String> correctionOf = new ArrayList<>();
      source.path("correctionOf").forEach(id -> correctionOf.add(id.asText()));
      List<StoredPassage> passages = new ArrayList<>();
      for (JsonNode passage : source.path("passages")) {
        passages.add(
            new StoredPassage(
                passage.path("id").asText(),
                passage.path("sourceId").asText(),
                passage.path("text").asText(),
                passage.path("parentHeading").asText(),
                passage.path("contentHash").asText()));
      }
      sources.add(
          new StoredSource(
              sourceId,
              role,
              source.path("contentReference").asText(),
              source.path("contentHash").asText(),
              source.path("originalName").asText(),
              source.path("suppliedIdentifier").asText(),
              List.copyOf(correctionOf),
              body == null ? "" : body.originalText(),
              body == null ? "" : body.readerLimitation(),
              source.path("content").asText(""),
              List.copyOf(passages)));
    }
    List<StoredRequirement> requirements = new ArrayList<>();
    for (JsonNode requirement : array(document, "requirements")) {
      List<String> sourceIds = new ArrayList<>();
      requirement.path("sourceIds").forEach(id -> sourceIds.add(id.asText()));
      requirements.add(
          new StoredRequirement(
              requirement.path("id").asText(),
              requirement.path("text").asText(),
              List.copyOf(sourceIds),
              requirement.path("supersededRequirementId").asText()));
    }
    List<StoredQuestion> questions = new ArrayList<>();
    for (JsonNode question : array(document, "questions")) {
      List<String> evidenceIds = new ArrayList<>();
      question.path("evidenceIds").forEach(id -> evidenceIds.add(id.asText()));
      questions.add(
          new StoredQuestion(
              question.path("id").asText(),
              question.path("choice").asText(),
              question.path("question").asText(),
              List.copyOf(evidenceIds)));
    }
    return new SourceInventory(List.copyOf(sources), List.copyOf(requirements), List.copyOf(questions), List.copyOf(specificationKeys));
  }

  private Map<String, SourceEvidence> evidenceBySource(String runId) {
    Map<String, SourceEvidence> evidence = new LinkedHashMap<>();
    for (Revision revision : artifacts.history(runId, Kind.USER_INPUT)) {
      if (!PRODUCER_ID.equals(revision.producerId())) {
        continue;
      }
      SourceEvidence body = artifacts.payload(revision, SourceEvidence.class);
      if (body != null && body.sourceId() != null) {
        evidence.put(body.sourceId(), body);
      }
    }
    return evidence;
  }

  private ArrayNode strings(List<String> values) {
    ArrayNode array = json.createArrayNode();
    for (String value : values) {
      array.add(value);
    }
    return array;
  }

  private static String sourceIdForSupplied(ArrayNode sources, String suppliedIdentifier) {
    for (JsonNode source : sources) {
      if (suppliedIdentifier.equals(source.path("suppliedIdentifier").asText())) {
        return source.path("id").asText();
      }
    }
    throw new IllegalArgumentException("No source has supplied identifier " + suppliedIdentifier + ".");
  }

  private static String requirementIdForSource(ArrayNode requirements, String sourceId) {
    String found = "";
    for (JsonNode requirement : requirements) {
      for (JsonNode id : requirement.path("sourceIds")) {
        if (sourceId.equals(id.asText()) && requirement.path("supersededRequirementId").asText("").isBlank()) {
          found = requirement.path("id").asText();
        }
      }
    }
    return found;
  }

  private static String nextId(ArrayNode items, String prefix) {
    int max = 0;
    for (JsonNode item : items) {
      String id = item.path("id").asText("");
      if (id.startsWith(prefix)) {
        try {
          max = Math.max(max, Integer.parseInt(id.substring(prefix.length())));
        } catch (NumberFormatException ignored) {
          continue;
        }
      }
    }
    return prefix + (max + 1);
  }

  private static String readerLimitation(String originalName) {
    return "No reader is available for "
        + originalName
        + ". UTF-8 .md and .txt mapping files can be read. This file was not imported as an API specification.";
  }

  private String payloadHash(SourceBatch batch) {
    try {
      return sha256(json.writeValueAsString(batch));
    } catch (Exception failure) {
      throw new IllegalStateException("Cannot hash the source command.", failure);
    }
  }

  private static List<String> ids(ArrayNode items) {
    List<String> ids = new ArrayList<>();
    for (JsonNode item : items) {
      ids.add(item.path("id").asText());
    }
    return ids;
  }

  private static String sha256(String content) {
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (Exception failure) {
      throw new IllegalStateException("SHA-256 is unavailable.", failure);
    }
  }

  private record PendingSource(String sourceId, String text, boolean interprets) {}

  private record SourceEvidence(String sourceId, String originalText, String readerLimitation) {}
}

enum SourceRole {
  MESSAGE,
  MARKDOWN_IDS,
  MAPPING,
  API_SPECIFICATION,
  CORRECTION,
  UNSUPPORTED
}

record SourceFile(
    String storageReference, String originalName, String suppliedIdentifier, SourceRole explicitRole) {}

record SourceNote(String text, SourceRole role) {}

record SourceCorrection(String text, String correctsSuppliedIdentifier) {}

record SourceBatch(List<SourceNote> notes, List<SourceFile> files, List<SourceCorrection> corrections) {}

record StoredPassage(String id, String sourceId, String text, String parentHeading, String contentHash) {}

record StoredSource(
    String id,
    SourceRole role,
    String contentReference,
    String contentHash,
    String originalName,
    String suppliedIdentifier,
    List<String> correctionOf,
    String originalText,
    String readerLimitation,
    String content,
    List<StoredPassage> passages) {}

record StoredRequirement(String id, String text, List<String> sourceIds, String supersededRequirementId) {}

record StoredQuestion(String id, String choice, String question, List<String> evidenceIds) {}

record SourceInventory(
    List<StoredSource> sources,
    List<StoredRequirement> requirements,
    List<StoredQuestion> questions,
    List<String> specificationImportKeys) {}
