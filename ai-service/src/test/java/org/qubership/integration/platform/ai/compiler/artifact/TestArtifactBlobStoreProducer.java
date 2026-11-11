package org.qubership.integration.platform.ai.compiler.artifact;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Set;

/** Keeps application integration tests independent of an external S3 endpoint. */
@ApplicationScoped
@Alternative
public class TestArtifactBlobStoreProducer {

  @Produces
  @ApplicationScoped
  public ArtifactBlobStore artifactBlobStore() {
    return new InMemoryArtifactBlobStore();
  }
  public static class Profile implements QuarkusTestProfile {
    @Override
    public Set<Class<?>> getEnabledAlternatives() {
      return Set.of(TestArtifactBlobStoreProducer.class);
    }
  }
}
