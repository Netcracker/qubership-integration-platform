package org.qubership.integration.platform.ai.qipknowledge.pack;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** CDI wiring for classpath CIP knowledge pack access. */
@ApplicationScoped
public class CipKnowledgePackProducer {

  @ConfigProperty(name = "qip.ai.qipknowledge.pack-id", defaultValue = "integration-platform-skills")
  String packId;

  @Produces
  @ApplicationScoped
  public CipKnowledgePackRepository qipKnowledgePackRepository() {
    CipKnowledgePackVersion activeVersion = new CipKnowledgePackVersion(packId, packId);
    return new ClasspathCipKnowledgePackRepository(activeVersion);
  }
}
