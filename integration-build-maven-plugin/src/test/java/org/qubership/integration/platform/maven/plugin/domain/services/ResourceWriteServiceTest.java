package org.qubership.integration.platform.maven.plugin.domain.services;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.UndeclaredThrowableException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceWriteServiceTest {

    private static final String DEPLOYMENT_AND_SERVICE = """
        ---
        kind: Deployment
        metadata:
          name: qip-engine-default
        ---
        kind: Service
        metadata:
          name: qip-engine-default
        """;

    private final ResourceWriteService resourceWriteService = new ResourceWriteService(new YAMLMapper());

    /**
     * A Helm expression in a value position starts a YAML flow mapping, so the document only parses once
     * it is stripped. The file still carries the original content.
     */
    @Test
    void readsTheKindAndNameOfATemplatedResource(@TempDir Path directory) throws IOException {
        String templated = """
            ---
            kind: ServiceMonitor
            metadata:
              name: qip-engine-default
            spec:
              namespaceSelector:
                matchNames:
                - {{ .Release.Namespace }}
            """;

        resourceWriteService.writeResources(directory.toString(), templated);

        Path written = directory.resolve("ServiceMonitor-qip-engine-default.yaml");
        assertTrue(Files.exists(written));
        assertTrue(Files.readString(written).contains("{{ .Release.Namespace }}"),
            "the template must survive into the file; only the parse input is stripped");
    }

    /**
     * Guards the group staying non-capturing. Written {@code (:?} rather than {@code (?:} the matcher
     * recurses, and a long unterminated expression overflows the stack instead of being left alone.
     */
    @Test
    void survivesALongUnterminatedExpression(@TempDir Path directory) throws IOException {
        String unterminated = """
            ---
            kind: ConfigMap
            metadata:
              name: qip-engine-default
            data:
              content: "{{ %s"
            """.formatted("x".repeat(4000));

        resourceWriteService.writeResources(directory.toString(), unterminated);

        assertTrue(Files.exists(directory.resolve("ConfigMap-qip-engine-default.yaml")));
    }

    @Test
    void writesOneFilePerResourceNamedAfterKindAndName(@TempDir Path directory) throws IOException {
        Path outputDirectory = directory.resolve("crs");

        resourceWriteService.writeResources(outputDirectory.toString(), DEPLOYMENT_AND_SERVICE);

        Path deployment = outputDirectory.resolve("Deployment-qip-engine-default.yaml");
        Path service = outputDirectory.resolve("Service-qip-engine-default.yaml");
        try (Stream<Path> files = Files.list(outputDirectory)) {
            assertEquals(2, files.count());
        }
        assertTrue(Files.readString(deployment).contains("kind: Deployment"));
        assertTrue(Files.readString(service).contains("kind: Service"));
    }

    @Test
    void keepsTheDocumentSeparatorInEachFile(@TempDir Path directory) throws IOException {
        resourceWriteService.writeResources(directory.toString(), DEPLOYMENT_AND_SERVICE);

        String content = Files.readString(directory.resolve("Deployment-qip-engine-default.yaml"));
        assertTrue(content.startsWith("---"));
    }

    @Test
    void rejectsTwoResourcesOfTheSameKindAndName(@TempDir Path directory) {
        String resources = DEPLOYMENT_AND_SERVICE.replace("kind: Service", "kind: Deployment");

        UndeclaredThrowableException exception = assertThrows(UndeclaredThrowableException.class,
            () -> resourceWriteService.writeResources(directory.toString(), resources));

        assertEquals(ResourceWriteService.ResourceWriteException.class, exception.getCause().getClass());
        assertTrue(exception.getCause().getMessage().contains("Duplicate resource"));
    }

    @Test
    void rejectsAResourceWithoutAKind(@TempDir Path directory) {
        String resources = """
            ---
            metadata:
              name: qip-engine-default
            """;

        RuntimeException exception = assertThrows(RuntimeException.class,
            () -> resourceWriteService.writeResources(directory.toString(), resources));

        assertTrue(exception.getMessage().contains("Failed to get resource kind"));
    }

    @Test
    void rejectsAResourceWithoutAName(@TempDir Path directory) {
        String resources = """
            ---
            kind: Deployment
            metadata:
              labels:
                app: qip
            """;

        RuntimeException exception = assertThrows(RuntimeException.class,
            () -> resourceWriteService.writeResources(directory.toString(), resources));

        assertTrue(exception.getMessage().contains("Failed to get resource name"));
    }
}
