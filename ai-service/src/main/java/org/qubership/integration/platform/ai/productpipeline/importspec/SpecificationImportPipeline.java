package org.qubership.integration.platform.ai.productpipeline.importspec;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.qubership.integration.platform.ai.chat.attachment.AttachmentKeys;
import org.qubership.integration.platform.ai.chat.attachment.UploadedSpecAttachment;
import org.qubership.integration.platform.ai.compiler.artifact.ArtifactBlobStore;
import org.qubership.integration.platform.ai.integration.catalog.materialize.UploadedSpecImportOutcome;
import org.qubership.integration.platform.ai.integration.catalog.pipeline.CatalogMutationGateway;
import org.qubership.integration.platform.ai.plan.workdocument.source.AttachmentRoles;

/** Imports specifications independently of chain requirements and keeps each file's outcome. */
@ApplicationScoped
public class SpecificationImportPipeline {
  private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(SpecificationImportPipeline.class);
  public record Item(String s3Key, UploadedSpecImportOutcome imported, String error) {}

  public record Result(List<Item> items) {
    public boolean succeeded() {
      return !items.isEmpty() && items.stream().allMatch(item -> item.imported() != null);
    }

    public List<UploadedSpecImportOutcome> imported() {
      return items.stream().map(Item::imported).filter(java.util.Objects::nonNull).toList();
    }

    public String failures() {
      return items.stream().filter(item -> item.imported() == null)
          .map(item -> item.s3Key() + ": " + item.error()).collect(java.util.stream.Collectors.joining("\n"));
    }
  }

  public record StandaloneRequest(String attachmentHash, String userText, boolean completed) {}

  private final CatalogMutationGateway catalog;
  private final ArtifactBlobStore blobs;
  private final ObjectMapper json;

  @Inject
  public SpecificationImportPipeline(CatalogMutationGateway catalog, ArtifactBlobStore blobs, ObjectMapper json) {
    this.catalog = catalog;
    this.blobs = blobs;
    this.json = json;
  }

  public Uni<Result> execute(String conversationId, List<UploadedSpecAttachment> attachments,
      Map<String, String> systemTypes, String fallbackType) {
    return Multi.createFrom().iterable(attachments)
        .onItem().transformToUniAndConcatenate(attachment -> {
          String type = systemTypes.isEmpty() ? fallbackType : systemTypes.getOrDefault(attachment.s3Key(), "INTERNAL");
          return Uni.createFrom().deferred(() -> {
            if (!AttachmentKeys.isSafe(attachment.s3Key()) || !AttachmentRoles.importsAsSpecification(attachment.s3Key())) {
              return Uni.createFrom().failure(new IllegalArgumentException("The attachment is not an allowed API specification"));
            }
            return catalog.importUploadedSpec(conversationId, attachment, type);
          }).map(outcome -> new Item(attachment.s3Key(), outcome, null))
              .onFailure().invoke(error -> LOG.errorf(error,
                  "Specification import failed conversationId=%s s3Key=%s", conversationId, attachment.s3Key()))
              .onFailure().recoverWithItem(error -> new Item(attachment.s3Key(), null,
                  failureMessage(error)));
        }).collect().asList().map(Result::new)
        .invoke(result -> write(key(conversationId, "result"), result));
  }

  public void prepareStandalone(String conversationId, String attachmentHash, String userText) {
    write(key(conversationId, "request"), new StandaloneRequest(attachmentHash, userText, false));
  }

  private static String failureMessage(Throwable error) {
    String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    if (error instanceof org.qubership.integration.platform.ai.integration.catalog.client.CatalogNonRetryableResponseException catalogError
        && !catalogError.catalogErrorMessage().isBlank()) {
      return message + ": " + catalogError.catalogErrorMessage();
    }
    return message;
  }

  public void completeStandalone(String conversationId, String attachmentHash) {
    var request = standaloneRequest(conversationId).orElseThrow();
    write(key(conversationId, "request"), new StandaloneRequest(attachmentHash, request.userText(), true));
  }

  public Optional<StandaloneRequest> standaloneRequest(String conversationId) {
    return blobs.get(key(conversationId, "request")).map(bytes -> read(bytes, StandaloneRequest.class));
  }

  public Optional<Result> result(String conversationId) {
    return blobs.get(key(conversationId, "result")).map(bytes -> read(bytes, Result.class));
  }

  private static String key(String conversationId, String name) {
    return "specification-import/" + conversationId + "/" + name + ".json";
  }

  private void write(String key, Object value) {
    try {
      blobs.put(key, json.writeValueAsBytes(value));
    } catch (java.io.IOException error) {
      throw new IllegalStateException("Cannot store the specification import result", error);
    }
  }

  private <T> T read(byte[] bytes, Class<T> type) {
    try {
      return json.readValue(bytes, type);
    } catch (java.io.IOException error) {
      throw new IllegalStateException("Cannot read the specification import result", error);
    }
  }
}
