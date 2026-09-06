package org.qubership.integration.platform.ai.integration.catalog.materialize;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.qubership.integration.platform.ai.chat.attachment.UploadedSpecAttachment;
import org.qubership.integration.platform.ai.chat.attachment.UploadedSpecTitleExtractor;
import org.qubership.integration.platform.ai.integration.catalog.cache.ConversationCatalogCache;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateEnvironmentRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateSystemRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogSystemSearchRequest;
import org.qubership.integration.platform.ai.storage.S3Service;

/**
 * Imports a single uploaded API specification into the runtime catalog. The import is idempotent by
 * system/specification-group name: a matching existing specification is reused instead of creating a
 * duplicate.
 */
@ApplicationScoped
public class UploadedSpecAutoImporter {

  private static final String DEFAULT_ENVIRONMENT = "default";
  private static final String SYSTEM_TYPE_INTERNAL = "INTERNAL";

  private final S3Service s3Service;
  private final CatalogRestClient catalogRestClient;
  private final CatalogSpecificationImporter catalogSpecificationImporter;
  private final ConversationCatalogCache conversationCatalogCache;

  @Inject
  public UploadedSpecAutoImporter(
      S3Service s3Service,
      @RestClient CatalogRestClient catalogRestClient,
      CatalogSpecificationImporter catalogSpecificationImporter,
      ConversationCatalogCache conversationCatalogCache) {
    this.s3Service = s3Service;
    this.catalogRestClient = catalogRestClient;
    this.catalogSpecificationImporter = catalogSpecificationImporter;
    this.conversationCatalogCache = conversationCatalogCache;
  }

  public UploadedSpecImportOutcome importSpec(
      String conversationId, UploadedSpecAttachment attachment) {
    return importSpec(conversationId, attachment, SYSTEM_TYPE_INTERNAL);
  }

  public UploadedSpecImportOutcome importSpec(
      String conversationId, UploadedSpecAttachment attachment, String systemType) {
    byte[] content = s3Service.readObjectBytes(attachment.s3Key());
    String specName = UploadedSpecTitleExtractor.resolveSpecName(content, attachment.filename());

    CatalogRestClient.SystemDto system = findOrCreateSystem(specName, systemType);
    String catalogSystemType =
        system.type() == null || system.type().isBlank()
            ? SYSTEM_TYPE_INTERNAL
            : system.type();
    ensureDefaultEnvironment(system.id(), catalogSystemType);

    Optional<CatalogRestClient.SpecificationGroupDto> existingGroup =
        findReusableSpecificationGroup(system.id(), specName);

    String specificationId;
    String specificationGroupId;
    boolean reused;
    if (existingGroup.isPresent()) {
      specificationGroupId = existingGroup.get().id();
      Optional<CatalogRestClient.SpecificationDto> existingSpec =
          findExistingSpecificationInGroup(system.id(), specificationGroupId);
      if (existingSpec.isPresent()) {
        specificationId = existingSpec.get().id();
        reused = true;
      } else {
        CatalogSpecificationImporter.ImportOutcome outcome =
            catalogSpecificationImporter.importOpenApiDocumentIntoGroup(
                system.id(), specificationGroupId, content, attachment.filename());
        specificationId = outcome.specificationId();
        reused = false;
      }
    } else {
      CatalogSpecificationImporter.ImportOutcome outcome =
          catalogSpecificationImporter.importOpenApiDocument(
              system.id(), specName, null, content, attachment.filename());
      specificationId = outcome.specificationId();
      specificationGroupId = outcome.specificationGroupId();
      reused = false;
    }

    conversationCatalogCache.rememberSystems(conversationId, List.of(system));
    conversationCatalogCache.rememberActiveSystemId(conversationId, system.id());
    conversationCatalogCache.rememberSpecificationsForSystem(
        conversationId,
        system.id(),
        List.of(
            new CatalogRestClient.SpecificationDto(
                specificationId, specName, specificationGroupId, system.id())));

    return new UploadedSpecImportOutcome(
        attachment.s3Key(), system.id(), specificationGroupId, specificationId, reused);
  }

  private CatalogRestClient.SystemDto findOrCreateSystem(String baseName, String systemType) {
    List<CatalogRestClient.SystemDto> systems =
        catalogRestClient.searchSystems(new CatalogSystemSearchRequest(baseName));
    if (systems != null) {
      for (CatalogRestClient.SystemDto system : systems) {
        if (system.name() != null && system.name().equalsIgnoreCase(baseName)) {
          return system;
        }
      }
    }
    String resolvedType =
        systemType == null || systemType.isBlank() ? SYSTEM_TYPE_INTERNAL : systemType.trim();
    return catalogRestClient.createSystem(new CatalogCreateSystemRequest(baseName, resolvedType));
  }

  private void ensureDefaultEnvironment(String systemId, String systemType) {
    String normalizedType =
        systemType == null ? "" : systemType.trim().toUpperCase(java.util.Locale.ROOT);
    if (!SYSTEM_TYPE_INTERNAL.equals(normalizedType)) {
      return;
    }
    List<CatalogRestClient.EnvironmentDto> environments =
        catalogRestClient.getEnvironments(systemId);
    if (environments == null || environments.isEmpty()) {
      catalogRestClient.createEnvironment(
          systemId, new CatalogCreateEnvironmentRequest(DEFAULT_ENVIRONMENT, ""));
    }
  }

  /**
   * Reuse the existing group on a catalog system when the OpenAPI title matches the group name, or
   * when the group was named from a longer filename that still starts with that title.
   */
  private Optional<CatalogRestClient.SpecificationGroupDto> findReusableSpecificationGroup(
      String systemId, String specName) {
    List<CatalogRestClient.SpecificationGroupDto> groups =
        catalogRestClient.getSpecificationGroups(systemId);
    if (groups == null || specName == null || specName.isBlank()) {
      return Optional.empty();
    }
    Optional<CatalogRestClient.SpecificationGroupDto> exact =
        groups.stream()
            .filter(
                g ->
                    g != null
                        && g.id() != null
                        && !g.id().isBlank()
                        && g.name() != null
                        && g.name().equalsIgnoreCase(specName))
            .findFirst();
    if (exact.isPresent()) {
      return exact;
    }
    String needle = specName.toLowerCase(Locale.ROOT);
    List<CatalogRestClient.SpecificationGroupDto> prefixed =
        groups.stream()
            .filter(
                g ->
                    g != null
                        && g.id() != null
                        && !g.id().isBlank()
                        && g.name() != null)
            .filter(
                g -> {
                  String name = g.name().toLowerCase(Locale.ROOT);
                  return name.startsWith(needle + " ");
                })
            .toList();
    if (prefixed.size() == 1) {
      return Optional.of(prefixed.getFirst());
    }
    return Optional.empty();
  }

  private Optional<CatalogRestClient.SpecificationDto> findExistingSpecificationInGroup(
      String systemId, String specificationGroupId) {
    List<CatalogRestClient.SpecificationDto> specs =
        catalogRestClient.getApiSpecifications(systemId);
    if (specs == null) {
      return Optional.empty();
    }
    return specs.stream()
        .filter(
            s ->
                s != null
                    && s.id() != null
                    && !s.id().isBlank()
                    && specificationGroupId.equals(s.specificationGroupId()))
        .findFirst();
  }

}
