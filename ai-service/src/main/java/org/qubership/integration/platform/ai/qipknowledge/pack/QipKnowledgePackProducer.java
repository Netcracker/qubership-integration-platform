package org.qubership.integration.platform.ai.qipknowledge.pack;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** CDI wiring for classpath QIP knowledge pack access. */
@ApplicationScoped
public class QipKnowledgePackProducer {

  @ConfigProperty(name = "qip.ai.qipknowledge.pack-id", defaultValue = "integration-platform-skills")
  String packId;

  @Produces
  @ApplicationScoped
  public QipKnowledgePackRepository qipKnowledgePackRepository() {
    QipKnowledgePackVersion activeVersion = new QipKnowledgePackVersion(packId, packId);
    return new ClasspathQipKnowledgePackRepository(activeVersion);
  }
}
