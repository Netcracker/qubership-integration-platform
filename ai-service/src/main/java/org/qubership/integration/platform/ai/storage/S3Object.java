package org.qubership.integration.platform.ai.storage;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

/** Streaming S3 getObject result plus derived metadata for HTTP responses. */
public record S3Object(ResponseInputStream<GetObjectResponse> resp, String filename, String ct) {}
