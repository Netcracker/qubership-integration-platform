package org.qubership.integration.platform.ai.integration.catalog.materialize;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.planning.ApiHubImportCandidate;
import org.qubership.integration.platform.ai.chat.planning.ApiHubImportResult;
import org.qubership.integration.platform.ai.chat.planning.ConversationPlanningDiaryService;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubDocumentPayload;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubMcpTools;
import org.qubership.integration.platform.ai.integration.catalog.cache.ConversationCatalogCache;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateEnvironmentRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateSystemRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogSystemSearchRequest;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Imports a full ApiHub source document into runtime-catalog (create/reuse system, multipart
 * upload, poll). Used by IMPORT_SPECIFICATION scenario only.
 */
@ApplicationScoped
public class ApiHubSpecificationImportService {

  private static final Logger LOG = Logger.getLogger(ApiHubSpecificationImportService.class);
  private static final String DEFAULT_DOCUMENT_SLUG = "api";

  private final CatalogRestClient catalogRestClient;
  private final ConversationCatalogCache catalogCache;
  private final CatalogSpecificationImporter catalogSpecificationImporter;
  private final ApiHubMcpTools apiHubMcpTools;
  private final ConversationPlanningDiaryService planningDiaryService;

  @Inject
  public ApiHubSpecificationImportService(
      @RestClient CatalogRestClient catalogRestClient,
      ConversationCatalogCache catalogCache,
      CatalogSpecificationImporter catalogSpecificationImporter,
      ApiHubMcpTools apiHubMcpTools,
      ConversationPlanningDiaryService planningDiaryService) {
    this.catalogRestClient = catalogRestClient;
    this.catalogCache = catalogCache;
    this.catalogSpecificationImporter = catalogSpecificationImporter;
    this.apiHubMcpTools = apiHubMcpTools;
    this.planningDiaryService = planningDiaryService;
  }

  public record ImportOutcome(ApiHubImportResult result) {}

  public ImportOutcome importCandidate(String conversationId, ApiHubImportCandidate candidate) {
    if (candidate == null || !candidate.hasRequiredFields()) {
      throw new IllegalArgumentException(
          "Import candidate is missing required fields (catalogSystemName, catalogSystemType,"
              + " apiHubPackageId, apiHubVersion, apiHubDocumentId, apiHubSpecificationName)");
    }

    Optional<ImportOutcome> fromDiary = reuseDiaryImportResult(conversationId, candidate);
    if (fromDiary.isPresent()) {
      return fromDiary.get();
    }

    String systemId = findOrCreateSystem(conversationId, candidate);
    ensureDefaultEnvironment(
        systemId, candidate.catalogSystemType(), candidate.catalogSystemName());
    String groupName = candidate.apiHubSpecificationName().trim();

    Optional<ImportOutcome> fromCatalog =
        reuseExistingCatalogSpecification(conversationId, candidate, systemId, groupName);
    if (fromCatalog.isPresent()) {
      return fromCatalog.get();
    }

    String documentSlug =
        CatalogStrings.blankToNull(candidate.apiHubDocumentId()) != null
            ? candidate.apiHubDocumentId().trim()
            : DEFAULT_DOCUMENT_SLUG;

    ApiHubDocumentPayload document =
        apiHubMcpTools.fetchApiHubDocument(
            candidate.apiHubPackageId(),
            candidate.apiHubVersion(),
            documentSlug,
            "rest");

    CatalogSpecificationImporter.ImportOutcome imported =
        catalogSpecificationImporter.importOpenApiDocument(
            systemId, groupName, null, document.content(), document.fileName());

    return finalizeImport(conversationId, candidate, systemId, groupName, documentSlug, imported);
  }

  private Optional<ImportOutcome> reuseDiaryImportResult(
      String conversationId, ApiHubImportCandidate candidate) {
    if (conversationId == null || conversationId.isBlank()) {
      return Optional.empty();
    }
    Optional<ApiHubImportResult> existing = planningDiaryService.lastImportResult(conversationId);
    if (existing.isEmpty()) {
      return Optional.empty();
    }
    ApiHubImportResult result = existing.get();
    if (!matchesCandidate(result, candidate)) {
      return Optional.empty();
    }
    LOG.infof(
        "IMPORT_SPECIFICATION: reusing diary import result systemId=%s specId=%s"
            + " conversationId=%s",
        result.systemId(),
        result.specificationId(),
        conversationId);
    return Optional.of(new ImportOutcome(result));
  }

  private Optional<ImportOutcome> reuseExistingCatalogSpecification(
      String conversationId,
      ApiHubImportCandidate candidate,
      String systemId,
      String groupName) {
    List<CatalogRestClient.SpecificationDto> specs =
        catalogRestClient.getApiSpecifications(systemId);
    if (specs == null || specs.isEmpty()) {
      return Optional.empty();
    }
    for (CatalogRestClient.SpecificationDto spec : specs) {
      if (spec == null || spec.name() == null) {
        continue;
      }
      if (!spec.name().equalsIgnoreCase(groupName.trim())) {
        continue;
      }
      if (CatalogStrings.blankToNull(spec.id()) == null) {
        continue;
      }
      LOG.infof(
          "IMPORT_SPECIFICATION: reusing existing catalog specification specId=%s systemId=%s"
              + " name=%s conversationId=%s",
          spec.id(),
          systemId,
          groupName,
          conversationId);
      ApiHubImportResult result =
          new ApiHubImportResult(
              candidate.candidateId(),
              java.time.Instant.now(),
              systemId,
              spec.id(),
              spec.specificationGroupId(),
              null,
              groupName);
      planningDiaryService.recordImportResult(conversationId, result);
      if (conversationId != null && !conversationId.isBlank()) {
        catalogCache.rememberSpecifications(conversationId, List.of(spec));
        catalogCache.rememberActiveSystemId(conversationId, systemId);
      }
      planningDiaryService.recordCatalogSpecifications(conversationId, systemId, List.of(spec));
      return Optional.of(new ImportOutcome(result));
    }
    return Optional.empty();
  }

  private ImportOutcome finalizeImport(
      String conversationId,
      ApiHubImportCandidate candidate,
      String systemId,
      String groupName,
      String documentSlug,
      CatalogSpecificationImporter.ImportOutcome imported) {
    CatalogRestClient.SpecificationDto spec =
        new CatalogRestClient.SpecificationDto(
            imported.specificationId(),
            groupName,
            imported.specificationGroupId(),
            systemId);
    if (conversationId != null && !conversationId.isBlank()) {
      catalogCache.rememberSystems(
          conversationId,
          List.of(
              new CatalogRestClient.SystemDto(
                  systemId, candidate.catalogSystemName(), candidate.catalogSystemType(), null)));
      catalogCache.rememberSpecifications(conversationId, List.of(spec));
      catalogCache.rememberActiveSystemId(conversationId, systemId);
    }

    ApiHubImportResult result =
        new ApiHubImportResult(
            candidate.candidateId(),
            java.time.Instant.now(),
            systemId,
            imported.specificationId(),
            imported.specificationGroupId(),
            imported.importId(),
            groupName);
    planningDiaryService.recordImportResult(conversationId, result);
    planningDiaryService.recordCatalogSpecifications(
        conversationId, systemId, List.of(spec));

    LOG.infof(
        "IMPORT_SPECIFICATION: imported specId=%s systemId=%s packageId=%s version=%s"
            + " specName=%s documentSlug=%s importId=%s conversationId=%s",
        imported.specificationId(),
        systemId,
        candidate.apiHubPackageId(),
        candidate.apiHubVersion(),
        groupName,
        documentSlug,
        imported.importId(),
        conversationId);
    return new ImportOutcome(result);
  }

  private static boolean matchesCandidate(
      ApiHubImportResult result, ApiHubImportCandidate candidate) {
    if (result.candidateId() != null
        && candidate.candidateId() != null
        && result.candidateId().equals(candidate.candidateId())) {
      return true;
    }
    return result.apiHubSpecificationName() != null
        && candidate.apiHubSpecificationName() != null
        && result.apiHubSpecificationName()
            .equalsIgnoreCase(candidate.apiHubSpecificationName().trim())
        && CatalogStrings.blankToNull(result.systemId()) != null;
  }

  private String findOrCreateSystem(String conversationId, ApiHubImportCandidate candidate) {
    String systemName = candidate.catalogSystemName().trim();
    String systemType = candidate.catalogSystemType().trim().toUpperCase(Locale.ROOT);

    Optional<String> existing = findSystemByName(conversationId, systemName);
    if (existing.isPresent()) {
      LOG.infof(
          "IMPORT_SPECIFICATION: reusing catalog system systemId=%s name=%s",
          existing.get(),
          systemName);
      return existing.get();
    }

    CatalogRestClient.SystemDto created =
        catalogRestClient.createSystem(new CatalogCreateSystemRequest(systemName, systemType));
    if (created == null || CatalogStrings.blankToNull(created.id()) == null) {
      throw new IllegalStateException("createSystem returned no systemId for name=" + systemName);
    }
    LOG.infof(
        "IMPORT_SPECIFICATION: createSystem systemId=%s name=%s type=%s",
        created.id(),
        systemName,
        systemType);
    if (conversationId != null && !conversationId.isBlank()) {
      catalogCache.rememberSystems(conversationId, List.of(created));
      catalogCache.rememberActiveSystemId(conversationId, created.id());
      planningDiaryService.recordCatalogSystemsFound(conversationId, systemName, List.of(created));
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
          planningDiaryService.recordCatalogSystemsFound(conversationId, systemName, List.of(system));
        }
        return Optional.of(system.id());
      }
    }
    return Optional.empty();
  }

  /**
   * INTERNAL and IMPLEMENTED systems require a default environment before OpenAPI import (same as
   * UI {@code ServicesList.handleCreate}).
   */
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
    LOG.infof(
        "IMPORT_SPECIFICATION: createEnvironment systemId=%s name=%s address=/",
        systemId,
        envName);
  }
}
