package org.qubership.integration.platform.ai.integration.catalog.client;

import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Static guard: {@link CatalogRestClient} paths must use runtime-catalog native {@code /v1/...}
 * prefix.
 */
class CatalogRestClientPathTest {

  @Test
  void restClientPathsUseV1Prefix() throws Exception {
    for (Method method : CatalogRestClient.class.getDeclaredMethods()) {
      if (method.isSynthetic()) {
        continue;
      }
      Path path = method.getAnnotation(Path.class);
      assertNotNull(path, () -> "Missing @Path on " + method.getName());
      String p = path.value();
      assertTrue(p.startsWith("/v1/"), () -> method.getName() + " path must start with /v1/: " + p);
      assertFalse(
          p.startsWith("/api/"), () -> method.getName() + " must not use /api prefix: " + p);

      assertHasHttpMethod(method);
    }
  }

  private static void assertHasHttpMethod(Method method) {
    Set<String> annotations = new HashSet<>();
    if (method.getAnnotation(GET.class) != null) {
      annotations.add(HttpMethod.GET);
    }
    if (method.getAnnotation(POST.class) != null) {
      annotations.add(HttpMethod.POST);
    }
    if (method.getAnnotation(PATCH.class) != null) {
      annotations.add(HttpMethod.PATCH);
    }
    if (method.getAnnotation(DELETE.class) != null) {
      annotations.add(HttpMethod.DELETE);
    }
    assertEquals(
        1,
        annotations.size(),
        () -> "Expected exactly one HTTP method annotation on " + method.getName());
  }
}
