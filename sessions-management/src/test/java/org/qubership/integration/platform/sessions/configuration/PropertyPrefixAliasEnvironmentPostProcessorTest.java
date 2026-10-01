package org.qubership.integration.platform.sessions.configuration;

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
        sources.addFirst(new SystemEnvironmentPropertySource("testEnvironment", osEnvironment));
        sources.addLast(new MapPropertySource("consul", consul));
        sources.addLast(new MapPropertySource("applicationYml", applicationYml));
        ConfigurationPropertySources.attach(environment);
        new PropertyPrefixAliasEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());
    }

    @Test
    void legacyKeyResolvesUnderTheNewPrefix() {
        applicationYml.put("qip.opensearch.index.prefix", "legacy-prefix");

        assertEquals("legacy-prefix", environment.getProperty("cip.opensearch.index.prefix"));
    }

    @Test
    void newKeyWinsWithinOneSource() {
        consul.put("qip.opensearch.index.prefix", "legacy");
        consul.put("cip.opensearch.index.prefix", "new");

        assertEquals("new", environment.getProperty("cip.opensearch.index.prefix"));
    }

    @Test
    void legacyKeyInConsulBeatsNewDefaultInApplicationYml() {
        consul.put("qip.opensearch.index.prefix", "consul");
        applicationYml.put("cip.opensearch.index.prefix", "default");

        assertEquals("consul", environment.getProperty("cip.opensearch.index.prefix"));
    }

    @Test
    void newKeyInConsulBeatsLegacyKeyInApplicationYml() {
        consul.put("cip.opensearch.index.prefix", "consul");
        applicationYml.put("qip.opensearch.index.prefix", "default");

        assertEquals("consul", environment.getProperty("cip.opensearch.index.prefix"));
    }

    @Test
    void legacyEnvironmentVariableResolvesUnderTheNewPrefix() {
        osEnvironment.put("QIP_OPENSEARCH_INDEX_PREFIX", "from-env");
        applicationYml.put("cip.opensearch.index.prefix", "default");

        assertEquals("from-env", environment.getProperty("cip.opensearch.index.prefix"));
    }

    @Test
    void placeholderToNewKeyResolvesFromLegacyValue() {
        consul.put("qip.sessions.bulk-request.max-size-kb", "512");
        applicationYml.put("cip.sessions.bulk-request.payload-size-threshold-kb",
                "${cip.sessions.bulk-request.max-size-kb}");

        assertEquals("512", environment.getProperty("cip.sessions.bulk-request.payload-size-threshold-kb"));
    }

    @Test
    void mapBindsEntriesFromBothPrefixes() {
        consul.put("cip.internal-services.catalog", "new");
        applicationYml.put("qip.internal-services.engine", "old");

        Map<String, String> bound = Binder.get(environment)
                .bind("cip.internal-services", Bindable.mapOf(String.class, String.class))
                .get();

        assertEquals(Map.of("catalog", "new", "engine", "old"), bound);
    }

    @Test
    void onlyNamesUnderTheNewPrefixAreAliased() {
        applicationYml.put("qip.opensearch.index.prefix", "legacy-prefix");

        assertNull(environment.getPropertySources()
                .get(PropertyPrefixAliasEnvironmentPostProcessor.SOURCE_NAME)
                .getProperty("qip.opensearch.index.prefix"));
        assertEquals("legacy-prefix", environment.getProperty("qip.opensearch.index.prefix"));
    }

    @Test
    @SuppressWarnings("deprecation")
    void isRegisteredAsEnvironmentPostProcessor() {
        assertTrue(SpringFactoriesLoader.loadFactoryNames(EnvironmentPostProcessor.class, getClass().getClassLoader())
                .contains(PropertyPrefixAliasEnvironmentPostProcessor.class.getName()));
    }
}
