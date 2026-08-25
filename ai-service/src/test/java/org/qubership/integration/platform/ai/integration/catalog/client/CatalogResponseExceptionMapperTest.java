package org.qubership.integration.platform.ai.integration.catalog.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.util.Arrays;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.junit.jupiter.api.Test;

class CatalogResponseExceptionMapperTest {

  private final CatalogResponseExceptionMapper mapper = new CatalogResponseExceptionMapper();

  @Test
  void mapsOnlyNonRateLimitedClientResponsesToTheAbortType() {
    assertInstanceOf(CatalogNonRetryableResponseException.class, map(400));
    assertInstanceOf(CatalogNonRetryableResponseException.class, map(404));
    assertInstanceOf(WebApplicationException.class, map(429));
    assertTrue(!(map(429) instanceof CatalogNonRetryableResponseException));
    assertTrue(!(map(503) instanceof CatalogNonRetryableResponseException));
  }

  @Test
  void bothClientsRegisterTheSharedMapperAndRetryTransientResponses() {
    assertClientFaultTolerance(CatalogRestClient.class);
    assertClientFaultTolerance(CatalogImportRestClient.class);
  }

  private RuntimeException map(int status) {
    Response response = Response.status(status).build();
    try {
      return mapper.toThrowable(response);
    } finally {
      response.close();
    }
  }

  private static void assertClientFaultTolerance(Class<?> clientType) {
    assertTrue(
        Arrays.stream(clientType.getAnnotationsByType(RegisterProvider.class))
            .anyMatch(provider -> provider.value().equals(CatalogResponseExceptionMapper.class)));
    Retry retry = clientType.getAnnotation(Retry.class);
    assertTrue(Arrays.asList(retry.retryOn()).contains(WebApplicationException.class));
    assertEquals(CatalogNonRetryableResponseException.class, retry.abortOn()[0]);
  }
}
