package org.qubership.integration.platform.ai.flow.persistence;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Ticket 01: production Flow persistence uses the existing PostgreSQL datasource and Flyway, and
 * fails closed instead of falling back to in-memory execution.
 */
class FlowPersistenceConfigIT {

  @Test
  void productionConfigUsesExistingDatasourceFlywayAndFailClosedPersistence() throws IOException {
    String properties = Files.readString(applicationProperties(), StandardCharsets.UTF_8);

    assertTrue(properties.contains("quarkus.datasource.db-kind=postgresql"));
    assertTrue(properties.contains("quarkus.flyway.migrate-at-start=true"));
    assertTrue(properties.contains("quarkus.flyway.locations=classpath:db/migration"));
    assertTrue(properties.contains("%prod.quarkus.datasource.devservices.enabled=false"));
    assertTrue(
        properties.contains("quarkus.hibernate-orm.database.generation=none")
            || properties.contains("quarkus.hibernate-orm.schema-management.strategy=none"));
    assertTrue(
        properties.contains(
            "quarkus.hibernate-orm.physical-naming-strategy="
                + "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"));
    assertTrue(properties.contains("quarkus.flow.persistence.auto-restore=true"));
    assertFalse(properties.contains("quarkus.flow.persistence.auto-restore=false"));
    assertFalse(
        properties.contains("create-chain-provided-ids:qip:1.0.0"),
        "ticket 03 persists create-chain; do not exclude that workflow from Flow JPA");
    assertFalse(properties.contains("mp.messaging.incoming.flow-in.connector=smallrye-kafka"));
    assertFalse(properties.contains("kafka.bootstrap.servers"));
  }

  private static Path applicationProperties() {
    return Path.of("src/main/resources/application.properties");
  }
}
