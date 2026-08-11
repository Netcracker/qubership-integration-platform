package org.qubership.integration.platform.ai.compiler.artifact;

import java.util.List;
import java.util.Optional;

/** Stores immutable compiler artifact documents without interpreting their contents. */
public interface ArtifactBlobStore {

  void put(String key, byte[] content);

  Optional<byte[]> get(String key);

  List<String> list(String prefix);

  /** Returns content and the opaque version token required for a later conditional write. */
  Optional<VersionedBlob> getVersioned(String key);

  /**
   * Conditionally writes {@code content}.
   *
   * <p>{@code expectedVersion == null} means create-only (fail if the key already exists). A
   * non-null value must match the version returned by {@link #getVersioned(String)}.
   */
  void putIfVersion(String key, byte[] content, String expectedVersion);
}
