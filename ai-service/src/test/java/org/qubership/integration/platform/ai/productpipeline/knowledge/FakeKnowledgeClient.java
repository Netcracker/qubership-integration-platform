package org.qubership.integration.platform.ai.productpipeline.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic in-memory {@link KnowledgeClient} for consumer unit tests. Does not recreate the
 * deleted JSONL object-store implementation.
 */
public final class FakeKnowledgeClient implements KnowledgeClient, KnowledgeContextProvider {

  private final KnowledgeResponseIdentity identity;
  private final KnowledgeQueryContext context;
  private final Map<String, CanonicalKnowledgeObject> byId = new LinkedHashMap<>();
  private final Map<String, String> aliasToId = new LinkedHashMap<>();
  private int contextCalls;
  private KnowledgeContextRequest lastContextRequest;

  public FakeKnowledgeClient(KnowledgeResponseIdentity identity) {
    this.identity = Objects.requireNonNull(identity, "identity");
    this.context = new KnowledgeQueryContext(identity.packageRef());
  }

  public static FakeKnowledgeClient defaultFixture() {
    KnowledgePackageRef packageRef =
        new KnowledgePackageRef(
            "fixture@1.0.0",
            "1.0.0",
            "1.0.0",
            "sha256:digest-test",
            "CERTIFIED",
            "sha256:certificate");
    FakeKnowledgeClient client =
        new FakeKnowledgeClient(new KnowledgeResponseIdentity(packageRef));
    client.put(
        "CIP:GEN-000049",
        "GeneratorHint",
        "Error Handling Generator Mapping",
        "Maps catch/try-catch rules including R-502 and VR-E-010.",
        List.of());
    client.put(
        "CIP:GEN-000006",
        "GeneratorHint",
        "GEN-05: Auth Generator Contract",
        "M2M v2 auth is required for internal service-calls (GEN-05).",
        List.of("GEN-05", "gen-05"));
    client.put(
        "CIP:STD-000085",
        "Standard",
        "ADR-003: Platform M2M as Sole Internal Authentication",
        "M2M v2 is required for internal service-calls.",
        List.of("adr-003-platform-m2m-as-sole-internal-authentication", "ADR-003"));
    client.put(
        "CIP:DOC-000001",
        "Document",
        "Generator Contracts",
        "Phase 7 generator contract specification for every AI generator.",
        List.of("GENERATOR_CONTRACTS", "generator-contracts"));
    return client;
  }

  public KnowledgeQueryContext context() {
    return context;
  }

  public KnowledgeResponseIdentity identity() {
    return identity;
  }

  public void put(String id, String type, String title, String body, List<String> aliases) {
    List<String> aliasList = aliases == null ? List.of() : List.copyOf(aliases);
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("aliases", aliasList);
    metadata.put("tags", aliasList);
    metadata.put("anchor", null);
    CanonicalKnowledgeObject object =
        new CanonicalKnowledgeObject(
            "1.0",
            id,
            type,
            title,
            body == null ? "" : body,
            metadata,
            List.of(),
            new CanonicalKnowledgeObject.Content(
                "markdown", body == null ? "" : body, body == null ? "" : body, List.of()),
            identity.packageRef().knowledgeVersion(),
            "active",
            new CanonicalKnowledgeObject.Source(
                "markdown",
                "fixtures/" + id + ".md",
                id.toLowerCase(Locale.ROOT),
                "sha256:source",
                identity.packageRef().knowledgeVersion()));
    byId.put(id, object);
    for (String alias : aliasList) {
      if (alias == null || alias.isBlank()) {
        continue;
      }
      aliasToId.putIfAbsent(alias.toLowerCase(Locale.ROOT), id);
    }
  }

  @Override
  public KnowledgeQueryContext forConversation(String conversationId) {
    return context;
  }

  @Override
  public KnowledgeObjectResult exact(KnowledgeQueryContext queryContext, String id) {
    requireContext(queryContext);
    if (id == null || id.isBlank()) {
      throw new KnowledgeClientException(
          KnowledgeFailureKind.KNOWLEDGE_INVALID_REQUEST, "id is required");
    }
    CanonicalKnowledgeObject found = resolve(id.trim());
    if (found == null) {
      throw new KnowledgeClientException(
          KnowledgeFailureKind.KNOWLEDGE_NOT_FOUND, "object not found: " + id.trim());
    }
    return new KnowledgeObjectResult(identity, found);
  }

  @Override
  public KnowledgeSearchResult filter(KnowledgeQueryContext queryContext, KnowledgeFilter filter) {
    requireContext(queryContext);
    Objects.requireNonNull(filter, "filter");
    List<CanonicalKnowledgeObject> matched = new ArrayList<>();
    for (CanonicalKnowledgeObject object : byId.values()) {
      if (filter.type() != null && !filter.type().equals(object.type())) {
        continue;
      }
      matched.add(object);
      if (matched.size() >= filter.limit()) {
        break;
      }
    }
    return new KnowledgeSearchResult(identity, matched);
  }

  @Override
  public KnowledgeRelationResult relations(
      KnowledgeQueryContext queryContext, String id, Set<String> kinds) {
    requireContext(queryContext);
    return new KnowledgeRelationResult(identity, List.of());
  }

  private CanonicalKnowledgeObject resolve(String key) {
    CanonicalKnowledgeObject exact = byId.get(key);
    if (exact != null) {
      return exact;
    }
    String canonicalId = aliasToId.get(key.toLowerCase(Locale.ROOT));
    return canonicalId == null ? null : byId.get(canonicalId);
  }

  @Override
  public KnowledgeContextPackage context(
      KnowledgeQueryContext queryContext, KnowledgeContextRequest request) {
    requireContext(queryContext);
    contextCalls++;
    lastContextRequest = Objects.requireNonNull(request, "request");
    CanonicalKnowledgeObject mapping = byId.get("CIP:GEN-000049");
    return new KnowledgeContextPackage(
        identity,
        List.of("error", "generator", "handling", "mapping", "rule"),
        List.of(mapping),
        mapping.content().body().length());
  }

  public int contextCalls() {
    return contextCalls;
  }

  public KnowledgeContextRequest lastContextRequest() {
    return lastContextRequest;
  }

  private void requireContext(KnowledgeQueryContext queryContext) {
    Objects.requireNonNull(queryContext, "context");
    if (!identity.packageRef().equals(queryContext.packageRef())) {
      throw new KnowledgeClientException(
          KnowledgeFailureKind.KNOWLEDGE_PACKAGE_PIN_MISMATCH,
          "expectedPackageChecksum does not match the active package");
    }
  }
}
