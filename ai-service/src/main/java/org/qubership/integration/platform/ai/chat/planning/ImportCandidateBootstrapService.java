package org.qubership.integration.platform.ai.chat.planning;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.conversation.ConversationMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds an import candidate from diary + recent chat when the planner never called the record tool. */
@ApplicationScoped
public class ImportCandidateBootstrapService {

  private static final Logger LOG = Logger.getLogger(ImportCandidateBootstrapService.class);
  private static final String DEFAULT_DOCUMENT_ID = "api";

  private static final Pattern PACKAGE_WITH_VERSION =
      Pattern.compile(
          "(?i)(?:apihub\\s+)?package\\s+([A-Z][A-Za-z0-9.]+)\\s+with\\s+version\\s+([^\\s,]+)");
  private static final Pattern PACKAGE_ID_INLINE =
      Pattern.compile("\\b(S\\.[A-Za-z0-9.]+)\\b");
  private static final Pattern VERSION_INLINE =
      Pattern.compile("\\b(20[0-9]{2}\\.[0-9]+@[0-9]+|[0-9]+\\.[0-9]+\\.[0-9]+@[0-9]+)\\b");

  private final ConversationPlanningDiaryService planningDiaryService;

  @Inject
  public ImportCandidateBootstrapService(ConversationPlanningDiaryService planningDiaryService) {
    this.planningDiaryService = planningDiaryService;
  }

  /**
   * Ensures an import candidate exists when IMPORT_SPECIFICATION starts but only prose from
   * CREATE_CHAIN_PLAN mentions package/version (no {@code recordApiHubImportCandidate} call).
   */
  public Optional<ApiHubImportCandidate> ensureCandidateForImport(
      String conversationId, List<ConversationMessage> messages) {
    if (conversationId == null || conversationId.isBlank()) {
      return Optional.empty();
    }
    Optional<ApiHubImportCandidate> existing =
        planningDiaryService.resolveImportCandidate(conversationId, null);
    if (existing.isPresent()) {
      return existing;
    }
    Optional<ParsedOffer> fromTranscript = parseFromRecentMessages(messages);
    if (fromTranscript.isEmpty()) {
      return Optional.empty();
    }
    ParsedOffer offer = fromTranscript.get();
    String catalogSystemName =
        planningDiaryService
            .lastCatalogEmptySearchCondition(conversationId)
            .filter(s -> !s.isBlank())
            .orElse(offer.specificationName());
    ApiHubImportCandidate saved =
        planningDiaryService.upsertImportCandidateFromApiHubSearch(
            conversationId,
            catalogSystemName,
            inferCatalogSystemType(offer.packageId()),
            offer.packageId(),
            offer.version(),
            DEFAULT_DOCUMENT_ID,
            offer.specificationName(),
            "bootstrap from conversation transcript");
    if (saved != null) {
      LOG.infof(
          "Bootstrapped ApiHub import candidate conversationId=%s packageId=%s version=%s",
          conversationId,
          offer.packageId(),
          offer.version());
    }
    return Optional.ofNullable(saved);
  }

  private static Optional<ParsedOffer> parseFromRecentMessages(List<ConversationMessage> messages) {
    if (messages == null || messages.isEmpty()) {
      return Optional.empty();
    }
    List<ConversationMessage> recent = new ArrayList<>(messages);
    for (int i = recent.size() - 1; i >= 0; i--) {
      ConversationMessage msg = recent.get(i);
      if (msg == null || msg.getRole() != ConversationMessage.Role.ASSISTANT) {
        continue;
      }
      String text = msg.getContent();
      if (text == null || text.isBlank()) {
        continue;
      }
      Matcher explicit = PACKAGE_WITH_VERSION.matcher(text);
      if (explicit.find()) {
        String packageId = explicit.group(1).trim();
        String version = explicit.group(2).trim();
        return Optional.of(new ParsedOffer(packageId, version, inferSpecificationName(text, packageId)));
      }
    }
    String packageId = null;
    String version = null;
    for (int i = recent.size() - 1; i >= 0; i--) {
      ConversationMessage msg = recent.get(i);
      if (msg == null || msg.getRole() != ConversationMessage.Role.ASSISTANT) {
        continue;
      }
      String text = msg.getContent();
      if (text == null || text.isBlank()) {
        continue;
      }
      if (packageId == null) {
        Matcher pkg = PACKAGE_ID_INLINE.matcher(text);
        while (pkg.find()) {
          String candidate = pkg.group(1);
          if (candidate.contains("SvcCat") || candidate.contains("ActProv")) {
            packageId = candidate;
            break;
          }
        }
      }
      if (version == null) {
        Matcher ver = VERSION_INLINE.matcher(text);
        if (ver.find()) {
          version = ver.group(1);
        }
      }
      if (packageId != null && version != null) {
        return Optional.of(new ParsedOffer(packageId, version, inferSpecificationName(text, packageId)));
      }
    }
    return Optional.empty();
  }

  private static String inferSpecificationName(String text, String packageId) {
    if (text != null && text.toLowerCase(Locale.ROOT).contains("service catalog")) {
      return "Service Catalog";
    }
    if (packageId != null && packageId.contains("SvcCat")) {
      return "Service Catalog";
    }
    return packageId != null ? packageId : "ApiHub Specification";
  }

  private static String inferCatalogSystemType(String packageId) {
    if (packageId != null
        && (packageId.startsWith("S.") || packageId.toUpperCase(Locale.ROOT).contains("TMF"))) {
      return "INTERNAL";
    }
    return "EXTERNAL";
  }

  private record ParsedOffer(String packageId, String version, String specificationName) {}
}
