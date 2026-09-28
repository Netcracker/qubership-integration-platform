package org.qubership.integration.platform.runtime.catalog.configuration;

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
        applicationYml.put("qip.actions-log.cleanup.cron", "0 0 1 * * ?");

        assertEquals("0 0 1 * * ?", environment.getProperty("cip.actions-log.cleanup.cron"));
    }

    @Test
    void newKeyWinsWithinOneSource() {
        consul.put("qip.actions-log.cleanup.cron", "legacy");
        consul.put("cip.actions-log.cleanup.cron", "new");

        assertEquals("new", environment.getProperty("cip.actions-log.cleanup.cron"));
    }

    @Test
    void legacyKeyInConsulBeatsNewDefaultInApplicationYml() {
        consul.put("qip.actions-log.cleanup.cron", "consul");
        applicationYml.put("cip.actions-log.cleanup.cron", "default");

        assertEquals("consul", environment.getProperty("cip.actions-log.cleanup.cron"));
    }

    @Test
    void newKeyInConsulBeatsLegacyKeyInApplicationYml() {
        consul.put("cip.actions-log.cleanup.cron", "consul");
        applicationYml.put("qip.actions-log.cleanup.cron", "default");

        assertEquals("consul", environment.getProperty("cip.actions-log.cleanup.cron"));
    }

    @Test
    void legacyEnvironmentVariableResolvesUnderTheNewPrefix() {
        osEnvironment.put("QIP_ISTIO_ENABLED", "false");
        applicationYml.put("cip.istio.enabled", "true");

        assertEquals("false", environment.getProperty("cip.istio.enabled"));
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
        applicationYml.put("qip.actions-log.cleanup.cron", "0 0 1 * * ?");

        assertNull(environment.getPropertySources()
                .get(PropertyPrefixAliasEnvironmentPostProcessor.SOURCE_NAME)
                .getProperty("qip.actions-log.cleanup.cron"));
        assertEquals("0 0 1 * * ?", environment.getProperty("qip.actions-log.cleanup.cron"));
    }

    @Test
    @SuppressWarnings("deprecation")
    void isRegisteredAsEnvironmentPostProcessor() {
        assertTrue(SpringFactoriesLoader.loadFactoryNames(EnvironmentPostProcessor.class, getClass().getClassLoader())
                .contains(PropertyPrefixAliasEnvironmentPostProcessor.class.getName()));
    }
}
