package org.qubership.integration.platform.ai.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;

class S3ServiceTest {

  @Test
  void listsObjectKeysAcrossResultPages() {
    AppConfig config = mock(AppConfig.class);
    AppConfig.StorageConfig storage = mock(AppConfig.StorageConfig.class);
    S3Client client = mock(S3Client.class);
    when(config.storage()).thenReturn(storage);
    when(storage.bucketName()).thenReturn("artifact-bucket");
    when(client.listObjectsV2(
            any(software.amazon.awssdk.services.s3.model.ListObjectsV2Request.class)))
        .thenReturn(
            page("next", "compiler-artifacts/a.json"),
            page(null, "compiler-artifacts/b.json"));

    S3Service service = new S3Service(config, client);

    assertEquals(
        List.of("compiler-artifacts/a.json", "compiler-artifacts/b.json"),
        service.listObjectKeys("compiler-artifacts/"));
  }

  @Test
  void putDesignIdsMarkdownUsesStableIdsFilename() {
    AppConfig config = mock(AppConfig.class);
    AppConfig.StorageConfig storage = mock(AppConfig.StorageConfig.class);
    S3Client client = mock(S3Client.class);
    when(config.storage()).thenReturn(storage);
    when(storage.bucketName()).thenReturn("artifact-bucket");

    S3Service service = new S3Service(config, client);
    String key = service.putDesignIdsMarkdown("# IDS\n");

    assertTrue(key.startsWith("ids-designs/"));
    assertTrue(key.endsWith("/ids.md"));
  }

  private static ListObjectsV2Response page(String nextToken, String key) {
    return ListObjectsV2Response.builder()
        .nextContinuationToken(nextToken)
        .contents(
            software.amazon.awssdk.services.s3.model.S3Object.builder().key(key).build())
        .build();
  }
}
