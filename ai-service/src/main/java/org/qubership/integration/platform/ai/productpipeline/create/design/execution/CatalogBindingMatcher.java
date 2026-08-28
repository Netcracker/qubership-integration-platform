package org.qubership.integration.platform.ai.productpipeline.create.design.execution;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogSystemReadTool;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingResolution;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode;

/**
 * Read-only local-catalog matcher for catalog-first binding resolution. Never queries APIHub and
 * never imports a specification.
 */
@ApplicationScoped
public class CatalogBindingMatcher {

  private static final Pattern METHOD_PATH =
      Pattern.compile("(?i)\\b(GET|POST|PUT|PATCH|DELETE)\\s+(/\\S+)");

  private final CatalogSystemReadTool catalogReadTool;

  @Inject
  public CatalogBindingMatcher(CatalogSystemReadTool catalogReadTool) {
    this.catalogReadTool = Objects.requireNonNull(catalogReadTool, "catalogReadTool");
  }

  /** Result of matching one service-call query against the local catalog. */
  public sealed interface MatchResult {
    record Exact(CatalogMatch match) implements MatchResult {}

    record Ambiguous(List<String> candidateIds) implements MatchResult {}

    record None() implements MatchResult {}
  }

  /** One complete reusable catalog hierarchy hit. */
  public record CatalogMatch(
      String systemId,
      String specificationGroupId,
      String specificationId,
      String integrationOperationId,
      String systemName,
      String protocol,
      String method,
      String path,
      String operationName,
      String evidenceRef) {}

  public MatchResult match(
      String serviceName, String operationQuery, String protocol, String release) {
    String query = CatalogStrings.blankToNull(operationQuery);
    if (query == null) {
      return new MatchResult.None();
    }
    String resolvedService = CatalogStrings.blankToNull(serviceName);
    String search = resolvedService != null ? resolvedService : query;
    List<CatalogRestClient.SystemDto> systems = catalogReadTool.searchCatalogSystems(search);
    if (systems.isEmpty() && resolvedService != null) {
      systems = catalogReadTool.searchCatalogSystems(query);
    }
    ParsedQuery parsed = parseQuery(query);
    List<CatalogMatch> matches = new ArrayList<>();
    for (CatalogRestClient.SystemDto system : systems) {
      if (system == null || CatalogStrings.blankToNull(system.id()) == null) {
        continue;
      }
      if (!serviceAgrees(resolvedService, system.name())) {
        continue;
      }
      if (!protocolAgrees(protocol, system.protocol())) {
        continue;
      }
      List<CatalogRestClient.SpecificationDto> specs =
          catalogReadTool.getApiSpecifications(system.id());
      for (CatalogRestClient.SpecificationDto spec : specs) {
        if (spec == null
            || CatalogStrings.blankToNull(spec.id()) == null
            || CatalogStrings.blankToNull(spec.specificationGroupId()) == null) {
          continue;
        }
        if (!releaseAgrees(release, spec.name())) {
          continue;
        }
        List<CatalogRestClient.OperationDto> ops =
            catalogReadTool.listCatalogOperations(spec.id(), system.id(), null);
        for (CatalogRestClient.OperationDto op : ops) {
          if (op == null || CatalogStrings.blankToNull(op.id()) == null) {
            continue;
          }
          if (!operationAgrees(parsed, query, op)) {
            continue;
          }
          matches.add(
              new CatalogMatch(
                  system.id(),
                  spec.specificationGroupId(),
                  spec.id(),
                  op.id(),
                  system.name(),
                  system.protocol(),
                  op.method(),
                  op.path(),
                  op.name(),
                  "catalog-read:" + system.id() + "/" + spec.id() + "/" + op.id()));
        }
      }
    }
    if (matches.isEmpty()) {
      return new MatchResult.None();
    }
    if (matches.size() == 1) {
      return new MatchResult.Exact(matches.getFirst());
    }
    LinkedHashSet<String> ids = new LinkedHashSet<>();
    for (CatalogMatch match : matches) {
      ids.add(match.integrationOperationId());
    }
    return new MatchResult.Ambiguous(List.copyOf(ids));
  }

  /**
   * Indexes resolved bindings by {@code serviceCallId}. Matching is exact owner identity: the same
   * operation UUID on two occurrences stays two keys. Missing, duplicate, or extra bindings fail
   * fast. Participant names and operation text are not consulted.
   */
  public Map<String, CatalogBindingResolution> match(
      List<SemanticNode.ServiceCall> calls, List<CatalogBindingResolution> bindings) {
    Objects.requireNonNull(calls, "calls");
    Objects.requireNonNull(bindings, "bindings");
    Map<String, CatalogBindingResolution> byId = new LinkedHashMap<>();
    for (CatalogBindingResolution binding : bindings) {
      if (binding == null) {
        throw new IllegalArgumentException("catalog binding is required");
      }
      CatalogBindingResolution previous = byId.putIfAbsent(binding.serviceCallId(), binding);
      if (previous != null) {
        throw new IllegalArgumentException(
            "duplicate catalog binding for serviceCallId=" + binding.serviceCallId());
      }
    }
    Map<String, CatalogBindingResolution> matched = new LinkedHashMap<>();
    for (SemanticNode.ServiceCall call : calls) {
      if (call == null) {
        throw new IllegalArgumentException("service call is required");
      }
      CatalogBindingResolution binding = byId.remove(call.serviceCallId());
      if (binding == null) {
        throw new IllegalArgumentException(
            "missing catalog binding for serviceCallId=" + call.serviceCallId());
      }
      matched.put(call.serviceCallId(), binding);
    }
    if (!byId.isEmpty()) {
      throw new IllegalArgumentException(
          "extra catalog binding for serviceCallId=" + byId.keySet().iterator().next());
    }
    return Map.copyOf(matched);
  }

  private static boolean serviceAgrees(String requiredService, String catalogName) {
    if (CatalogStrings.blankToNull(requiredService) == null) {
      return true;
    }
    if (CatalogStrings.blankToNull(catalogName) == null) {
      return false;
    }
    String required = requiredService.trim().toLowerCase(Locale.ROOT);
    String actual = catalogName.trim().toLowerCase(Locale.ROOT);
    return actual.contains(required) || required.contains(actual);
  }

  private static boolean protocolAgrees(String requiredProtocol, String catalogProtocol) {
    String required = CatalogStrings.blankToNull(requiredProtocol);
    if (required == null) {
      return true;
    }
    return required.equalsIgnoreCase(CatalogStrings.blankToNull(catalogProtocol));
  }

  private static boolean releaseAgrees(String requiredRelease, String specificationName) {
    if (CatalogStrings.blankToNull(requiredRelease) == null) {
      return true;
    }
    if (CatalogStrings.blankToNull(specificationName) == null) {
      return false;
    }
    return specificationName.toLowerCase(Locale.ROOT)
        .contains(requiredRelease.toLowerCase(Locale.ROOT));
  }

  private static ParsedQuery parseQuery(String operationQuery) {
    if (operationQuery == null) {
      return new ParsedQuery(null, null, null);
    }
    Matcher matcher = METHOD_PATH.matcher(operationQuery);
    String method = null;
    String path = null;
    if (matcher.find()) {
      method = matcher.group(1).toUpperCase(Locale.ROOT);
      path = matcher.group(2);
    }
    return new ParsedQuery(method, path, operationQuery.trim());
  }

  private static boolean operationAgrees(
      ParsedQuery parsed, String operationQuery, CatalogRestClient.OperationDto op) {
    if (parsed.method() != null
        && CatalogStrings.blankToNull(op.method()) != null
        && !parsed.method().equalsIgnoreCase(op.method().trim())) {
      return false;
    }
    if (parsed.path() != null
        && CatalogStrings.blankToNull(op.path()) != null
        && !pathsAgree(parsed.path(), op.path())) {
      return false;
    }
    String needle = CatalogStrings.blankToNull(operationQuery);
    if (needle == null) {
      return false;
    }
    String lower = needle.toLowerCase(Locale.ROOT);
    if (containsIgnoreCase(op.name(), lower)
        || containsIgnoreCase(op.path(), lower)
        || containsIgnoreCase(op.method(), lower)
        || (parsed.method() != null && parsed.path() != null)) {
      // Method+path exact agreement already checked; name may still differ.
      if (parsed.method() != null && parsed.path() != null) {
        return true;
      }
      return containsIgnoreCase(op.name(), lower)
          || containsIgnoreCase(op.id(), lower)
          || tokenOverlap(lower, op.name());
    }
    return false;
  }

  private static boolean pathsAgree(String required, String actual) {
    String left = trimTrailingSlash(required.trim());
    String right = trimTrailingSlash(actual.trim());
    return left.equalsIgnoreCase(right);
  }

  private static String trimTrailingSlash(String path) {
    if (path.length() > 1 && path.endsWith("/")) {
      return path.substring(0, path.length() - 1);
    }
    return path;
  }

  private static boolean containsIgnoreCase(String value, String needleLower) {
    return value != null && value.toLowerCase(Locale.ROOT).contains(needleLower);
  }

  private static boolean tokenOverlap(String queryLower, String operationName) {
    if (CatalogStrings.blankToNull(operationName) == null) {
      return false;
    }
    String nameLower = operationName.toLowerCase(Locale.ROOT);
    String[] tokens = queryLower.split("[^a-z0-9]+");
    int hits = 0;
    int significant = 0;
    for (String token : tokens) {
      if (token.length() < 4) {
        continue;
      }
      significant++;
      if (nameLower.contains(token)) {
        hits++;
      }
    }
    return significant > 0 && hits == significant;
  }

  private record ParsedQuery(String method, String path, String raw) {}
}
