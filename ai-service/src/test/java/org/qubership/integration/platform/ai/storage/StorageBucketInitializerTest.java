package org.qubership.integration.platform.ai.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.CreateBucketResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

class StorageBucketInitializerTest {

  @Test
  void skipsInitWhenDisabled() {
    S3Client s3 = mock(S3Client.class);
    StorageBucketInitializer initializer =
        new StorageBucketInitializer(s3, storageConfig(false, "qip-ai-storage"));

    initializer.onStart(null);

    verify(s3, never()).headBucket(any(HeadBucketRequest.class));
    verify(s3, never()).createBucket(any(CreateBucketRequest.class));
  }

  @Test
  void leavesExistingBucket() {
    S3Client s3 = mock(S3Client.class);
    when(s3.headBucket(any(HeadBucketRequest.class)))
        .thenReturn((HeadBucketResponse) HeadBucketResponse.builder().build());
    StorageBucketInitializer initializer =
        new StorageBucketInitializer(s3, storageConfig(true, "qip-ai-storage"));

    initializer.onStart(null);

    verify(s3).headBucket(any(HeadBucketRequest.class));
    verify(s3, never()).createBucket(any(CreateBucketRequest.class));
  }

  @Test
  void createsBucketWhenMissing() {
    S3Client s3 = mock(S3Client.class);
    when(s3.headBucket(any(HeadBucketRequest.class))).thenThrow(noSuchBucket());
    when(s3.createBucket(any(CreateBucketRequest.class)))
        .thenReturn((CreateBucketResponse) CreateBucketResponse.builder().build());
    StorageBucketInitializer initializer =
        new StorageBucketInitializer(s3, storageConfig(true, "qip-ai-storage"));

    initializer.onStart(null);

    verify(s3).headBucket(any(HeadBucketRequest.class));
    verify(s3).createBucket(any(CreateBucketRequest.class));
  }

  @Test
  void startsWhenStorageIsUnreachable() {
    S3Client s3 = mock(S3Client.class);
    when(s3.headBucket(any(HeadBucketRequest.class)))
        .thenThrow(SdkClientException.create("connection refused"));
    StorageBucketInitializer initializer =
        new StorageBucketInitializer(s3, storageConfig(true, "qip-ai-storage"));

    assertDoesNotThrow(() -> initializer.onStart(null));
    verify(s3, never()).createBucket(any(CreateBucketRequest.class));
  }

  private static AppConfig storageConfig(boolean initializeOnStartup, String bucketName) {
    AppConfig config = mock(AppConfig.class);
    AppConfig.StorageConfig storage = mock(AppConfig.StorageConfig.class);
    when(config.storage()).thenReturn(storage);
    when(storage.initializeBucketOnStartup()).thenReturn(initializeOnStartup);
    when(storage.bucketName()).thenReturn(bucketName);
    return config;
  }

  private static S3Exception noSuchBucket() {
    return (S3Exception)
        S3Exception.builder()
            .awsErrorDetails(AwsErrorDetails.builder().errorCode("NoSuchBucket").build())
            .message("The specified bucket does not exist")
            .build();
  }
}
