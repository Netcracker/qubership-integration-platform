package org.qubership.integration.platform.ai.compiler.artifact;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory artifact storage for focused tests and standalone store construction. */
public final class InMemoryArtifactBlobStore implements ArtifactBlobStore {

  private final Map<String, VersionedEntry> documents = new ConcurrentHashMap<>();

  @Override
  public void put(String key, byte[] content) {
    synchronized (documents) {
      VersionedEntry existing = documents.get(key);
      long next = existing == null ? 1L : existing.version + 1L;
      documents.put(key, new VersionedEntry(content.clone(), next));
    }
  }

  @Override
  public Optional<byte[]> get(String key) {
    VersionedEntry entry = documents.get(key);
    return entry == null ? Optional.empty() : Optional.of(entry.content.clone());
  }

  @Override
  public List<String> list(String prefix) {
    return documents.keySet().stream().filter(key -> key.startsWith(prefix)).sorted().toList();
  }

  @Override
  public Optional<VersionedBlob> getVersioned(String key) {
    VersionedEntry entry = documents.get(key);
    if (entry == null) {
      return Optional.empty();
    }
    return Optional.of(new VersionedBlob(entry.content, Long.toString(entry.version)));
  }

  @Override
  public void putIfVersion(String key, byte[] content, String expectedVersion) {
    synchronized (documents) {
      VersionedEntry existing = documents.get(key);
      if (expectedVersion == null) {
        if (existing != null) {
          throw new StaleBlobVersionException("create-only write lost for key " + key);
        }
        documents.put(key, new VersionedEntry(content.clone(), 1L));
        return;
      }
      if (existing == null || !Long.toString(existing.version).equals(expectedVersion)) {
        throw new StaleBlobVersionException("stale version for key " + key);
      }
      documents.put(key, new VersionedEntry(content.clone(), existing.version + 1L));
    }
  }

  private static final class VersionedEntry {
    private final byte[] content;
    private final long version;

    private VersionedEntry(byte[] content, long version) {
      this.content = content;
      this.version = version;
    }
  }
}
