package org.qubership.integration.platform.ai.a2a.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.RestAssured;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Shared HTTP SSE helpers for A2A streaming contract tests. */
public final class A2aSseTestSupport {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final HttpClient CLIENT =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private A2aSseTestSupport() {}

  public static List<JsonNode> collectSseEvents(String method, String path, String body, Duration timeout)
      throws Exception {
    HttpResponse<InputStream> response = openSse(method, path, body, timeout);
    CompletableFuture<List<JsonNode>> future =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return readSse(response.body());
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });
    return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  /**
   * Opens an SSE response and reads frames until the stream ends. Invokes {@code afterFirstFrame}
   * once the first event is parsed so the caller can publish later live updates.
   */
  public static List<JsonNode> collectSseEventsAfterFirstFrame(
      String method,
      String path,
      String body,
      Duration timeout,
      Runnable afterFirstFrame)
      throws Exception {
    HttpResponse<InputStream> response = openSse(method, path, body, timeout);
    CompletableFuture<List<JsonNode>> future =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return readSseAfterFirstFrame(response.body(), afterFirstFrame);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });
    return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  /**
   * Opens an SSE response and returns after the first event is parsed, leaving the connection open
   * for the caller to release a hold-open producer and then drain the remainder.
   */
  public static FirstFrameSse openUntilFirstFrame(
      String method, String path, String body, Duration timeout) throws Exception {
    HttpResponse<InputStream> response = openSse(method, path, body, timeout);
    InputStream stream = response.body();
    BufferedReader reader =
        new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
    List<JsonNode> events = new ArrayList<>();
    StringBuilder data = new StringBuilder();
    String line;
    while ((line = reader.readLine()) != null) {
      if (line.startsWith("data:")) {
        data.append(line.substring("data:".length()).trim());
      } else if (line.isEmpty()) {
        if (data.length() > 0) {
          events.add(MAPPER.readTree(data.toString()));
          data.setLength(0);
          return new FirstFrameSse(events, reader, stream);
        }
      }
    }
    if (data.length() > 0) {
      events.add(MAPPER.readTree(data.toString()));
      return new FirstFrameSse(events, reader, stream);
    }
    reader.close();
    throw new AssertionError("SSE stream closed before the first frame");
  }

  public static List<JsonNode> drainRemaining(FirstFrameSse open) throws Exception {
    List<JsonNode> events = new ArrayList<>(open.firstEvents());
    StringBuilder data = new StringBuilder();
    try (BufferedReader reader = open.reader()) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.startsWith("data:")) {
          data.append(line.substring("data:".length()).trim());
        } else if (line.isEmpty()) {
          if (data.length() > 0) {
            events.add(MAPPER.readTree(data.toString()));
            data.setLength(0);
          }
        }
      }
      if (data.length() > 0) {
        events.add(MAPPER.readTree(data.toString()));
      }
    }
    return events;
  }

  public record FirstFrameSse(
      List<JsonNode> firstEvents, BufferedReader reader, InputStream stream) {}

  private static HttpResponse<InputStream> openSse(
      String method, String path, String body, Duration timeout) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + RestAssured.port + path))
            .timeout(timeout)
            .header("A2A-Version", "1.0")
            .header("Accept", "text/event-stream");
    if ("POST".equalsIgnoreCase(method)) {
      builder.header("Content-Type", "application/json");
      builder.POST(
          HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body, StandardCharsets.UTF_8));
    } else {
      builder.GET();
    }

    HttpResponse<InputStream> response =
        CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
    if (response.statusCode() >= 400) {
      String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
      throw new AssertionError(
          "SSE request failed status=" + response.statusCode() + " body=" + errorBody);
    }
    return response;
  }

  public static List<JsonNode> readSse(InputStream inputStream) throws Exception {
    return readSseAfterFirstFrame(inputStream, null);
  }

  public static List<JsonNode> readSseAfterFirstFrame(InputStream inputStream, Runnable afterFirstFrame)
      throws Exception {
    List<JsonNode> events = new ArrayList<>();
    boolean signaled = false;
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
      StringBuilder data = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.startsWith("data:")) {
          data.append(line.substring("data:".length()).trim());
        } else if (line.isEmpty()) {
          if (data.length() > 0) {
            events.add(MAPPER.readTree(data.toString()));
            data.setLength(0);
            if (!signaled && afterFirstFrame != null) {
              signaled = true;
              afterFirstFrame.run();
            }
          }
        }
      }
      if (data.length() > 0) {
        events.add(MAPPER.readTree(data.toString()));
        if (!signaled && afterFirstFrame != null) {
          afterFirstFrame.run();
        }
      }
    }
    return events;
  }

  public static String eventState(JsonNode event) {
    JsonNode state = event.at("/status/state");
    if (!state.isMissingNode() && !state.isNull()) {
      return state.asText();
    }
    state = event.at("/task/status/state");
    if (!state.isMissingNode() && !state.isNull()) {
      return state.asText();
    }
    state = event.at("/statusUpdate/status/state");
    if (!state.isMissingNode() && !state.isNull()) {
      return state.asText();
    }
    // Proto JSON may nest under lowercase enum-style wrappers.
    String raw = event.toString().toUpperCase(Locale.ROOT);
    if (raw.contains("TASK_STATE_INPUT_REQUIRED")) {
      return "TASK_STATE_INPUT_REQUIRED";
    }
    if (raw.contains("TASK_STATE_COMPLETED")) {
      return "TASK_STATE_COMPLETED";
    }
    if (raw.contains("TASK_STATE_FAILED")) {
      return "TASK_STATE_FAILED";
    }
    if (raw.contains("TASK_STATE_WORKING")) {
      return "TASK_STATE_WORKING";
    }
    if (raw.contains("TASK_STATE_SUBMITTED")) {
      return "TASK_STATE_SUBMITTED";
    }
    return "";
  }

  public static boolean isTaskEvent(JsonNode event) {
    if (event.has("id") && event.has("status") && event.has("contextId")) {
      return true;
    }
    if (event.has("task") && event.get("task").has("id")) {
      return true;
    }
    String kind = event.path("kind").asText("");
    return "task".equals(kind);
  }

  public static String textMessageBody(String messageId, String taskId, String text) {
    String taskField = taskId == null ? "" : "\"taskId\": \"%s\",".formatted(taskId);
    return """
        {
          "message": {
            "metadata": { "skillId": "create-chain@2" },
            "messageId": "%s",
            %s
            "role": "ROLE_USER",
            "parts": [ { "text": "%s" } ]
          }
        }
        """
        .formatted(messageId, taskField, text);
  }
}
