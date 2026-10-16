package org.qubership.integration.platform.ai.qipknowledge.pack;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/** CDI wiring for classpath QIP knowledge pack access. */
@ApplicationScoped
public class QipKnowledgePackProducer {
  private static final QipKnowledgePackVersion ACTIVE_VERSION =
      new QipKnowledgePackVersion(
          "integration-platform-skills",
          "integration-platform-skills");

  @Produces
  @ApplicationScoped
  public QipKnowledgePackRepository qipKnowledgePackRepository() {
    return new ClasspathQipKnowledgePackRepository(ACTIVE_VERSION);
  }
}
