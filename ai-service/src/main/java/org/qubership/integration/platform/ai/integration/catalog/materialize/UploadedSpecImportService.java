package org.qubership.integration.platform.ai.integration.catalog.materialize;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.attachment.SpecType;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubRequirementRefs;
import org.qubership.integration.platform.ai.integration.catalog.cache.ConversationCatalogCache;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateEnvironmentRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateSystemRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogSystemSearchRequest;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;
import org.qubership.integration.platform.ai.plan.ResolvedCatalogBinding;
import org.qubership.integration.platform.ai.plan.UploadedSpecCandidate;
import org.qubership.integration.platform.ai.storage.S3Service;

@ApplicationScoped
public class UploadedSpecImportService {

  private static final Logger LOG = Logger.getLogger(UploadedSpecImportService.class);

  private static final String DEFAULT_SYSTEM_TYPE = ApiHubRequirementRefs.DEFAULT_SYSTEM_TYPE;

  private final CatalogRestClient catalogRestClient;
  private final CatalogSpecificationImporter catalogSpecificationImporter;
  private final ConversationCatalogCache catalogCache;
  private final S3Service s3Service;

  @Inject
  public UploadedSpecImportService(
      @RestClient CatalogRestClient catalogRestClient,
      CatalogSpecificationImporter catalogSpecificationImporter,
      ConversationCatalogCache catalogCache,
      S3Service s3Service) {
    this.catalogRestClient = catalogRestClient;
    this.catalogSpecificationImporter = catalogSpecificationImporter;
    this.catalogCache = catalogCache;
    this.s3Service = s3Service;
  }

  public List<UploadedSpecImportResult> importCandidates(
      String conversationId, List<UploadedSpecCandidate> candidates) {
    List<UploadedSpecImportResult> results = new ArrayList<>();
    for (UploadedSpecCandidate candidate : candidates) {
      results.add(importCandidate(conversationId, candidate));
    }
    return List.copyOf(results);
  }

  private UploadedSpecImportResult importCandidate(
      String conversationId, UploadedSpecCandidate candidate) {
    String systemName = candidate.title();
    String groupName = candidate.title();
    String systemId = findOrCreateSystem(conversationId, systemName, DEFAULT_SYSTEM_TYPE);
    ensureDefaultEnvironment(systemId, DEFAULT_SYSTEM_TYPE, systemName);

    Optional<UploadedSpecImportResult> reused =
        reuseExistingCatalogSpecification(conversationId, systemId, groupName, candidate.s3Key());
    if (reused.isPresent()) {
      return reused.get();
    }

    byte[] content = s3Service.readObjectBytes(candidate.s3Key());
    // Let runtime-catalog auto-detect the protocol from the file contents. Explicit "asyncapi"
    // is rejected by OperationProtocol.fromValue; catalog extraction resolves kafka/amqp bindings.
    String protocol = null;
    String fileName = candidate.s3Key();
    int slash = fileName.lastIndexOf('/');
    if (slash >= 0) {
      fileName = fileName.substring(slash + 1);
    }

    CatalogSpecificationImporter.ImportOutcome imported =
        catalogSpecificationImporter.importOpenApiDocument(
            systemId, groupName, protocol, content, fileName);

    ResolvedCatalogBinding binding =
        new ResolvedCatalogBinding(
            systemId,
            imported.specificationId(),
            imported.specificationGroupId(),
            null,
            DEFAULT_SYSTEM_TYPE);
    populateCache(conversationId, binding, groupName);
    LOG.infof(
        "UPLOADED_SPEC: imported s3Key=%s systemId=%s specId=%s",
        candidate.s3Key(), systemId, imported.specificationId());
    return new UploadedSpecImportResult(candidate.s3Key(), binding);
  }

  private Optional<UploadedSpecImportResult> reuseExistingCatalogSpecification(
      String conversationId, String systemId, String groupName, String s3Key) {
    List<CatalogRestClient.SpecificationDto> specs = catalogRestClient.getApiSpecifications(systemId);
    if (specs == null || specs.isEmpty()) {
      return Optional.empty();
    }
    String trimmedGroup = groupName.trim();
    for (CatalogRestClient.SpecificationDto spec : specs) {
      if (spec == null || CatalogStrings.blankToNull(spec.id()) == null) {
        continue;
      }
      if (!ApiHubSpecificationImportService.belongsToSpecificationGroup(
          spec, systemId, trimmedGroup)) {
        continue;
      }
      LOG.infof(
          "UPLOADED_SPEC: reusing existing spec specId=%s systemId=%s group=%s",
          spec.id(), systemId, trimmedGroup);
      ResolvedCatalogBinding binding =
          new ResolvedCatalogBinding(
              systemId, spec.id(), spec.specificationGroupId(), null, DEFAULT_SYSTEM_TYPE);
      populateCache(conversationId, binding, groupName);
      return Optional.of(new UploadedSpecImportResult(s3Key, binding));
    }
    return Optional.empty();
  }

  private String findOrCreateSystem(String conversationId, String systemName, String systemType) {
    Optional<String> existing = findSystemByName(conversationId, systemName);
    if (existing.isPresent()) {
      return existing.get();
    }

    CatalogRestClient.SystemDto created =
        catalogRestClient.createSystem(
            new CatalogCreateSystemRequest(systemName, systemType));
    if (created == null || CatalogStrings.blankToNull(created.id()) == null) {
      throw new IllegalStateException("createSystem returned no systemId for name=" + systemName);
    }
    if (conversationId != null && !conversationId.isBlank()) {
      catalogCache.rememberSystems(conversationId, List.of(created));
      catalogCache.rememberActiveSystemId(conversationId, created.id());
    }
    return created.id();
  }

  private Optional<String> findSystemByName(String conversationId, String systemName) {
    List<CatalogRestClient.SystemDto> found =
        catalogRestClient.searchSystems(new CatalogSystemSearchRequest(systemName));
    if (found == null || found.isEmpty()) {
      return Optional.empty();
    }
    for (CatalogRestClient.SystemDto system : found) {
      if (system != null
          && system.name() != null
          && system.name().equalsIgnoreCase(systemName.trim())
          && CatalogStrings.blankToNull(system.id()) != null) {
        if (conversationId != null && !conversationId.isBlank()) {
          catalogCache.rememberSystems(conversationId, List.of(system));
        }
        return Optional.of(system.id());
      }
    }
    return Optional.empty();
  }

  private void ensureDefaultEnvironment(String systemId, String systemType, String systemName) {
    if (CatalogStrings.blankToNull(systemId) == null) {
      return;
    }
    String normalizedType = systemType == null ? "" : systemType.trim().toUpperCase(Locale.ROOT);
    if (!"INTERNAL".equals(normalizedType) && !"IMPLEMENTED".equals(normalizedType)) {
      return;
    }
    List<CatalogRestClient.EnvironmentDto> environments =
        catalogRestClient.getEnvironments(systemId);
    if (environments != null && !environments.isEmpty()) {
      return;
    }
    String envName =
        CatalogStrings.blankToNull(systemName) != null ? systemName.trim() : "Default";
    catalogRestClient.createEnvironment(
        systemId, new CatalogCreateEnvironmentRequest(envName, "/"));
  }

  private void populateCache(
      String conversationId, ResolvedCatalogBinding binding, String groupName) {
    if (conversationId == null || conversationId.isBlank()) {
      return;
    }
    catalogCache.rememberSystems(
        conversationId,
        List.of(
            new CatalogRestClient.SystemDto(
                binding.systemId(), groupName, binding.systemType(), null)));
    catalogCache.rememberSpecifications(
        conversationId,
        List.of(
            new CatalogRestClient.SpecificationDto(
                binding.specificationId(), groupName, binding.specificationGroupId(), binding.systemId())));
    catalogCache.rememberActiveSystemId(conversationId, binding.systemId());
  }
}
