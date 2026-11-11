package org.qubership.integration.platform.ai.compiler.artifact;

/** Content plus opaque storage version (in-memory counter or S3 ETag). */
public record VersionedBlob(byte[] content, String version) {

  public VersionedBlob {
    content = content == null ? new byte[0] : content.clone();
  }

  @Override
  public byte[] content() {
    return content.clone();
  }
}
