package org.qubership.integration.platform.ai.qipknowledge.pack;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/** CDI wiring for classpath QIP knowledge pack access. */
@ApplicationScoped
public class CipKnowledgePackProducer {
  private static final CipKnowledgePackVersion ACTIVE_VERSION =
      new CipKnowledgePackVersion(
          "integration-platform-skills",
          "integration-platform-skills");

  @Produces
  @ApplicationScoped
  public CipKnowledgePackRepository qipKnowledgePackRepository() {
    return new ClasspathCipKnowledgePackRepository(ACTIVE_VERSION);
  }
}
