package org.qubership.integration.platform.ai.chat.planning;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogToolResult;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Maps successful catalog tool JSON outcomes into {@link ConversationPlanningDiaryService}. */
final class CatalogDiaryOutcomeParser {

  private static final TypeReference<List<CatalogRestClient.SystemDto>> SYSTEM_LIST =
      new TypeReference<>() {};

  private static final TypeReference<List<CatalogRestClient.SpecificationDto>> SPEC_LIST =
      new TypeReference<>() {};

  private static final Pattern SYSTEM_ID_ARG = Pattern.compile("(?i)systemId=([^,\\s]+)");
  private static final Pattern SPECIFICATION_ID_ARG =
      Pattern.compile("(?i)(?:specificationId|modelId)=([^,\\s]+)");
  private static final Pattern SEARCH_CONDITION_ARG = Pattern.compile("(?i)searchCondition=(.+)");

  private CatalogDiaryOutcomeParser() {}

  static void recordSuccess(
      ConversationPlanningDiaryService diary,
      ObjectMapper objectMapper,
      String conversationId,
      String toolName,
      String argsSummary,
      String out) {
    if (out == null || CatalogToolResult.isError(objectMapper, out)) {
      return;
    }
    try {
      JsonNode payload = CatalogToolResult.dataOrNull(objectMapper, out);
      if (payload == null) {
        payload = objectMapper.readTree(out);
      }
      if (payload == null || !payload.isArray() || payload.isEmpty()) {
        return;
      }
      switch (toolName) {
        case "searchCatalogSystems" -> {
          List<CatalogRestClient.SystemDto> systems = objectMapper.convertValue(payload, SYSTEM_LIST);
          String searchCondition = extractSearchCondition(argsSummary);
          diary.recordCatalogSystemsFound(conversationId, searchCondition, systems);
        }
        case "getApiSpecifications" -> {
          String systemId = extractSystemId(argsSummary);
          if (systemId == null) {
            return;
          }
          List<CatalogRestClient.SpecificationDto> specs =
              objectMapper.convertValue(payload, SPEC_LIST);
          diary.recordCatalogSpecifications(conversationId, systemId, specs);
        }
        case "listCatalogOperations" -> {
          String specificationId = extractSpecificationId(argsSummary);
          if (specificationId == null) {
            return;
          }
          diary.recordCatalogOperationsLoaded(conversationId, specificationId, payload.size());
        }
        default -> {
          // other read tools — no diary resolution
        }
      }
    } catch (Exception ignored) {
      // non-JSON or schema mismatch — skip
    }
  }

  private static String extractSearchCondition(String argsSummary) {
    if (argsSummary == null) {
      return "";
    }
    Matcher m = SEARCH_CONDITION_ARG.matcher(argsSummary);
    return m.find() ? m.group(1).trim() : "";
  }

  private static String extractSystemId(String argsSummary) {
    if (argsSummary == null) {
      return null;
    }
    Matcher m = SYSTEM_ID_ARG.matcher(argsSummary);
    return m.find() ? m.group(1).trim() : null;
  }

  private static String extractSpecificationId(String argsSummary) {
    if (argsSummary == null) {
      return null;
    }
    Matcher m = SPECIFICATION_ID_ARG.matcher(argsSummary);
    return m.find() ? m.group(1).trim() : null;
  }
}
