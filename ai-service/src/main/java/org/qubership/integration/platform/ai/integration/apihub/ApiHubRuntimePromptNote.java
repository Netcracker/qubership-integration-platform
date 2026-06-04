package org.qubership.integration.platform.ai.integration.apihub;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.qubership.integration.platform.ai.configuration.AppConfig;

/**
 * When APIHub MCP base URL is unset, prefixes user-facing LLM input so models skip APIHub tools.
 */
@ApplicationScoped
public class ApiHubRuntimePromptNote {

  private static final String PREFIX_DISABLED =
      "Runtime note: APIHub MCP is not configured (empty qip.ai.apihub.base-url). "
          + "Do not call searchApiOperations, getApiOperationSpecification, listApiHubPackages, "
          + "or getApiHubDocument. "
          + "Resolve service-call bindings using QIP catalog tools only "
          + "(searchCatalogSystems, getApiSpecifications, listCatalogOperations) "
          + "and the approved plan text.\n\n---\n\n";

  private static final String PREFIX_ENABLED =
      "Runtime note: APIHub search is lexical. Use searchApiOperations with apiType, group=packageId, "
          + "title-like query (spaces OK), and do NOT pass release on search. When IDS lists "
          + "**APIHub:** packageId/version/operationId, call getApiOperationSpecification directly.\n\n"
          + "---\n\n";

  @Inject AppConfig appConfig;

  /**
   * @param userText text to send to the model (may be null)
   * @return prefixed text when APIHub is disabled, otherwise original
   */
  public String maybePrefix(String userText) {
    String base = appConfig.apihub().baseUrl();
    String text = userText != null ? userText : "";
    if (base == null || base.isBlank()) {
      return PREFIX_DISABLED + text;
    }
    return PREFIX_ENABLED + text;
  }
}
