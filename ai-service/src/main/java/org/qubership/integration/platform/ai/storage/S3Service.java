package org.qubership.integration.platform.ai.storage;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class S3Service {

  private final AppConfig config;
  private final S3Client s3;

  @Inject
  public S3Service(AppConfig config, S3Client s3) {
    this.config = config;
    this.s3 = s3;
  }

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

  /** Stores an arbitrary object and returns its key. */
  public String putObject(String key, InputStream body, long size, String contentType) {
    PutObjectRequest put =
        PutObjectRequest.builder()
            .bucket(config.storage().bucketName())
            .key(key)
            .contentType(contentType)
            .contentLength(size)
            .build();
    s3.putObject(put, RequestBody.fromInputStream(body, size));
    return key;
  }

  /** Reads object bytes together with the S3 ETag used for conditional updates. */
  public ConditionallyVersionedObject getObjectBytesVersioned(String key) {
    var resp =
        s3.getObject(
            GetObjectRequest.builder().bucket(config.storage().bucketName()).key(key).build());
    try (var stream = resp) {
      byte[] content = stream.readAllBytes();
      String etag = resp.response().eTag();
      return new ConditionallyVersionedObject(content, etag);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Conditionally stores an object. Pass {@code expectedEtag == null} for create-only ({@code
   * If-None-Match: *}); otherwise use the ETag from {@link #getObjectBytesVersioned(String)} as
   * {@code If-Match}.
   */
  public void putObjectIfVersion(
      String key, InputStream body, long size, String contentType, String expectedEtag) {
    PutObjectRequest.Builder builder =
        PutObjectRequest.builder()
            .bucket(config.storage().bucketName())
            .key(key)
            .contentType(contentType)
            .contentLength(size);
    if (expectedEtag == null) {
      builder.ifNoneMatch("*");
    } else {
      builder.ifMatch(expectedEtag);
    }
    s3.putObject(builder.build(), RequestBody.fromInputStream(body, size));
  }

  /** Object payload plus opaque S3 ETag. */
  public record ConditionallyVersionedObject(byte[] content, String etag) {

    public ConditionallyVersionedObject {
      content = content == null ? new byte[0] : content.clone();
    }

    @Override
    public byte[] content() {
      return content.clone();
    }
  }

  /** Lists object keys under a prefix across all S3 result pages. */
  public List<String> listObjectKeys(String prefix) {
    List<String> keys = new ArrayList<>();
    String continuationToken = null;
    do {
      var response =
          s3.listObjectsV2(
              ListObjectsV2Request.builder()
                  .bucket(config.storage().bucketName())
                  .prefix(prefix)
                  .continuationToken(continuationToken)
                  .build());
      response.contents().forEach(object -> keys.add(object.key()));
      continuationToken = response.nextContinuationToken();
    } while (continuationToken != null);
    return List.copyOf(keys);
  }

  /**
   * Stores generated IDS markdown under {@code ids-designs/} and returns the object key for use
   * with {@link S3Controller} download. Filename in the key is {@code ids.md} so
   * Content-Disposition uses a stable product name.
   */
  public String putDesignIdsMarkdown(String markdownBody) {
    String key = "ids-designs/" + UUID.randomUUID() + "/ids.md";
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
