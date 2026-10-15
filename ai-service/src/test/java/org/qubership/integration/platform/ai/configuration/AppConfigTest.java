package org.qubership.integration.platform.ai.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class AppConfigTest {

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
