package org.qubership.integration.platform.ai.productpipeline.create.design.execution;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogSystemReadTool;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.NormalizedDesignFlow;

/**
 * Read-only local-catalog matcher for catalog-first binding resolution. Never queries APIHub and
 * never imports a specification.
 */
@ApplicationScoped
public class CatalogBindingMatcher {

  private static final Pattern METHOD_PATH =
      Pattern.compile("(?i)\\b(GET|POST|PUT|PATCH|DELETE|PUBLISH|SUBSCRIBE|SEND|RECEIVE)\\s+(\\S+)");

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

  public MatchResult match(NormalizedDesignFlow flow, NormalizedDesignFlow.Step serviceCallStep) {
    Objects.requireNonNull(flow, "flow");
    Objects.requireNonNull(serviceCallStep, "serviceCallStep");
    // Support both service-call and async-api-trigger steps
    String kind = serviceCallStep.kind();
    if (!"service-call".equalsIgnoreCase(kind) && !"async-api-trigger".equalsIgnoreCase(kind)) {
        return new MatchResult.None();
    }
    String operationQuery = CatalogStrings.blankToNull(serviceCallStep.operationQuery());
    if (operationQuery == null) {
      return new MatchResult.None();
    }
    String serviceName = resolveServiceName(flow, serviceCallStep);
    String search = serviceName != null ? serviceName : operationQuery;
    List<CatalogRestClient.SystemDto> systems = catalogReadTool.searchCatalogSystems(search);
    if (systems.isEmpty() && serviceName != null) {
      systems = catalogReadTool.searchCatalogSystems(operationQuery);
    }
    ParsedQuery parsed = parseQuery(operationQuery);
    String requiredRelease = flowRelease(flow);
    List<CatalogMatch> matches = new ArrayList<>();
    for (CatalogRestClient.SystemDto system : systems) {
      if (system == null || CatalogStrings.blankToNull(system.id()) == null) {
        continue;
      }
      if (!serviceAgrees(serviceName, system.name())) {
        continue;
      }
      if (!protocolAgrees(flow, system.protocol())) {
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
        if (!releaseAgrees(requiredRelease, spec.name())) {
          continue;
        }
        List<CatalogRestClient.OperationDto> ops =
            catalogReadTool.listCatalogOperations(spec.id(), system.id(), null);
        for (CatalogRestClient.OperationDto op : ops) {
          if (op == null || CatalogStrings.blankToNull(op.id()) == null) {
            continue;
          }
          if (!operationAgrees(parsed, operationQuery, op)) {
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
   * Re-reads the catalog for a previously observed hint. Returns exact only when the hierarchy is
   * still live: the system answers to its id, the specification still belongs to it under the same
   * group, and the operation is still one the specification offers.
   *
   * <p>The hint holds ids the reader already approved, so identity is settled before this call.
   * What can still change is the catalog, and only a re-read answers that. Comparing the ids back
   * against the flow's participant name or operation query would re-derive identity from prose the
   * model wrote, which fails on wording no rule anticipated — a period at the end of a path is
   * enough. A caller who changes the requirement changes the fact text, and the hint no longer
   * matches the step, so an outdated hint cannot survive that way either.
   */
  public Optional<CatalogMatch> revalidateHint(
      NormalizedDesignFlow flow,
      NormalizedDesignFlow.Step serviceCallStep,
      String systemId,
      String specificationGroupId,
      String specificationId,
      String integrationOperationId) {
    Objects.requireNonNull(flow, "flow");
    Objects.requireNonNull(serviceCallStep, "serviceCallStep");
    if (CatalogStrings.blankToNull(systemId) == null
        || CatalogStrings.blankToNull(specificationGroupId) == null
        || CatalogStrings.blankToNull(specificationId) == null
        || CatalogStrings.blankToNull(integrationOperationId) == null) {
      return Optional.empty();
    }
    String serviceName = resolveServiceName(flow, serviceCallStep);
    String search = serviceName != null ? serviceName : serviceCallStep.operationQuery();
    List<CatalogRestClient.SystemDto> systems =
        catalogReadTool.searchCatalogSystems(search == null ? systemId : search);
    CatalogRestClient.SystemDto system =
        systems.stream().filter(s -> systemId.equals(s.id())).findFirst().orElse(null);
    if (system == null) {
      // Fall back to a direct system-id search token so stale name searches still re-read.
      systems = catalogReadTool.searchCatalogSystems(systemId);
      system = systems.stream().filter(s -> systemId.equals(s.id())).findFirst().orElse(null);
    }
    if (system == null) {
      return Optional.empty();
    }
    CatalogRestClient.SpecificationDto spec =
        catalogReadTool.getApiSpecifications(systemId).stream()
            .filter(
                s ->
                    specificationId.equals(s.id())
                        && specificationGroupId.equals(s.specificationGroupId()))
            .findFirst()
            .orElse(null);
    if (spec == null) {
      return Optional.empty();
    }
    CatalogRestClient.OperationDto op =
        catalogReadTool.listCatalogOperations(specificationId, systemId, null).stream()
            .filter(candidate -> integrationOperationId.equals(candidate.id()))
            .findFirst()
            .orElse(null);
    if (op == null) {
      return Optional.empty();
    }
    return Optional.of(
        new CatalogMatch(
            systemId,
            specificationGroupId,
            specificationId,
            integrationOperationId,
            system.name(),
            system.protocol(),
            op.method(),
            op.path(),
            op.name(),
            "catalog-revalidate:" + systemId + "/" + specificationId + "/" + integrationOperationId));
  }

  private static String resolveServiceName(
      NormalizedDesignFlow flow, NormalizedDesignFlow.Step step) {
    String to = CatalogStrings.blankToNull(step.toParticipantId());
    if (to == null) {
      return null;
    }
    for (NormalizedDesignFlow.Participant participant : flow.participants()) {
      if (to.equals(participant.participantId())) {
        return participant.displayName();
      }
    }
    return null;
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
    if (actual.contains(required) || required.contains(actual)) {
      return true;
    }
    // Uploaded spec titles and imported catalog names often differ only in punctuation
    // (en-dash vs hyphen, extra spaces, ampersand). Fall back to an alphanumeric match.
    String requiredAlpha = required.replaceAll("[^a-z0-9]", "");
    String actualAlpha = actual.replaceAll("[^a-z0-9]", "");
    return actualAlpha.contains(requiredAlpha) || requiredAlpha.contains(actualAlpha);
  }

  private static boolean protocolAgrees(NormalizedDesignFlow flow, String catalogProtocol) {
    String required = firstConstraintValue(flow, "protocol");
    if (required == null) {
      return true;
    }
    return required.equalsIgnoreCase(CatalogStrings.blankToNull(catalogProtocol));
  }

  private static String flowRelease(NormalizedDesignFlow flow) {
    String release = firstConstraintValue(flow, "release");
    if (release != null) {
      return release;
    }
    return firstConstraintValue(flow, "version");
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

  private static String firstConstraintValue(NormalizedDesignFlow flow, String key) {
    String prefix = key.toLowerCase(Locale.ROOT) + ":";
    for (String constraint : flow.constraints()) {
      if (constraint == null) {
        continue;
      }
      String trimmed = constraint.trim();
      if (trimmed.toLowerCase(Locale.ROOT).startsWith(prefix)) {
        String value = trimmed.substring(prefix.length()).trim();
        if (!value.isEmpty()) {
          return value;
        }
      }
    }
    for (String assumption : flow.assumptions()) {
      if (assumption == null) {
        continue;
      }
      String trimmed = assumption.trim();
      if (trimmed.toLowerCase(Locale.ROOT).startsWith(prefix)) {
        String value = trimmed.substring(prefix.length()).trim();
        if (!value.isEmpty()) {
          return value;
        }
      }
    }
    return null;
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
    boolean catalogContainsQuery =
        containsIgnoreCase(op.name(), lower)
            || containsIgnoreCase(op.path(), lower)
            || containsIgnoreCase(op.method(), lower);
    boolean queryContainsCatalog =
        containsInLower(lower, op.name()) || containsInLower(lower, op.path());
    if (catalogContainsQuery
        || queryContainsCatalog
        || (parsed.method() != null && parsed.path() != null)) {
      // Method+path exact agreement already checked; name may still differ.
      if (parsed.method() != null && parsed.path() != null) {
        return true;
      }
      return catalogContainsQuery
          || containsIgnoreCase(op.id(), lower)
          || tokenOverlap(lower, op.name())
          || queryContainsCatalog;
    }
    return false;
  }

  private static boolean containsInLower(String lowerQuery, String candidate) {
    return CatalogStrings.blankToNull(candidate) != null
        && lowerQuery.contains(candidate.toLowerCase(Locale.ROOT));
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
