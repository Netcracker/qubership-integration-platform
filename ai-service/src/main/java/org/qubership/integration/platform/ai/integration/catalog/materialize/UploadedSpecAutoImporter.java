package org.qubership.integration.platform.ai.integration.catalog.materialize;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
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
    byte[] content = s3Service.readObjectBytes(attachment.s3Key());
    String specName = UploadedSpecTitleExtractor.resolveSpecName(content, attachment.filename());

    CatalogRestClient.SystemDto system = findOrCreateSystem(specName);
    ensureDefaultEnvironment(system.id());

    Optional<CatalogRestClient.SpecificationGroupDto> existingGroup =
        findExistingSpecificationGroup(system.id(), specName);

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

  private CatalogRestClient.SystemDto findOrCreateSystem(String baseName) {
    List<CatalogRestClient.SystemDto> systems =
        catalogRestClient.searchSystems(new CatalogSystemSearchRequest(baseName));
    if (systems != null) {
      for (CatalogRestClient.SystemDto system : systems) {
        if (system.name() != null && system.name().equalsIgnoreCase(baseName)) {
          return system;
        }
      }
    }
    return catalogRestClient.createSystem(
        new CatalogCreateSystemRequest(baseName, SYSTEM_TYPE_INTERNAL));
  }

  private void ensureDefaultEnvironment(String systemId) {
    List<CatalogRestClient.EnvironmentDto> environments =
        catalogRestClient.getEnvironments(systemId);
    if (environments == null || environments.isEmpty()) {
      catalogRestClient.createEnvironment(
          systemId, new CatalogCreateEnvironmentRequest(DEFAULT_ENVIRONMENT, ""));
    }
  }

  private Optional<CatalogRestClient.SpecificationGroupDto> findExistingSpecificationGroup(
      String systemId, String groupName) {
    List<CatalogRestClient.SpecificationGroupDto> groups =
        catalogRestClient.getSpecificationGroups(systemId);
    if (groups == null) {
      return Optional.empty();
    }
    return groups.stream()
        .filter(
            g ->
                g != null
                    && g.id() != null
                    && !g.id().isBlank()
                    && g.name() != null
                    && g.name().equalsIgnoreCase(groupName))
        .findFirst();
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
