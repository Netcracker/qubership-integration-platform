package org.qubership.integration.platform.engine.configuration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.support.SpringFactoriesLoader;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropertyPrefixAliasEnvironmentPostProcessorTest {

    private final StandardEnvironment environment = new StandardEnvironment();
    private final Map<String, Object> osEnvironment = new HashMap<>();
    private final Map<String, Object> consul = new HashMap<>();
    private final Map<String, Object> applicationYml = new HashMap<>();

    // Mirrors the Spring Boot order: environment variables, then Consul, then application.yml.
    @BeforeEach
    void setUp() {
        MutablePropertySources sources = environment.getPropertySources();
        sources.addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, osEnvironment));
        sources.addLast(new MapPropertySource("consul", consul));
        sources.addLast(new MapPropertySource("applicationYml", applicationYml));
        ConfigurationPropertySources.attach(environment);
        new PropertyPrefixAliasEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());
    }

    @Test
    void legacyKeyResolvesUnderTheNewPrefix() {
        applicationYml.put("qip.opensearch.rollover.min_index_age", "2d");

        assertEquals("2d", environment.getProperty("cip.opensearch.rollover.min_index_age"));
    }

    @Test
    void newKeyWinsWithinOneSource() {
        consul.put("qip.opensearch.rollover.min_index_age", "legacy");
        consul.put("cip.opensearch.rollover.min_index_age", "new");

        assertEquals("new", environment.getProperty("cip.opensearch.rollover.min_index_age"));
    }

    @Test
    void legacyKeyInConsulBeatsNewDefaultInApplicationYml() {
        consul.put("qip.opensearch.rollover.min_index_age", "consul");
        applicationYml.put("cip.opensearch.rollover.min_index_age", "default");

        assertEquals("consul", environment.getProperty("cip.opensearch.rollover.min_index_age"));
    }

    @Test
    void newKeyInConsulBeatsLegacyKeyInApplicationYml() {
        consul.put("cip.opensearch.rollover.min_index_age", "consul");
        applicationYml.put("qip.opensearch.rollover.min_index_age", "default");

        assertEquals("consul", environment.getProperty("cip.opensearch.rollover.min_index_age"));
    }

    @Test
    void legacyEnvironmentVariableResolvesUnderTheNewPrefix() {
        osEnvironment.put("QIP_ISTIO_ENABLED", "false");
        applicationYml.put("cip.istio.enabled", "true");

        assertEquals("false", environment.getProperty("cip.istio.enabled"));
    }

    @Test
    void dashlessEnvironmentVariableOverridesApplicationYml() {
        osEnvironment.put("CIP_INTERNALSERVICES_RUNTIMECATALOG_URL", "http://env-catalog:8080");
        applicationYml.put("cip.internal-services.runtime-catalog.url", "http://runtime-catalog:8080");

        assertEquals("http://env-catalog:8080", Binder.get(environment)
                .bind("cip.internal-services.runtime-catalog.url", String.class)
                .get());
    }

    @Test
    void dashlessLegacyEnvironmentVariableOverridesApplicationYml() {
        osEnvironment.put("QIP_INTERNALSERVICES_RUNTIMECATALOG_URL", "http://env-catalog:8080");
        applicationYml.put("cip.internal-services.runtime-catalog.url", "http://runtime-catalog:8080");

        assertEquals("http://env-catalog:8080", Binder.get(environment)
                .bind("cip.internal-services.runtime-catalog.url", String.class)
                .get());
    }

    @Test
    void placeholderToNewKeyResolvesFromLegacyValue() {
        consul.put("qip.camel.routes.prefix", "/routes");
        applicationYml.put("cip.chains.external-routes.base-path", "${cip.camel.routes.prefix}/external");

        assertEquals("/routes/external", environment.getProperty("cip.chains.external-routes.base-path"));
    }

    @Test
    void mapBindsEntriesFromBothPrefixes() {
        consul.put("cip.cr.build.environment.NEW_VAR", "new");
        applicationYml.put("qip.cr.build.environment.OLD_VAR", "old");

        Map<String, String> bound = Binder.get(environment)
                .bind("cip.cr.build.environment", Bindable.mapOf(String.class, String.class))
                .get();

        assertEquals(Map.of("NEW_VAR", "new", "OLD_VAR", "old"), bound);
    }

    @Test
    void onlyNamesUnderTheNewPrefixAreAliased() {
        applicationYml.put("qip.opensearch.rollover.min_index_age", "2d");

        assertNull(environment.getPropertySources()
                .get(PropertyPrefixAliasEnvironmentPostProcessor.SOURCE_NAME)
                .getProperty("qip.opensearch.rollover.min_index_age"));
        assertEquals("2d", environment.getProperty("qip.opensearch.rollover.min_index_age"));
    }

    @Test
    @SuppressWarnings("deprecation")
    void isRegisteredAsEnvironmentPostProcessor() {
        assertTrue(SpringFactoriesLoader.loadFactoryNames(EnvironmentPostProcessor.class, getClass().getClassLoader())
                .contains(PropertyPrefixAliasEnvironmentPostProcessor.class.getName()));
    }
}
