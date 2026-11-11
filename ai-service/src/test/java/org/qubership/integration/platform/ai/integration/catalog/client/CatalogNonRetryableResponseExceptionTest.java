package org.qubership.integration.platform.ai.integration.catalog.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CatalogNonRetryableResponseExceptionTest {

  @Test
  void catalogErrorMessageReturnsParsedErrorMessageField() {
    CatalogNonRetryableResponseException exception =
        catalog400("{\"errorMessage\":\"exchange not found\",\"status\":400}");

    assertEquals("exchange not found", exception.catalogErrorMessage());
  }

  @Test
  void catalogErrorMessageReturnsEmptyWhenFieldMissing() {
    CatalogNonRetryableResponseException exception =
        catalog400("{\"status\":400,\"detail\":\"ignored\"}");

    assertEquals("", exception.catalogErrorMessage());
  }

  @Test
  void catalogErrorMessageReturnsEmptyWhenBodyIsNotJson() {
    CatalogNonRetryableResponseException exception =
        catalog400("<html>internal server error</html>");

    assertEquals("", exception.catalogErrorMessage());
    assertFalse(exception.catalogErrorMessage().contains("html"));
  }

  @Test
  void catalogErrorMessageReturnsEmptyWhenBodyBlank() {
    CatalogNonRetryableResponseException exception = catalog400("");

    assertEquals("", exception.catalogErrorMessage());
  }

  private static CatalogNonRetryableResponseException catalog400(String body) {
    Response response =
        Response.status(400)
            .type("application/json")
            .entity(body.getBytes(StandardCharsets.UTF_8))
            .build();
    return new CatalogNonRetryableResponseException(response);
  }
}
