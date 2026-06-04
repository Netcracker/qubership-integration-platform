package org.qubership.integration.platform.ai.chat.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.jboss.logmanager.MDC;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;
import org.qubership.integration.platform.ai.model.ScenarioType;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Records ApiHub import candidates from successful search results (server-side, no LLM tool call). */
@ApplicationScoped
public class ApiHubImportCandidateRecorder {

  private static final Logger LOG = Logger.getLogger(ApiHubImportCandidateRecorder.class);
  private static final Pattern SEARCH_CONDITION =
      Pattern.compile("(?i)searchCondition=(.+)");

  private final ConversationPlanningDiaryService planningDiaryService;
  private final ObjectMapper objectMapper;

  @Inject
  public ApiHubImportCandidateRecorder(
      ConversationPlanningDiaryService planningDiaryService, ObjectMapper objectMapper) {
    this.planningDiaryService = planningDiaryService;
    this.objectMapper = objectMapper;
  }

  /**
   * When CREATE_CHAIN_PLAN lists ApiHub packages after an empty catalog search, persist a candidate
   * for the package that best matches the last catalog searchCondition.
   */
  public void recordFromPackagesListIfApplicable(String packagesJsonText) {
    String scenarioRaw = MDC.get(ChatMdc.SCENARIO_TYPE);
    if (scenarioRaw == null || !ScenarioType.CREATE_CHAIN_PLAN.name().equals(scenarioRaw.trim())) {
      return;
    }
    String conversationId = MDC.get(ChatMdc.CONVERSATION_ID);
    if (conversationId == null || conversationId.isBlank()) {
      return;
    }
    if (packagesJsonText == null
        || packagesJsonText.isBlank()
        || packagesJsonText.startsWith("Error ")
        || packagesJsonText.startsWith("APIHUB MCP returned error:")) {
      return;
    }
    try {
      JsonNode root = objectMapper.readTree(packagesJsonText);
      JsonNode packages = root.path("packages");
      if (!packages.isArray() || packages.isEmpty()) {
        return;
      }
      String searchHint =
          planningDiaryService
              .lastCatalogEmptySearchCondition(conversationId)
              .orElse("");
      JsonNode matched = selectPackageForImport(packages, searchHint);
      if (matched == null) {
        return;
      }
      String packageId = textField(matched, "packageId");
      String packageName = textField(matched, "name");
      String version = pickLatestVersion(matched.path("versions"));
      if (packageId == null || version == null || packageName == null) {
        return;
      }
      String catalogSystemName = searchHint.isBlank() ? packageName : searchHint;
      ApiHubImportCandidate saved =
          planningDiaryService.upsertImportCandidateFromApiHubSearch(
              conversationId,
              catalogSystemName,
              inferCatalogSystemType(packageId),
              packageId,
              version,
              "api",
              packageName,
              "auto from api-packages-list");
      if (saved != null) {
        LOG.infof(
            "Auto-recorded ApiHub import candidate from packages list conversationId=%s"
                + " packageId=%s version=%s specName=%s",
            conversationId,
            packageId,
            version,
            packageName);
      }
    } catch (Exception e) {
      LOG.debugf(
          "Skip auto import candidate from packages list conversationId=%s: %s",
          conversationId,
          e.getMessage());
    }
  }

  private static JsonNode selectPackageForImport(JsonNode packages, String searchHint) {
    JsonNode best = null;
    int bestScore = 0;
    for (JsonNode pkg : packages) {
      if (pkg == null || pkg.isNull()) {
        continue;
      }
      int score = scorePackage(pkg, searchHint);
      if (score > bestScore) {
        bestScore = score;
        best = pkg;
      }
    }
    return bestScore > 0 ? best : null;
  }

  private static int scorePackage(JsonNode pkg, String searchHint) {
    String name = pkg.path("name").asText("").toLowerCase(Locale.ROOT);
    String packageId = pkg.path("packageId").asText("").toLowerCase(Locale.ROOT);
    String hint = searchHint == null ? "" : searchHint.toLowerCase(Locale.ROOT);
    int score = 0;
    if (hint.contains("service catalog") || hint.contains("svccat")) {
      if (name.contains("service catalog")) {
        score += 10;
      }
      if (packageId.contains("svccat")) {
        score += 10;
      }
    }
    if (!hint.isBlank()) {
      for (String token : hint.split("[^a-z0-9]+")) {
        if (token.length() < 4) {
          continue;
        }
        if (name.contains(token) || packageId.contains(token)) {
          score += 2;
        }
      }
    }
    return score;
  }

  private static String pickLatestVersion(JsonNode versions) {
    if (!versions.isArray() || versions.isEmpty()) {
      return null;
    }
    JsonNode first = versions.get(0);
    return first != null ? textField(first, "version") : null;
  }

  /**
   * When CREATE_CHAIN_PLAN finds ApiHub operations but catalog was empty, persist an import
   * candidate so IMPORT_SPECIFICATION can run without relying on the planner calling
   * {@code recordApiHubImportCandidate}.
   */
  public void recordFromSearchResultIfApplicable(String searchResultText) {
    String scenarioRaw = MDC.get(ChatMdc.SCENARIO_TYPE);
    if (scenarioRaw == null || !ScenarioType.CREATE_CHAIN_PLAN.name().equals(scenarioRaw.trim())) {
      return;
    }
    String conversationId = MDC.get(ChatMdc.CONVERSATION_ID);
    if (conversationId == null || conversationId.isBlank()) {
      return;
    }
    if (searchResultText == null
        || searchResultText.isBlank()
        || searchResultText.startsWith("Error ")
        || searchResultText.startsWith("APIHUB MCP returned error:")) {
      return;
    }
    try {
      JsonNode root = objectMapper.readTree(searchResultText);
      JsonNode items = root.path("items");
      if (!items.isArray() || items.isEmpty()) {
        return;
      }
      JsonNode first = items.get(0);
      if (first == null || first.isNull()) {
        return;
      }
      String packageId = textField(first, "packageId");
      String version = textField(first, "version");
      String documentId = textField(first, "documentId");
      String packageName = textField(first, "packageName");
      if (packageId == null || version == null || documentId == null || packageName == null) {
        return;
      }
      String catalogSystemName =
          planningDiaryService
              .lastCatalogEmptySearchCondition(conversationId)
              .filter(s -> !s.isBlank())
              .orElse(packageName);
      String catalogSystemType = inferCatalogSystemType(packageId);
      ApiHubImportCandidate saved =
          planningDiaryService.upsertImportCandidateFromApiHubSearch(
              conversationId,
              catalogSystemName,
              catalogSystemType,
              packageId,
              version,
              documentId,
              packageName,
              "auto from search_api_operations");
      if (saved != null) {
        LOG.infof(
            "Auto-recorded ApiHub import candidate conversationId=%s packageId=%s version=%s"
                + " specName=%s catalogSystem=%s",
            conversationId,
            packageId,
            version,
            packageName,
            catalogSystemName);
      }
    } catch (Exception e) {
      LOG.debugf(
          "Skip auto import candidate from ApiHub search conversationId=%s: %s",
          conversationId,
          e.getMessage());
    }
  }

  private static String inferCatalogSystemType(String packageId) {
    if (packageId != null
        && (packageId.startsWith("S.") || packageId.toUpperCase(Locale.ROOT).contains("TMF"))) {
      return "INTERNAL";
    }
    return "EXTERNAL";
  }

  private static String textField(JsonNode node, String field) {
    if (node == null || node.isMissingNode()) {
      return null;
    }
    String value = node.path(field).asText(null);
    return CatalogStrings.blankToNull(value);
  }

  static String parseSearchConditionFromDiaryDetail(String detail) {
    if (detail == null || detail.isBlank()) {
      return null;
    }
    Matcher m = SEARCH_CONDITION.matcher(detail.trim());
    if (m.find()) {
      return m.group(1).trim();
    }
    return null;
  }
}
