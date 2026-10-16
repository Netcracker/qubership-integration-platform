package org.qubership.integration.platform.ai.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class AppConfigTest {

  @Test
  void resolvesProductionStorageSettingsFromDeploymentEnvironment() throws Exception {
    Properties properties = new Properties();
    try (var input = java.nio.file.Files.newInputStream(java.nio.file.Path.of("src/main/resources/application.properties"))) {
      properties.load(input);
    }
    var config = new SmallRyeConfigBuilder().addDefaultInterceptors().withProfile("prod")
        .withSources(new PropertiesConfigSource(properties, "production-properties"),
            new PropertiesConfigSource(Map.of(
                "MINIO_ENDPOINT", "https://storage.example.com",
                "MINIO_ACCESS_KEY", "test-access",
                "MINIO_SECRET_KEY", "test-secret"), "deployment-environment"))
        .build();

    assertEquals("https://storage.example.com", config.getValue("quarkus.s3.endpoint-override", String.class));
    assertEquals("test-access", config.getValue("quarkus.s3.aws.credentials.static-provider.access-key-id", String.class));
    assertEquals("test-secret", config.getValue("quarkus.s3.aws.credentials.static-provider.secret-access-key", String.class));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = "catalog-token")
  void mapsOptionalCatalogToken(String token) {
    Map<String, String> properties = new HashMap<>(Map.of(
        "qip.ai.llm.base-url", "http://localhost:9999",
        "qip.ai.llm.model-name", "test-model",
        "qip.ai.a2a.public-base-url", "http://localhost:8080"));
    if (token != null) {
      properties.put("qip.ai.catalog.token", token);
    }

    SmallRyeConfig config = new SmallRyeConfigBuilder()
        .withSources(new PropertiesConfigSource(properties, "catalog-token-test"))
        .withMapping(AppConfig.class)
        .build();

    assertEquals(Optional.ofNullable(token).filter(value -> !value.isEmpty()),
        config.getConfigMapping(AppConfig.class).catalog().token());
  }
}
