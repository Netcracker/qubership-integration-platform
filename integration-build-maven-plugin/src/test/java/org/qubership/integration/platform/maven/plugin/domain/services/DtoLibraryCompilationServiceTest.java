package org.qubership.integration.platform.maven.plugin.domain.services;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.chain.model.IntegrationService;
import org.qubership.integration.platform.chain.model.Protocol;
import org.qubership.integration.platform.chain.model.ServiceSpecification;
import org.qubership.integration.platform.chain.model.SpecificationGroup;
import org.qubership.integration.platform.chain.model.SpecificationSource;
import org.qubership.integration.platform.codegen.SystemModelCodeGenerator;
import org.qubership.integration.platform.codegen.TargetProtocol;
import org.qubership.integration.platform.codegen.model.CodegenSpecificationSource;
import org.qubership.integration.platform.codegen.model.CodegenSystemModel;
import org.qubership.integration.platform.compiler.CompilationError;
import org.qubership.integration.platform.compiler.CompilerService;
import org.qubership.integration.platform.io.model.exportimport.system.OperationProtocol;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DtoLibraryCompilationServiceTest {

    private static final byte[] CLASS_DATA = new byte[] {1, 2, 3};

    private final CompilerService compilerService = mock(CompilerService.class);

    // Real, not a mock: the generator is selected by the @TargetProtocol annotation on its class,
    // which a mock subclass does not carry.
    private final TestCodeGenerator grpcGenerator = new TestCodeGenerator();

    private final DtoLibraryCompilationService compilationService =
        new DtoLibraryCompilationService(compilerService, List.of(grpcGenerator));

    @Test
    void returnsNoLibraryWhenNoGeneratorTargetsTheServiceProtocol() throws Exception {
        assertNull(compilationService.generateJar(
            service(Protocol.HTTP), group(), specification()));

        verifyNoInteractions(compilerService);
    }

    @Test
    void returnsNoLibraryWhenTheServiceHasNoProtocol() throws Exception {
        assertNull(compilationService.generateJar(
            service(null), group(), specification()));

        verifyNoInteractions(compilerService);
    }

    @Test
    void packsTheCompiledClassesAndTheGeneratorManifestIntoTheJar() throws Exception {
        grpcGenerator.code = Map.of("org/example/Dto.java", "package org.example; class Dto {}");
        when(compilerService.compile(grpcGenerator.code)).thenReturn(Map.of("org/example/Dto.class", CLASS_DATA));

        byte[] jar = compilationService.generateJar(service(Protocol.GRPC), group(), specification());

        assertArrayEquals(CLASS_DATA, entries(jar).get("org/example/Dto.class"));
        assertEquals("orders", manifest(jar).getMainAttributes().getValue("Specification-Title"));
    }

    @Test
    void skipsCompilationWhenTheGeneratorProducesNoCode() throws Exception {
        byte[] jar = compilationService.generateJar(service(Protocol.GRPC), group(), specification());

        verifyNoInteractions(compilerService);
        assertTrue(entries(jar).isEmpty());
        assertEquals("orders", manifest(jar).getMainAttributes().getValue("Specification-Title"));
    }

    @Test
    void reportsTheCompilationFailure() throws Exception {
        grpcGenerator.code = Map.of("org/example/Dto.java", "not java");
        when(compilerService.compile(grpcGenerator.code)).thenThrow(new CompilationError("broken source"));

        CompilationError error = assertThrows(CompilationError.class, () -> compilationService.generateJar(
            service(Protocol.GRPC), group(), specification()));

        assertEquals("broken source", error.getMessage());
    }

    @Test
    void passesTheSpecificationToTheGeneratorAsASystemModel() throws Exception {
        compilationService.generateJar(service(Protocol.GRPC), group(), specification());

        CodegenSystemModel model = grpcGenerator.model;
        assertEquals("specification-1", model.getId());
        assertEquals("orders", model.getName());
        assertEquals("payments", model.getSystemName());
        assertEquals("payments-api", model.getGroupName());
        assertEquals(OperationProtocol.GRPC, model.getProtocol());
        CodegenSpecificationSource source = model.getSpecificationSources().get(0);
        assertEquals("orders.proto", source.getName());
        assertEquals("syntax = \"proto3\";", source.getSource());
    }

    private static IntegrationService service(Protocol protocol) {
        IntegrationService service = mock(IntegrationService.class);
        when(service.getName()).thenReturn("payments");
        when(service.getProtocol()).thenReturn(protocol);
        return service;
    }

    private static SpecificationGroup group() {
        SpecificationGroup group = mock(SpecificationGroup.class);
        when(group.getName()).thenReturn("payments-api");
        return group;
    }

    private static ServiceSpecification specification() {
        SpecificationSource source = mock(SpecificationSource.class);
        when(source.getName()).thenReturn("orders.proto");
        when(source.getText()).thenReturn("syntax = \"proto3\";");
        ServiceSpecification specification = mock(ServiceSpecification.class);
        when(specification.getId()).thenReturn("specification-1");
        when(specification.getName()).thenReturn("orders");
        when(specification.getSources()).thenReturn(List.of(source));
        return specification;
    }

    private static Map<String, byte[]> entries(byte[] jar) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (JarInputStream stream = new JarInputStream(new ByteArrayInputStream(jar))) {
            for (JarEntry entry = stream.getNextJarEntry(); entry != null; entry = stream.getNextJarEntry()) {
                if (!entry.isDirectory()) {
                    entries.put(entry.getName(), stream.readAllBytes());
                }
            }
        }
        return entries;
    }

    private static Manifest manifest(byte[] jar) throws IOException {
        try (JarInputStream stream = new JarInputStream(new ByteArrayInputStream(jar))) {
            return stream.getManifest();
        }
    }

    @TargetProtocol(protocol = OperationProtocol.GRPC)
    private static class TestCodeGenerator implements SystemModelCodeGenerator {
        private Map<String, String> code = Map.of();
        private CodegenSystemModel model;

        @Override
        public Manifest generateManifest(CodegenSystemModel model) {
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().putValue("Specification-Title", model.getName());
            return manifest;
        }

        @Override
        public Map<String, String> generateCode(CodegenSystemModel model) {
            this.model = model;
            return code;
        }
    }
}
