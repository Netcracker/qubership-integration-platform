package org.qubership.integration.platform.runtime.catalog.configuration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;

import java.util.Arrays;

/**
 * Resolves {@code cip.*} properties from their legacy {@code qip.*} names.
 *
 * <p>For a lookup of {@code cip.x}, the first property source that holds {@code cip.x} or {@code qip.x} decides, and
 * within one source {@code cip.x} wins. A {@code qip.x} set in Consul therefore overrides a {@code cip.x} default
 * shipped in {@code application.yml}.
 */
public class PropertyPrefixAliasEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String SOURCE_NAME = "propertyPrefixAliases";

    private static final String NEW_PREFIX = "cip.";
    private static final String OLD_PREFIX = "qip.";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        environment.getPropertySources().addFirst(new PrefixAliasPropertySource(environment));
    }

    // The Consul sources come from spring.config.import, so they exist once the config data processor has run.
    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    // Parameterized on MutablePropertySources, never on ConfigurableEnvironment: Spring Boot's own
    // SpringConfigurationPropertySources$SourcesIterator special-cases a PropertySource whose getSource() is a
    // ConfigurableEnvironment as a nested environment and re-walks environment.getPropertySources() from it, which
    // would find this very source again first and recurse forever (StackOverflowError) as soon as any Binder- or
    // @ConfigurationProperties-based lookup ran.
    private static final class PrefixAliasPropertySource extends EnumerablePropertySource<MutablePropertySources> {

        PrefixAliasPropertySource(ConfigurableEnvironment environment) {
            super(SOURCE_NAME, environment.getPropertySources());
        }

        // Asks each source directly, never the Environment, so a lookup cannot come back to this source.
        @Override
        public Object getProperty(String name) {
            if (!name.startsWith(NEW_PREFIX)) {
                return null;
            }
            String legacyName = OLD_PREFIX + name.substring(NEW_PREFIX.length());
            for (PropertySource<?> source : getSource()) {
                if (!isDelegate(source)) {
                    continue;
                }
                Object value = source.getProperty(name);
                if (value == null) {
                    value = source.getProperty(legacyName);
                }
                if (value != null) {
                    return value;
                }
            }
            return null;
        }

        // Lists every cip.* and qip.* name under the new prefix, so binding takes each one from this source.
        @Override
        public String[] getPropertyNames() {
            return getSource().stream()
                    .filter(source -> isDelegate(source) && source instanceof EnumerablePropertySource)
                    .flatMap(source -> Arrays.stream(((EnumerablePropertySource<?>) source).getPropertyNames()))
                    .filter(name -> name.startsWith(NEW_PREFIX) || name.startsWith(OLD_PREFIX))
                    .map(name -> NEW_PREFIX + name.substring(OLD_PREFIX.length()))
                    .distinct()
                    .toArray(String[]::new);
        }

        // The attached configurationProperties source wraps every other source, this one included.
        private boolean isDelegate(PropertySource<?> source) {
            return source != this && !ConfigurationPropertySources.isAttachedConfigurationPropertySource(source);
        }
    }
}
