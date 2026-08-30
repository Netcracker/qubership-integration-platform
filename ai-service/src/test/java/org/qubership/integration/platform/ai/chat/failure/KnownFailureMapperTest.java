package org.qubership.integration.platform.ai.chat.failure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionException;
import org.eclipse.microprofile.faulttolerance.exceptions.TimeoutException;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogNonRetryableResponseException;

class KnownFailureMapperTest {

  private final KnownFailureMapper mapper = new KnownFailureMapper();

  @Test
  void timeoutMapsToSpecCopyWithoutExceptionText() {
    KnownFailure mapped =
        mapper
            .tryMap(
                new TimeoutException("CatalogRestClient$$CDIWrapper#createSnapshot timed out"),
                CatalogOperation.SNAPSHOT)
            .orElseThrow();
    assertEquals(KnownFailureMapper.CATALOG_TIMEOUT_MESSAGE, mapped.safeText());
    assertFalse(mapped.safeText().contains("CDIWrapper"));
    assertFalse(mapped.safeText().contains("timed out"));
  }

  @Test
  void catalog400UsesErrorMessageAndElement() {
    CatalogNonRetryableResponseException refused =
        catalog400(
            """
            {
              "errorMessage": "Fields are not properly defined or require mandatory connection",
              "details": {
                "chainId": "chain-1",
                "elementId": "el-http-1",
                "elementName": "HTTP Trigger"
              }
            }
            """);

    KnownFailure mapped =
        mapper.tryMap(refused, CatalogOperation.SNAPSHOT).orElseThrow();

    assertEquals(
        "Couldn't take a catalog snapshot: Fields are not properly defined or require"
            + " mandatory connection. Element HTTP Trigger (el-http-1).",
        mapped.safeText());
  }

  @Test
  void npeIsNotAKnownFailure() {
    assertTrue(mapper.tryMap(new NullPointerException("x"), CatalogOperation.DEPLOY).isEmpty());
  }

  @Test
  void timeoutInsideCompletionExceptionMapsToSpecCopy() {
    KnownFailure mapped =
        mapper
            .tryMap(
                new CompletionException(
                    new TimeoutException(
                        "CatalogRestClient$$CDIWrapper#createSnapshot timed out")),
                CatalogOperation.DEPLOY)
            .orElseThrow();
    assertEquals(KnownFailureMapper.CATALOG_TIMEOUT_MESSAGE, mapped.safeText());
  }

  @Test
  void timeoutInsideBareRuntimeExceptionMapsToSpecCopy() {
    KnownFailure mapped =
        mapper
            .tryMap(
                new RuntimeException(
                    new TimeoutException(
                        "CatalogRestClient$$CDIWrapper#createSnapshot timed out")),
                CatalogOperation.SNAPSHOT)
            .orElseThrow();
    assertEquals(KnownFailureMapper.CATALOG_TIMEOUT_MESSAGE, mapped.safeText());
  }

  @Test
  void catalog400MissingBodyIsShortRefusalWithoutRawDump() {
    CatalogNonRetryableResponseException refused = catalog400("");

    KnownFailure mapped =
        mapper.tryMap(refused, CatalogOperation.SNAPSHOT).orElseThrow();

    assertEquals("Couldn't take a catalog snapshot.", mapped.safeText());
  }

  @Test
  void catalog400UnparseableBodyIsShortRefusalWithoutRawDump() {
    CatalogNonRetryableResponseException refused = catalog400("<html>internal</html>");

    KnownFailure mapped =
        mapper.tryMap(refused, CatalogOperation.SNAPSHOT).orElseThrow();

    assertEquals("Couldn't take a catalog snapshot.", mapped.safeText());
    assertFalse(mapped.safeText().contains("<html>"));
    assertFalse(mapped.safeText().contains("internal"));
  }

  private static CatalogNonRetryableResponseException catalog400(String json) {
    Response response =
        Response.status(400)
            .type("application/json")
            .entity(json.getBytes(StandardCharsets.UTF_8))
            .build();
    return new CatalogNonRetryableResponseException(response);
  }
}
