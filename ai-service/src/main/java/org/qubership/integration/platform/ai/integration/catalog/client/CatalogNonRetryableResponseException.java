package org.qubership.integration.platform.ai.integration.catalog.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogRestSupport;

/** A catalog client-response failure that must not be retried. */
public final class CatalogNonRetryableResponseException extends WebApplicationException {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final String bodySnippet;
  private final boolean turnBearer;

  public CatalogNonRetryableResponseException(Response response) {
    this(response, CatalogRestSupport.readResponseBodySnippet(response), false);
  }

  public CatalogNonRetryableResponseException(Response response, String bodySnippet) {
    this(response, bodySnippet, false);
  }

  public CatalogNonRetryableResponseException(
      Response response, String bodySnippet, boolean turnBearer) {
    super(response);
    this.bodySnippet = bodySnippet;
    this.turnBearer = turnBearer;
  }

  public String bodySnippet() {
    return bodySnippet;
  }

  /** True when the request was made with the turn bearer rather than the service identity. */
  public boolean turnBearer() {
    return turnBearer;
  }

  /** The HTTP status of the catalog response, or {@code -1} when the response is absent. */
  public int status() {
    Response response = getResponse();
    return response == null ? -1 : response.getStatus();
  }

  /** Parsed {@code errorMessage} when the body is JSON; otherwise an empty string. */
  public String catalogErrorMessage() {
    if (bodySnippet == null || bodySnippet.isBlank()) {
      return "";
    }
    try {
      JsonNode root = OBJECT_MAPPER.readTree(bodySnippet);
      String errorMessage = root.path("errorMessage").asText("");
      return errorMessage.isBlank() ? "" : errorMessage;
    } catch (Exception parseFailed) {
      return "";
    }
  }
}
