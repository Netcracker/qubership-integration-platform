package org.qubership.integration.platform.engine.camel.components;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The engine runs with {@code @SpringBootApplication(exclude = CamelAutoConfiguration.class)}, so the
 * application context holds no {@code CamelContext} bean. Camel resolves a component through
 * {@code DefaultComponentResolver}, which calls {@code getInjector().newInstance(type)}, and the
 * engine's injector is Spring's in {@code AUTOWIRE_CONSTRUCTOR} mode: it picks the greediest
 * constructor it can satisfy. A component whose only constructor takes a {@code CamelContext} is
 * therefore unresolvable, and every chain using it fails at route start.
 */
class RegisteredComponentInstantiationTest {

    private static final Path COMPONENT_SERVICES =
            Path.of("src/main/resources/META-INF/services/org/apache/camel/component");

    @Test
    @DisplayName("Every registered Camel component can be created without a CamelContext")
    void registeredComponentsAreInstantiableWithoutArguments() {
        List<Path> descriptors = descriptors();
        assertThat(descriptors).isNotEmpty();

        for (Path descriptor : descriptors) {
            String className = classNameOf(descriptor);
            Class<?> componentClass = loadClass(className);

            assertThatCode(componentClass::getConstructor)
                    .as("%s is registered as '%s' and must have a public no-argument constructor",
                            className, descriptor.getFileName())
                    .doesNotThrowAnyException();
        }
    }

    private List<Path> descriptors() {
        try (Stream<Path> files = Files.list(COMPONENT_SERVICES)) {
            return files.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private String classNameOf(Path descriptor) {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(descriptor)) {
            properties.load(reader);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        String className = properties.getProperty("class");
        assertThat(className)
                .as("%s must declare a class", descriptor.getFileName())
                .isNotBlank();
        return className;
    }

    private Class<?> loadClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException(className + " is registered as a Camel component but is not on the classpath", exception);
        }
    }
}
