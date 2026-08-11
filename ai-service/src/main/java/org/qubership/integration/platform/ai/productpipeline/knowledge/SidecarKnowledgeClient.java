package org.qubership.integration.platform.ai.productpipeline.knowledge;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/** Knowledge client that talks only to the colocated knowledge-package sidecar. */
@ApplicationScoped
public class SidecarKnowledgeClient implements KnowledgeClient {

  private final KnowledgeSidecarApi api;

  @Inject
  public SidecarKnowledgeClient(@RestClient KnowledgeSidecarApi api) {
    this.api = Objects.requireNonNull(api, "api");
  }

  /** Test helper that bypasses CDI RestClient injection. */
  static SidecarKnowledgeClient forTests(KnowledgeSidecarApi api) {
    return new SidecarKnowledgeClient(api);
  }

  @Override
  public KnowledgeObjectResult exact(KnowledgeQueryContext context, String id) {
    requireContext(context);
    requireText(id, "id");
    try {
      KnowledgeSidecarApi.ExactResponseDto response =
          api.exact(
              new KnowledgeSidecarApi.ExactRequestDto(
                  context.packageRef().packageChecksum(), id));
      KnowledgeResponseIdentity identity = verifyIdentity(context, response.packageRef());
      return toObject(identity, response.knowledgeObject());
    } catch (RuntimeException e) {
      throw mapFailure(e);
    }
  }

  @Override
  public KnowledgeSearchResult filter(KnowledgeQueryContext context, KnowledgeFilter filter) {
    requireContext(context);
    Objects.requireNonNull(filter, "filter");
    try {
      KnowledgeSidecarApi.SearchResponseDto response =
          api.filter(
              new KnowledgeSidecarApi.FilterRequestDto(
                  context.packageRef().packageChecksum(), filter.type(), filter.limit()));
      KnowledgeResponseIdentity identity = verifyIdentity(context, response.packageRef());
      return toSearch(identity, response.objects());
    } catch (RuntimeException e) {
      throw mapFailure(e);
    }
  }

  @Override
  public KnowledgeRelationResult relations(
      KnowledgeQueryContext context, String id, Set<String> kinds) {
    requireContext(context);
    requireText(id, "id");
    List<String> kindList = kinds == null ? List.of() : List.copyOf(kinds);
    try {
      KnowledgeSidecarApi.RelationsResponseDto response =
          api.relations(
              new KnowledgeSidecarApi.RelationsRequestDto(
                  context.packageRef().packageChecksum(), id, kindList));
      KnowledgeResponseIdentity identity = verifyIdentity(context, response.packageRef());
      List<CanonicalKnowledgeObject.Relation> relations =
          response.relations() == null ? List.of() : List.copyOf(response.relations());
      return new KnowledgeRelationResult(identity, relations);
    } catch (RuntimeException e) {
      throw mapFailure(e);
    }
  }

  @Override
  public KnowledgeContextPackage context(
      KnowledgeQueryContext context, KnowledgeContextRequest request) {
    requireContext(context);
    Objects.requireNonNull(request, "request");
    try {
      KnowledgeSidecarApi.ContextResponseDto response =
          api.context(
              new KnowledgeSidecarApi.ContextRequestDto(
                  context.packageRef().packageChecksum(),
                  request.requestText(),
                  request.capabilityId(),
                  request.phase(),
                  request.elementTypes(),
                  request.maxObjects(),
                  request.maxChars()));
      KnowledgeResponseIdentity identity = verifyIdentity(context, response.packageRef());
      return new KnowledgeContextPackage(
          identity,
          response.capabilities(),
          response.objects(),
          response.contentChars());
    } catch (RuntimeException e) {
      throw mapFailure(e);
    }
  }

  static KnowledgeResponseIdentity verifyIdentity(
      KnowledgeQueryContext context, KnowledgeSidecarApi.PackageRefDto packageRef) {
    if (packageRef == null) {
      throw new KnowledgeClientException(
          KnowledgeFailureKind.KNOWLEDGE_INTEGRITY_FAILURE, "missing response packageRef");
    }
    KnowledgePackageRef mapped = toPackageRef(packageRef);
    if (!context.packageRef().equals(mapped)) {
      throw new KnowledgeClientException(
          KnowledgeFailureKind.KNOWLEDGE_PACKAGE_PIN_MISMATCH,
          "expectedPackageChecksum does not match the active package");
    }
    return new KnowledgeResponseIdentity(mapped);
  }

  static KnowledgePackageRef toPackageRef(KnowledgeSidecarApi.PackageRefDto dto) {
    Objects.requireNonNull(dto, "packageRef");
    return new KnowledgePackageRef(
        dto.packageKey(),
        dto.knowledgeVersion(),
        dto.schemaVersion(),
        dto.packageChecksum(),
        dto.certificationStatus(),
        dto.certificationDigest());
  }

  private static KnowledgeObjectResult toObject(
      KnowledgeResponseIdentity identity, CanonicalKnowledgeObject object) {
    if (object == null) {
      throw new KnowledgeClientException(
          KnowledgeFailureKind.KNOWLEDGE_NOT_FOUND, "object payload missing");
    }
    return new KnowledgeObjectResult(identity, object);
  }

  private static KnowledgeSearchResult toSearch(
      KnowledgeResponseIdentity identity, List<CanonicalKnowledgeObject> objects) {
    return new KnowledgeSearchResult(
        identity, objects == null ? List.of() : List.copyOf(objects));
  }

  private static void requireContext(KnowledgeQueryContext context) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(context.packageRef(), "packageRef");
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new KnowledgeClientException(
          KnowledgeFailureKind.KNOWLEDGE_INVALID_REQUEST, name + " is required");
    }
  }

  static KnowledgeClientException mapFailure(RuntimeException error) {
    if (error instanceof KnowledgeClientException existing) {
      return existing;
    }
    if (error instanceof WebApplicationException web) {
      Response response = web.getResponse();
      int status = response == null ? 0 : response.getStatus();
      KnowledgeSidecarApi.ErrorDto body = null;
      try {
        if (response != null && response.hasEntity()) {
          body = response.readEntity(KnowledgeSidecarApi.ErrorDto.class);
        }
      } catch (RuntimeException ignored) {
        // fall through to status mapping
      }
      if (body != null && body.code() != null) {
        KnowledgeFailureKind kind = KnowledgeFailureKind.fromCode(body.code());
        return new KnowledgeClientException(
            kind, body.message() == null ? body.code() : body.message(), error);
      }
      if (status == 409) {
        return new KnowledgeClientException(
            KnowledgeFailureKind.KNOWLEDGE_PACKAGE_PIN_MISMATCH,
            "expectedPackageChecksum does not match the active package",
            error);
      }
      if (status == 503) {
        return new KnowledgeClientException(
            KnowledgeFailureKind.KNOWLEDGE_TEMPORARILY_UNAVAILABLE,
            "sidecar temporarily unavailable",
            error);
      }
      if (status == 404) {
        return new KnowledgeClientException(
            KnowledgeFailureKind.KNOWLEDGE_NOT_FOUND, "knowledge resource not found", error);
      }
      if (status >= 400 && status < 500) {
        return new KnowledgeClientException(
            KnowledgeFailureKind.KNOWLEDGE_INVALID_REQUEST, "sidecar rejected request", error);
      }
    }
    String detail = error.getClass().getSimpleName();
    if (error.getMessage() != null && !error.getMessage().isBlank()) {
      detail = detail + ": " + error.getMessage();
    }
    return new KnowledgeClientException(
        KnowledgeFailureKind.KNOWLEDGE_TRANSPORT_FAILURE,
        "sidecar transport failure (" + detail + ")",
        error);
  }
}
