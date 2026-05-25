package org.qubership.integration.platform.ai.storage;

import io.quarkus.arc.profile.UnlessBuildProfile;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

@UnlessBuildProfile("test")
@ApplicationScoped
public class StorageBucketInitializer {

  private static final Logger LOG = Logger.getLogger(StorageBucketInitializer.class);

  @Inject S3Client s3;

  @Inject AppConfig config;

  void onStart(@Observes StartupEvent ignored) {
    if (!config.storage().initializeBucketOnStartup()) {
      LOG.infof(
          "Skipping storage bucket init (qip.ai.storage.initialize-bucket-on-startup=false),"
              + " bucket=%s",
          config.storage().bucketName());
      return;
    }
    String bucket = config.storage().bucketName();
    try {
      try {
        s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        LOG.infof("Storage bucket exists: %s", bucket);
      } catch (S3Exception e) {
        if (!"NoSuchBucket".equals(e.awsErrorDetails().errorCode())) {
          throw e;
        }
        LOG.infof("Creating storage bucket: %s", bucket);
        s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
      }
    } catch (SdkClientException e) {
      LOG.warnf(
          e,
          "Storage endpoint unreachable at startup (bucket=%s). Service will start without bucket"
              + " verification; fix MINIO_ENDPOINT / credentials or set"
              + " qip.ai.storage.initialize-bucket-on-startup=false.",
          bucket);
    }
  }
}
