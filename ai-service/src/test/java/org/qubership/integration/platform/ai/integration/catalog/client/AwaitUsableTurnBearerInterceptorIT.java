package org.qubership.integration.platform.ai.integration.catalog.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.UserTokenStore;

/**
 * Proves the custom interceptor runs on the REST client interface and that the renewal wait happens
 * before the HTTP request, outside the client's read timeout. The probe's read timeout is 100 ms.
 */
@QuarkusTest
@QuarkusTestResource(
    value = AwaitUsableTurnBearerInterceptorIT.Endpoint.class,
    restrictToAnnotatedClass = true)
class AwaitUsableTurnBearerInterceptorIT {

  private static final String CONVERSATION_ID = "await-bearer-probe";

  @Inject @RestClient AwaitBearerProbeClient client;

  @org.junit.jupiter.api.BeforeEach
  void resetProbeRequests() {
    Endpoint.probeRequests.set(0);
  }

  @Test
  void renewalWaitLongerThanTheHttpTimeoutStillCompletes() throws Exception {
    long now = Instant.now().getEpochSecond();
    String expired = jwt(now - 60, "alice");
    String fresh = jwt(now + 600, "alice");
    try (ToolSession.Handle ignored = ToolSession.open(CONVERSATION_ID)) {
      UserTokenStore.put(CONVERSATION_ID, expired);
      Thread renew =
          new Thread(
              () -> {
                try {
                  Thread.sleep(300);
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                  return;
                }
                UserTokenStore.renew(CONVERSATION_ID, fresh);
              });
      renew.start();
      try {
        assertEquals("probe-ok", client.probe());
        assertEquals(1, Endpoint.probeRequests.get(), "the renewal wait must not trigger a retry");
      } finally {
        renew.join();
        UserTokenStore.remove(CONVERSATION_ID, fresh);
      }
    }
  }

  @Test
  void slowResponseStillFailsOnTheHttpTimeout() {
    long now = Instant.now().getEpochSecond();
    String fresh = jwt(now + 600, "alice");
    try (ToolSession.Handle ignored = ToolSession.open(CONVERSATION_ID)) {
      UserTokenStore.put(CONVERSATION_ID, fresh);
      try {
        assertThrows(Exception.class, () -> client.slow());
      } finally {
        UserTokenStore.remove(CONVERSATION_ID, fresh);
      }
    }
  }

  private static String jwt(long exp, String subject) {
    Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
    String payload = "{\"exp\":" + exp + ",\"sub\":\"" + subject + "\"}";
    return b64.encodeToString("{}".getBytes(StandardCharsets.UTF_8))
        + "."
        + b64.encodeToString(payload.getBytes(StandardCharsets.UTF_8))
        + ".sig";
  }

  public static class Endpoint implements QuarkusTestResourceLifecycleManager {
    static final java.util.concurrent.atomic.AtomicInteger probeRequests =
        new java.util.concurrent.atomic.AtomicInteger();
    private HttpServer server;

    @Override
    public Map<String, String> start() {
      try {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      } catch (IOException failure) {
        throw new IllegalStateException("Cannot start the renewal probe fixture.", failure);
      }
      server.createContext(
          "/probe",
          exchange -> {
            probeRequests.incrementAndGet();
            respond(exchange, "probe-ok");
          });
      server.createContext(
          "/slow",
          exchange -> {
            try {
              Thread.sleep(300);
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            }
            respond(exchange, "too-late");
          });
      server.start();
      return Map.of(
          "quarkus.rest-client.await-bearer-probe.url",
          "http://127.0.0.1:" + server.getAddress().getPort(),
          "quarkus.rest-client.await-bearer-probe.read-timeout",
          "100");
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "text/plain");
      exchange.sendResponseHeaders(200, bytes.length);
      try (var output = exchange.getResponseBody()) {
        output.write(bytes);
      }
    }

    @Override
    public void stop() {
      if (server != null) {
        server.stop(0);
      }
    }
  }
}
