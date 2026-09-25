package org.qubership.integration.platform.ai.plan.workdocument.checkpoint;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.integration.catalog.cache.CatalogOperationsLookupService;
import org.qubership.integration.platform.ai.integration.catalog.cache.CatalogOperationsReadCache;
import org.qubership.integration.platform.ai.integration.catalog.cache.ConversationCatalogCache;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogOperationLookup;
import org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogSystemFinder;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogSystemReadTool;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogToolSupport;
import org.qubership.integration.platform.ai.plan.workdocument.binding.CatalogResolution;
import org.qubership.integration.platform.ai.plan.workdocument.binding.ResolveApiOperationSeam;

/**
 * Catalog reads for a harness process on the host. Compose publishes runtime-catalog as
 * {@code 8091:8080}; the alias {@code runtime-catalog} only resolves inside that network.
 */
final class HostCatalog {

  static final int HOST_PUBLISHED_PORT = 8091;

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private HostCatalog() {}

  static CatalogResolution open(String configuredUrl) {
    String base = catalogBaseForHost(configuredUrl, hostResolves("runtime-catalog"));
    CatalogRestClient client = client(base);
    CatalogOperationsReadCache reads = new CatalogOperationsReadCache(client);
    ConversationCatalogCache cache = new ConversationCatalogCache(reads);
    CatalogOperationsLookupService operations = new CatalogOperationsLookupService(cache);
    CatalogSystemReadTool readTool = new CatalogSystemReadTool(client, operations, new CatalogToolSupport());
    CatalogOperationLookup lookup = new CatalogOperationLookup(new CatalogSystemFinder(client), readTool);
    return new ResolveApiOperationSeam(lookup, null);
  }

  static void bindConversation(String conversationId) {
    ToolSession.bind(conversationId);
  }

  static void clearConversation() {
    ToolSession.clear();
  }

  static String catalogBaseForHost(String configured, boolean composeAliasResolves) {
    if (configured == null || configured.isBlank()) {
      throw new IllegalStateException("CATALOG_CLIENT_UNAVAILABLE: CATALOG_URL is not set.");
    }
    String trimmed = configured.endsWith("/") ? configured.substring(0, configured.length() - 1) : configured;
    URI uri = URI.create(trimmed);
    if ("runtime-catalog".equals(uri.getHost()) && !composeAliasResolves) {
      return "http://127.0.0.1:" + HOST_PUBLISHED_PORT;
    }
    return trimmed;
  }

  private static boolean hostResolves(String host) {
    try {
      InetAddress.getByName(host);
      return true;
    } catch (Exception failure) {
      return false;
    }
  }

  private static CatalogRestClient client(String base) {
    return (CatalogRestClient)
        Proxy.newProxyInstance(
            CatalogRestClient.class.getClassLoader(),
            new Class<?>[] {CatalogRestClient.class},
            (proxy, method, args) -> {
              if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                  case "toString" -> "HostCatalogClient";
                  case "hashCode" -> System.identityHashCode(proxy);
                  case "equals" -> proxy == args[0];
                  default -> null;
                };
              }
              return switch (method.getName()) {
                case "filterSystems" -> post(base + "/v1/systems/filter", args[0], new TypeReference<List<CatalogRestClient.SystemDto>>() {});
                case "searchSystems" -> post(base + "/v1/systems/search", args[0], new TypeReference<List<CatalogRestClient.SystemDto>>() {});
                case "getApiSpecifications" ->
                    get(
                        base + "/v1/models?systemId=" + encode(String.valueOf(args[0])),
                        new TypeReference<List<CatalogRestClient.SpecificationDto>>() {});
                case "getOperations" ->
                    get(
                        base
                            + "/v1/operations?modelId="
                            + encode(String.valueOf(args[0]))
                            + "&offset="
                            + args[1]
                            + "&count="
                            + args[2]
                            + (args[3] == null ? "" : "&searchFilter=" + encode(String.valueOf(args[3]))),
                        new TypeReference<List<CatalogRestClient.OperationDto>>() {});
                default -> throw new UnsupportedOperationException(method.getName());
              };
            });
  }

  private static <T> T post(String url, Object body, TypeReference<T> type) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(Duration.ofSeconds(30))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
              .build();
      return read(HTTP.send(request, HttpResponse.BodyHandlers.ofString()), type);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("CATALOG_CLIENT_UNAVAILABLE: catalog request was interrupted.", failure);
    } catch (RuntimeException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new IllegalStateException("CATALOG_CLIENT_UNAVAILABLE: catalog request failed.", failure);
    }
  }

  private static <T> T get(String url, TypeReference<T> type) {
    try {
      HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build();
      return read(HTTP.send(request, HttpResponse.BodyHandlers.ofString()), type);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("CATALOG_CLIENT_UNAVAILABLE: catalog request was interrupted.", failure);
    } catch (RuntimeException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new IllegalStateException("CATALOG_CLIENT_UNAVAILABLE: catalog request failed.", failure);
    }
  }

  private static <T> T read(HttpResponse<String> response, TypeReference<T> type) throws Exception {
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IllegalStateException("CATALOG_CLIENT_UNAVAILABLE: catalog returned HTTP " + response.statusCode() + ".");
    }
    return JSON.readValue(response.body(), type);
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
