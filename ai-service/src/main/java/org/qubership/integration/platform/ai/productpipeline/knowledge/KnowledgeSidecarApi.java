package org.qubership.integration.platform.ai.productpipeline.knowledge;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/** MicroProfile REST client for the colocated knowledge sidecar. */
@RegisterRestClient(configKey = "knowledge-sidecar")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface KnowledgeSidecarApi {

  @GET
  @Path("/v1/health/ready")
  HealthDto ready();

  @GET
  @Path("/v1/package")
  PackageResponseDto activePackage();

  @POST
  @Path("/v1/query/exact")
  ExactResponseDto exact(ExactRequestDto body);

  @POST
  @Path("/v1/query/filter")
  SearchResponseDto filter(FilterRequestDto body);

  @POST
  @Path("/v1/query/relations")
  RelationsResponseDto relations(RelationsRequestDto body);

  @POST
  @Path("/v1/query/context")
  ContextResponseDto context(ContextRequestDto body);

  record HealthDto(String status, String detail) {}

  record PackageRefDto(
      String packageKey,
      String knowledgeVersion,
      String schemaVersion,
      String packageChecksum,
      String certificationStatus,
      String certificationDigest) {}

  record PackageResponseDto(PackageRefDto packageRef) {}

  record ExactRequestDto(String expectedPackageChecksum, String id) {}

  /**
   * Sidecar JSON uses {@code "object"}; the Java accessor is renamed so frameworks that treat
   * {@code object} as a reserved/special property still bind the payload.
   */
  record ExactResponseDto(
      PackageRefDto packageRef, @JsonProperty("object") CanonicalKnowledgeObject knowledgeObject) {}

  record FilterRequestDto(String expectedPackageChecksum, String type, int limit) {}

  record SearchResponseDto(PackageRefDto packageRef, List<CanonicalKnowledgeObject> objects) {}

  record RelationsRequestDto(String expectedPackageChecksum, String id, List<String> kinds) {}

  record RelationsResponseDto(
      PackageRefDto packageRef, List<CanonicalKnowledgeObject.Relation> relations) {}

  record ContextRequestDto(
      String expectedPackageChecksum,
      String requestText,
      String capabilityId,
      String phase,
      List<String> elementTypes,
      int maxObjects,
      int maxChars) {}

  record ContextResponseDto(
      PackageRefDto packageRef,
      List<String> capabilities,
      List<CanonicalKnowledgeObject> objects,
      int contentChars) {}

  record ErrorDto(String code, String message, boolean retryable) {}
}
