package org.qubership.integration.platform.ai.chat.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.logging.AiTraceLog;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-memory per-conversation notes for planning: IDS references from user turns, attachment keys,
 * and HITL checkpoint open/resolve events. Fed into CREATE_CHAIN_PLAN, IMPLEMENT_CHAIN, ASK_DESIGN,
 * and router transcripts so follow-up turns keep context even when no {@code
 * ChainImplementationPlan} exists yet.
 */
@ApplicationScoped
public class ConversationPlanningDiaryService {

  private static final int MAX_IDS_OR_KEYS = 12;
  private static final int MAX_DIARY_EVENTS = 24;
  private static final int MAX_DECISION_RECORDS = 8;
  private static final int MAX_CATALOG_SYSTEMS = 8;
  private static final int MAX_SPECS_PER_SYSTEM = 4;
  private static final int MAX_LINE_CHARS = 600;

  private static final Pattern IDS_TOKEN =
      Pattern.compile("\\b(QIP\\.INT\\.IDS\\.[A-Za-z0-9_.-]+)\\b");

  private final ConcurrentHashMap<String, Diary> byConversation = new ConcurrentHashMap<>();

  /** Records IDS document ids and attachment object keys from a resolved user turn. */
  public void recordDesignHintsFromUserTurn(
      String conversationId, String effectiveUserText, List<String> attachmentObjectKeys) {
    if (conversationId == null || conversationId.isBlank()) {
      return;
    }
    Diary d = byConversation.computeIfAbsent(conversationId, k -> new Diary());
    synchronized (d) {
      if (effectiveUserText != null && !effectiveUserText.isBlank()) {
        Matcher m = IDS_TOKEN.matcher(effectiveUserText);
        while (m.find() && d.idsDocumentIds.size() < MAX_IDS_OR_KEYS) {
          d.idsDocumentIds.add(m.group(1));
        }
      }
      if (attachmentObjectKeys != null) {
        for (String key : attachmentObjectKeys) {
          if (key == null || key.isBlank()) {
            continue;
          }
          if (d.attachmentObjectKeys.size() >= MAX_IDS_OR_KEYS) {
            break;
          }
          d.attachmentObjectKeys.add(key.trim());
        }
      }
    }
  }

  public void recordHitlCheckpointOpened(
      String conversationId, String checkpointId, String question, List<String> options) {
    if (conversationId == null || conversationId.isBlank() || checkpointId == null) {
      return;
    }
    Diary d = byConversation.computeIfAbsent(conversationId, k -> new Diary());
    synchronized (d) {
      String q = truncate(question);
      String opt = options == null ? "" : truncate(String.join(" | ", options));
      d.events.addLast(new DiaryEvent(Instant.now(), "hitl_open", checkpointId, q, opt));
      trimEvents(d);
    }
  }

  public void recordHitlCheckpointResolved(
      String conversationId, String checkpointId, String answer) {
    if (conversationId == null || conversationId.isBlank() || checkpointId == null) {
      return;
    }
    Diary d = byConversation.computeIfAbsent(conversationId, k -> new Diary());
    synchronized (d) {
      d.events.addLast(
          new DiaryEvent(Instant.now(), "hitl_resolved", checkpointId, truncate(answer), ""));
      trimEvents(d);
    }
  }

  /**
   * Records systems returned by a successful {@code searchCatalogSystems} call (CREATE_CHAIN_PLAN
   * diary).
   */
  public void recordCatalogSystemsFound(
      String conversationId, String searchCondition, List<CatalogRestClient.SystemDto> systems) {
    if (conversationId == null
        || conversationId.isBlank()
        || systems == null
        || systems.isEmpty()) {
      return;
    }
    Diary d = byConversation.computeIfAbsent(conversationId, k -> new Diary());
    synchronized (d) {
      for (CatalogRestClient.SystemDto system : systems) {
        if (system == null || system.id() == null || system.id().isBlank()) {
          continue;
        }
        if (d.catalogSystemsById.size() >= MAX_CATALOG_SYSTEMS
            && !d.catalogSystemsById.containsKey(system.id())) {
          break;
        }
        CatalogSystemResolution existing = d.catalogSystemsById.get(system.id());
        if (existing == null) {
          existing = new CatalogSystemResolution();
          existing.systemId = system.id();
          d.catalogSystemsById.put(system.id(), existing);
        }
        existing.name = nullToEmpty(system.name());
        existing.type = nullToEmpty(system.type());
        existing.protocol = nullToEmpty(system.protocol());
        if (searchCondition != null && !searchCondition.isBlank()) {
          existing.searchCondition = searchCondition.trim();
        }
      }
    }
  }

  /** Records API specification (model) ids for a catalog system. */
  public void recordCatalogSpecifications(
      String conversationId, String systemId, List<CatalogRestClient.SpecificationDto> specs) {
    if (conversationId == null
        || conversationId.isBlank()
        || systemId == null
        || systemId.isBlank()
        || specs == null
        || specs.isEmpty()) {
      return;
    }
    Diary d = byConversation.computeIfAbsent(conversationId, k -> new Diary());
    synchronized (d) {
      CatalogSystemResolution resolution =
          d.catalogSystemsById.computeIfAbsent(
              systemId,
              id -> {
                CatalogSystemResolution r = new CatalogSystemResolution();
                r.systemId = id;
                return r;
              });
      trimCatalogSystems(d);
      for (CatalogRestClient.SpecificationDto spec : specs) {
        if (spec == null || spec.id() == null || spec.id().isBlank()) {
          continue;
        }
        if (resolution.specifications.size() >= MAX_SPECS_PER_SYSTEM) {
          break;
        }
        boolean seen =
            resolution.specifications.stream().anyMatch(s -> spec.id().equals(s.modelId()));
        if (!seen) {
          resolution.specifications.add(
              new CatalogSpecResolution(spec.id(), nullToEmpty(spec.name())));
        }
      }
    }
  }

  /** Records that operations were loaded for a specification (modelId). */
  public void recordCatalogOperationsLoaded(
      String conversationId, String modelId, int operationCount) {
    if (conversationId == null
        || conversationId.isBlank()
        || modelId == null
        || modelId.isBlank()
        || operationCount <= 0) {
      return;
    }
    Diary d = byConversation.get(conversationId);
    if (d == null) {
      return;
    }
    synchronized (d) {
      for (CatalogSystemResolution resolution : d.catalogSystemsById.values()) {
        if (modelId.equals(resolution.operationsLoadedForModelId)) {
          resolution.operationCount = operationCount;
          return;
        }
        for (CatalogSpecResolution spec : resolution.specifications) {
          if (modelId.equals(spec.modelId())) {
            resolution.operationsLoadedForModelId = modelId;
            resolution.operationCount = operationCount;
            return;
          }
        }
        if (modelId.startsWith(resolution.systemId)) {
          resolution.operationsLoadedForModelId = modelId;
          resolution.operationCount = operationCount;
          return;
        }
      }
    }
  }

  /** Parses successful catalog read-tool JSON and updates catalog resolution entries. */
  public void recordCatalogToolSuccessFromJson(
      String conversationId,
      String toolName,
      String argsSummary,
      String out,
      ObjectMapper objectMapper) {
    CatalogDiaryOutcomeParser.recordSuccess(
        this, objectMapper, conversationId, toolName, argsSummary, out);
  }

  /**
   * Records a read-only catalog tool outcome without a HITL checkpoint (empty list or
   * transport/catalog error).
   *
   * @param outcomeKind {@code empty} when the HTTP response was an empty JSON array; {@code error}
   *     for failures
   */
  public void recordCatalogLookupNote(
      String conversationId,
      String toolName,
      String argsSummary,
      String outcomeKind,
      String resultPreview) {
    if (conversationId == null
        || conversationId.isBlank()
        || toolName == null
        || toolName.isBlank()) {
      return;
    }
    String kind = "error".equalsIgnoreCase(outcomeKind) ? "catalog_error" : "catalog_empty";
    Diary d = byConversation.computeIfAbsent(conversationId, k -> new Diary());
    synchronized (d) {
      d.events.addLast(
          new DiaryEvent(
              Instant.now(), kind, toolName, truncate(argsSummary), truncate(resultPreview)));
      trimEvents(d);
    }
  }

  /** First IDS id seen in user turns for this conversation (stable insertion order). */
  public Optional<String> firstRecordedIdsDocumentId(String conversationId) {
    if (conversationId == null || conversationId.isBlank()) {
      return Optional.empty();
    }
    Diary d = byConversation.get(conversationId);
    if (d == null) {
      return Optional.empty();
    }
    synchronized (d) {
      return d.idsDocumentIds.stream().findFirst();
    }
  }

  /** First attachment object key from user turns for this conversation. */
  public Optional<String> firstRecordedAttachmentObjectKey(String conversationId) {
    if (conversationId == null || conversationId.isBlank()) {
      return Optional.empty();
    }
    Diary d = byConversation.get(conversationId);
    if (d == null) {
      return Optional.empty();
    }
    synchronized (d) {
      return d.attachmentObjectKeys.stream().findFirst();
    }
  }

  /** Records a durable decision summary (e.g. after token-window eviction) for later turns. */
  public void recordDecision(String conversationId, String summary) {
    if (conversationId == null
        || conversationId.isBlank()
        || summary == null
        || summary.isBlank()) {
      return;
    }
    Diary d = byConversation.computeIfAbsent(conversationId, k -> new Diary());
    synchronized (d) {
      d.decisions.addLast(new DecisionRecord(Instant.now(), truncate(summary)));
      while (d.decisions.size() > MAX_DECISION_RECORDS) {
        d.decisions.removeFirst();
      }
    }
  }

  /**
   * Compact markdown of the last planning events for authoritative prompt state (not the full
   * diary).
   */
  public String formatRecentEventsForAuthoritative(String conversationId, int maxEvents) {
    if (conversationId == null || conversationId.isBlank() || maxEvents <= 0) {
      return "";
    }
    Diary d = byConversation.get(conversationId);
    if (d == null) {
      return "";
    }
    synchronized (d) {
      if (d.events.isEmpty()) {
        return "";
      }
      int tail = Math.min(maxEvents, d.events.size());
      StringBuilder sb = new StringBuilder();
      int start = d.events.size() - tail;
      int i = 0;
      for (DiaryEvent e : d.events) {
        if (i++ < start) {
          continue;
        }
        sb.append("- **").append(e.kind()).append("** `").append(e.ref()).append("`\n");
      }
      return sb.toString().trim();
    }
  }

  /** Markdown appendix for prompts (empty string when nothing recorded). */
  public String formatAppendix(String conversationId) {
    if (conversationId == null || conversationId.isBlank()) {
      return "";
    }
    Diary d = byConversation.get(conversationId);
    if (d == null) {
      return "";
    }
    synchronized (d) {
      if (d.idsDocumentIds.isEmpty()
          && d.attachmentObjectKeys.isEmpty()
          && d.catalogSystemsById.isEmpty()
          && d.events.isEmpty()
          && d.decisions.isEmpty()) {
        return "";
      }
      StringBuilder sb = new StringBuilder();
      sb.append("## Planning diary (conversation memory)\n");
      sb.append(
          "Use this block to reconcile follow-up user messages with earlier open questions. When"
              + " the user supplies missing catalog IDs or names, update your reasoning and tools"
              + " accordingly.\n\n");
      if (!d.idsDocumentIds.isEmpty()) {
        sb.append("### IDS document ids referenced in user content\n");
        for (String id : d.idsDocumentIds) {
          sb.append("- ").append(id).append("\n");
        }
        sb.append("\n");
      }
      if (!d.attachmentObjectKeys.isEmpty()) {
        sb.append("### Attachment object keys (S3 / storage)\n");
        sb.append(
            "Full IDS text is re-inlined in **User Request** when keys are present; use that body, "
                + "not keys alone.\n");
        for (String k : d.attachmentObjectKeys) {
          sb.append("- `").append(k).append("`\n");
        }
        sb.append("\n");
      }
      if (!d.catalogSystemsById.isEmpty()) {
        appendCatalogResolutionsSection(sb, d.catalogSystemsById);
      }
      if (!d.decisions.isEmpty()) {
        sb.append("### Decision log (summarized)\n");
        for (DecisionRecord r : d.decisions) {
          sb.append("- @ ").append(r.at()).append(": ").append(r.summary()).append("\n");
        }
        sb.append("\n");
      }
      if (!d.events.isEmpty()) {
        sb.append("### Planning events — HITL and catalog tools (most recent last)\n");
        for (DiaryEvent e : d.events) {
          sb.append("- **")
              .append(e.kind())
              .append("** `")
              .append(e.ref())
              .append("` @ ")
              .append(e.at())
              .append("\n");
          if (e.detail() != null && !e.detail().isBlank()) {
            sb.append("  - detail: ").append(e.detail()).append("\n");
          }
          if (e.extra() != null && !e.extra().isBlank()) {
            sb.append("  - extra: ").append(e.extra()).append("\n");
          }
        }
      }
      return sb.toString();
    }
  }

  private static void appendCatalogResolutionsSection(
      StringBuilder sb, Map<String, CatalogSystemResolution> catalogSystemsById) {
    sb.append("### Catalog services resolved (reuse; avoid redundant searchCatalogSystems)\n");
    sb.append(
        "Reuse these catalog ids for service-call binding in follow-up turns. "
            + "Do not call searchCatalogSystems again for the same backend unless the user names a "
            + "different service or no row matches the design. When IDS prose names a system "
            + "differently (e.g. Petshop) but a row below matches the user's catalog choice, "
            + "prefer the diary row.\n\n");
    for (CatalogSystemResolution r : catalogSystemsById.values()) {
      String searchHint =
          r.searchCondition != null && !r.searchCondition.isBlank()
              ? "searchCondition=" + r.searchCondition + " → "
              : "";
      sb.append("- ")
          .append(searchHint)
          .append("**")
          .append(r.name.isBlank() ? "(unnamed)" : r.name)
          .append("**");
      if (!r.type.isBlank() || !r.protocol.isBlank()) {
        sb.append(" (");
        if (!r.type.isBlank()) {
          sb.append(r.type);
        }
        if (!r.protocol.isBlank()) {
          if (!r.type.isBlank()) {
            sb.append(", ");
          }
          sb.append(r.protocol);
        }
        sb.append(")");
      }
      sb.append(": systemId=`").append(r.systemId).append("`\n");
      for (CatalogSpecResolution spec : r.specifications) {
        sb.append("  - specification: specificationId=`").append(spec.modelId()).append("`");
        if (!spec.name().isBlank()) {
          sb.append(", name=`").append(spec.name()).append("`");
        }
        sb.append("\n");
      }
      if (r.operationsLoadedForModelId != null
          && !r.operationsLoadedForModelId.isBlank()
          && r.operationCount > 0) {
        sb.append("  - operations: loaded for specificationId `")
            .append(r.operationsLoadedForModelId)
            .append("` (")
            .append(r.operationCount)
            .append(" operations)\n");
      }
    }
    sb.append("\n");
  }

  private static void trimCatalogSystems(Diary d) {
    while (d.catalogSystemsById.size() > MAX_CATALOG_SYSTEMS) {
      String oldest = d.catalogSystemsById.keySet().iterator().next();
      d.catalogSystemsById.remove(oldest);
    }
  }

  private static void trimEvents(Diary d) {
    while (d.events.size() > MAX_DIARY_EVENTS) {
      d.events.removeFirst();
    }
  }

  private static String nullToEmpty(String s) {
    return s != null ? s : "";
  }

  private static String truncate(String s) {
    if (s == null) {
      return "";
    }
    String t = s.replace('\n', ' ').trim();
    return AiTraceLog.preview(t, MAX_LINE_CHARS);
  }

  private static final class Diary {
    final Set<String> idsDocumentIds = new LinkedHashSet<>();
    final Set<String> attachmentObjectKeys = new LinkedHashSet<>();
    final LinkedHashMap<String, CatalogSystemResolution> catalogSystemsById = new LinkedHashMap<>();
    final ArrayDeque<DiaryEvent> events = new ArrayDeque<>();
    final ArrayDeque<DecisionRecord> decisions = new ArrayDeque<>();
  }

  private static final class CatalogSystemResolution {
    String systemId = "";
    String name = "";
    String type = "";
    String protocol = "";
    String searchCondition = "";
    final List<CatalogSpecResolution> specifications = new ArrayList<>();
    String operationsLoadedForModelId;
    int operationCount;
  }

  private record CatalogSpecResolution(String modelId, String name) {}

  private record DecisionRecord(Instant at, String summary) {}

  /**
   * @param ref checkpoint id for HITL kinds; tool name for {@code catalog_empty} / {@code
   *     catalog_error}
   */
  private record DiaryEvent(Instant at, String kind, String ref, String detail, String extra) {}
}
