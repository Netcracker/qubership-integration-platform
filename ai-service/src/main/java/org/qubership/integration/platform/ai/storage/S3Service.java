package org.qubership.integration.platform.ai.storage;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@ApplicationScoped
public class S3Service {

  @Inject AppConfig config;

  @Inject S3Client s3;

  public S3Object getObject(String key) {
    var resp =
        s3.getObject(
            GetObjectRequest.builder().bucket(config.storage().bucketName()).key(key).build());
    String filename = key.contains("/") ? key.substring(key.lastIndexOf('/') + 1) : key;
    String ct =
        resp.response().contentType() != null
            ? resp.response().contentType()
            : MediaType.APPLICATION_OCTET_STREAM;
    return new S3Object(resp, filename, ct);
  }

  /** Reads the whole object as UTF-8 text (for markdown and similar). */
  public String readObjectUtf8(String key) {
    S3Object obj = getObject(key);
    try (var stream = obj.resp()) {
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Stores generated IDS markdown under {@code ids-designs/} and returns the object key for use
   * with {@link S3Controller} download.
   */
  public String putDesignIdsMarkdown(String markdownBody) {
    String key = "ids-designs/" + UUID.randomUUID() + ".md";
    PutObjectRequest put =
        PutObjectRequest.builder()
            .bucket(config.storage().bucketName())
            .key(key)
            .contentType("text/markdown; charset=utf-8")
            .build();
    s3.putObject(put, RequestBody.fromString(markdownBody, StandardCharsets.UTF_8));
    return key;
  }
}
