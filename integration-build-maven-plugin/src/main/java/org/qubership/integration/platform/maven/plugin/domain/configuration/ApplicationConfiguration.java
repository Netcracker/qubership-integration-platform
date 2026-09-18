package org.qubership.integration.platform.maven.plugin.domain.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

/**
 * Spring context for the build-crs goal.
 *
 * <p>The three route builders under {@code camelk.builders} decide for themselves whether to register,
 * through a {@code @ConditionalOnProperty} on the mesh type. This module takes that decision from the
 * {@code controlPlaneType} mojo parameter instead, in the {@code OptionControlled*} subclasses, so the
 * base classes are excluded from the scan. Without the exclusion, a stray
 * {@code qip.control-plane.mesh-type} and {@code qip.istio.enabled} on the Maven JVM would register a
 * base class next to its subclass and the build would fail on a duplicate resource.
 *
 * <p>The filter matches class names, and the subclasses live in another package under other names, so
 * only the base classes are excluded.
 */
@Configuration
@ComponentScan(
    basePackages = "org.qubership.integration.platform",
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = {
            "org\\.qubership\\.integration\\.platform\\.camelk\\.builders\\.chain\\.HttpRouteResourceBuilder",
            "org\\.qubership\\.integration\\.platform\\.camelk\\.builders\\.chain\\.EgressRouteResourceBuilder",
            "org\\.qubership\\.integration\\.platform\\.camelk\\.builders\\.EngineRoutesResourceBuilder"
        }
    )
)
@PropertySource(
    value = "classpath:application.yml",
    factory = YamlPropertySourceFactory.class
)
public class ApplicationConfiguration {
    /**
     * Resolves {@code @Value} placeholders, and fails the build on one it cannot resolve.
     *
     * <p>Without this bean the context falls back to the environment's non-strict resolver, which
     * leaves an unresolvable {@code ${...}} in place as a literal. That turned a missing property into
     * a placeholder written verbatim into a generated resource, where it reached the engine as a
     * hostname. A property this module forgets to define now fails at build time instead.
     */
    @Bean
    public static PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
        PropertySourcesPlaceholderConfigurer configurer = new PropertySourcesPlaceholderConfigurer();
        configurer.setIgnoreUnresolvablePlaceholders(false);
        return configurer;
    }
}
