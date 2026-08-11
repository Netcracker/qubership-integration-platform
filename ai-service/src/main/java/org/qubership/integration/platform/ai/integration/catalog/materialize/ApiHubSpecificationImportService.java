package org.qubership.integration.platform.ai.integration.catalog.materialize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubDocumentPayload;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubMcpTools;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubRequirementRefs;
import org.qubership.integration.platform.ai.integration.catalog.cache.ConversationCatalogCache;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateEnvironmentRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateSystemRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogSystemSearchRequest;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;

import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Imports a full API Hub source document into runtime-catalog (create/reuse system, multipart
 * upload, poll). Used by IMPORT_SPECIFICATION scenario only.
 */
@ApplicationScoped
public class ApiHubSpecificationImportService {

  private static final Logger LOG = Logger.getLogger(ApiHubSpecificationImportService.class);

  private final CatalogRestClient catalogRestClient;
  private final ConversationCatalogCache catalogCache;
  private final CatalogSpecificationImporter catalogSpecificationImporter;
  private final ApiHubMcpTools apiHubMcpTools;
  private final ObjectMapper objectMapper;

  @Inject
  public ApiHubSpecificationImportService(
      @RestClient CatalogRestClient catalogRestClient,
      ConversationCatalogCache catalogCache,
      CatalogSpecificationImporter catalogSpecificationImporter,
      ApiHubMcpTools apiHubMcpTools,
      ObjectMapper objectMapper) {
    this.catalogRestClient = catalogRestClient;
    this.catalogCache = catalogCache;
    this.catalogSpecificationImporter = catalogSpecificationImporter;
    this.apiHubMcpTools = apiHubMcpTools;
    this.objectMapper = objectMapper;
  }

  public ApiHubSpecificationImportResult importFromRefs(
      String conversationId, ApiHubRequirementRefs refs) {
    if (refs == null || !refs.hasImportableRefs()) {
      throw new IllegalArgumentException(
          "API Hub refs are incomplete: packageId, version, and operationId or documentId are"
              + " required");
    }

    String systemName = refs.catalogSystemName();
    String systemType = ApiHubRequirementRefs.DEFAULT_SYSTEM_TYPE;
    String groupName = refs.specificationGroupName();

    String systemId = findOrCreateSystem(conversationId, systemName, systemType);
    ensureDefaultEnvironment(systemId, systemType, systemName);

    Optional<ApiHubSpecificationImportResult> reused =
        reuseExistingCatalogSpecification(conversationId, systemId, groupName, refs);
    if (reused.isPresent()) {
      return reused.get();
    }

    ApiHubDocumentPayload document =
        apiHubMcpTools.fetchApiHubDocument(
            refs.packageId(),
            refs.version(),
            refs.documentSlug(),
            refs.resolvedApiType());

    CatalogSpecificationImporter.ImportOutcome imported =
        catalogSpecificationImporter.importOpenApiDocument(
            systemId, groupName, null, document.content(), document.fileName());

    return finalizeImport(conversationId, refs, systemId, groupName, imported);
  }

  private Optional<ApiHubSpecificationImportResult> reuseExistingCatalogSpecification(
      String conversationId,
      String systemId,
      String groupName,
      ApiHubRequirementRefs refs) {
    List<CatalogRestClient.SpecificationDto> specs =
        catalogRestClient.getApiSpecifications(systemId);
    if (specs == null || specs.isEmpty()) {
      return Optional.empty();
    }
    String trimmedGroup = groupName.trim();
    for (CatalogRestClient.SpecificationDto spec : specs) {
      if (spec == null || CatalogStrings.blankToNull(spec.id()) == null) {
        continue;
      }
      if (!belongsToSpecificationGroup(spec, systemId, trimmedGroup)) {
        continue;
      }
      LOG.infof(
          "IMPORT_SPECIFICATION: reusing existing catalog specification specId=%s systemId=%s"
              + " specificationGroup=%s specName=%s conversationId=%s",
          spec.id(),
          systemId,
          trimmedGroup,
          spec.name(),
          conversationId);
      return Optional.of(
          populateCacheAndResolveOperation(
              conversationId,
              refs,
              systemId,
              groupName,
              spec.id(),
              spec.specificationGroupId(),
              null));
    }
    return Optional.empty();
  }

  /**
   * {@code groupName} is the specification group label passed to catalog import ({@code name}
   * query param). {@link CatalogRestClient.SpecificationDto#name()} is the imported model name
   * (for example {@code v4.4}), not the group name.
   */
  public static boolean belongsToSpecificationGroup(
      CatalogRestClient.SpecificationDto spec, String systemId, String groupName) {
    String groupId = CatalogStrings.blankToNull(spec.specificationGroupId());
    if (groupId == null) {
      return false;
    }
    String sid = CatalogStrings.blankToNull(systemId);
    if (sid != null) {
      String expectedGroupId = sid + "-" + groupName;
      if (groupId.equals(expectedGroupId)) {
        return true;
      }
    }
    return groupId.endsWith("-" + groupName);
  }

  private ApiHubSpecificationImportResult finalizeImport(
      String conversationId,
      ApiHubRequirementRefs refs,
      String systemId,
      String groupName,
      CatalogSpecificationImporter.ImportOutcome imported) {
    ApiHubSpecificationImportResult result =
        populateCacheAndResolveOperation(
            conversationId,
            refs,
            systemId,
            groupName,
            imported.specificationId(),
            imported.specificationGroupId(),
            imported.importId());

    LOG.infof(
        "IMPORT_SPECIFICATION: imported specId=%s systemId=%s packageId=%s version=%s"
            + " specName=%s documentSlug=%s importId=%s conversationId=%s",
        imported.specificationId(),
        systemId,
        refs.packageId(),
        refs.version(),
        groupName,
        refs.documentSlug(),
        imported.importId(),
        conversationId);

    return result;
  }

  private ApiHubSpecificationImportResult populateCacheAndResolveOperation(
      String conversationId,
      ApiHubRequirementRefs refs,
      String systemId,
      String groupName,
      String specificationId,
      String specificationGroupId,
      String importId) {
    if (conversationId != null && !conversationId.isBlank()) {
      catalogCache.rememberSystems(
          conversationId,
          List.of(
              new CatalogRestClient.SystemDto(
                  systemId, refs.catalogSystemName(), ApiHubRequirementRefs.DEFAULT_SYSTEM_TYPE, null)));
      catalogCache.rememberSpecifications(
          conversationId,
          List.of(
              new CatalogRestClient.SpecificationDto(
                  specificationId, groupName, specificationGroupId, systemId)));
      catalogCache.rememberActiveSystemId(conversationId, systemId);
    }

    Optional<String> catalogOperationId =
        resolveCatalogOperationId(conversationId, systemId, specificationId, refs);

    return new ApiHubSpecificationImportResult(
        systemId,
        specificationId,
        specificationGroupId,
        importId,
        groupName,
        catalogOperationId);
  }

  private Optional<String> resolveCatalogOperationId(
      String conversationId,
      String systemId,
      String specificationId,
      ApiHubRequirementRefs refs) {
    if (CatalogStrings.blankToNull(refs.operationId()) == null) {
      return Optional.empty();
    }
    if (conversationId == null || conversationId.isBlank()) {
      return Optional.empty();
    }
    Optional<PathMethod> target = loadApiHubOperationPathMethod(refs);
    if (target.isEmpty()) {
      return Optional.empty();
    }
    List<CatalogRestClient.OperationDto> operations =
        catalogCache.refreshOperations(conversationId, specificationId, systemId);
    PathMethod wanted = target.get();
    for (CatalogRestClient.OperationDto operation : operations) {
      if (operation == null || CatalogStrings.blankToNull(operation.id()) == null) {
        continue;
      }
      if (matchesPathMethod(operation, wanted)) {
        catalogCache.rememberOperation(conversationId, operation);
        return Optional.of(operation.id());
      }
    }
    return Optional.empty();
  }

  private Optional<PathMethod> loadApiHubOperationPathMethod(ApiHubRequirementRefs refs) {
    try {
      byte[] payload =
          apiHubMcpTools.fetchOperationOpenApiJson(
              refs.packageId(),
              refs.version(),
              refs.operationId(),
              refs.resolvedApiType());
      JsonNode root = objectMapper.readTree(payload);
      JsonNode paths = root.path("paths");
      if (!paths.isObject()) {
        return Optional.empty();
      }
      Iterator<String> pathNames = paths.fieldNames();
      while (pathNames.hasNext()) {
        String path = pathNames.next();
        JsonNode methods = paths.get(path);
        if (!methods.isObject()) {
          continue;
        }
        for (String methodName : List.of("get", "post", "put", "patch", "delete", "head", "options")) {
          if (methods.has(methodName)) {
            return Optional.of(new PathMethod(path, methodName.toUpperCase(Locale.ROOT)));
          }
        }
      }
      return Optional.empty();
    } catch (Exception e) {
      LOG.warnf(
          e,
          "IMPORT_SPECIFICATION: could not resolve API Hub operation path/method operationId=%s",
          refs.operationId());
      return Optional.empty();
    }
  }

  private static boolean matchesPathMethod(
      CatalogRestClient.OperationDto operation, PathMethod wanted) {
    String opMethod = CatalogStrings.blankToNull(operation.method());
    String opPath = CatalogStrings.blankToNull(operation.path());
    if (opMethod == null || opPath == null) {
      return false;
    }
    return opMethod.equalsIgnoreCase(wanted.method()) && opPath.equals(wanted.path());
  }

  private String findOrCreateSystem(String conversationId, String systemName, String systemType) {
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
    LOG.infof(
        "IMPORT_SPECIFICATION: createEnvironment systemId=%s name=%s address=/",
        systemId,
        envName);
  }

  private record PathMethod(String path, String method) {}
}
