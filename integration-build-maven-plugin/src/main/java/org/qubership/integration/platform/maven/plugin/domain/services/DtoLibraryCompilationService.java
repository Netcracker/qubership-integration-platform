package org.qubership.integration.platform.maven.plugin.domain.services;

import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.chain.model.IntegrationService;
import org.qubership.integration.platform.chain.model.Protocol;
import org.qubership.integration.platform.chain.model.ServiceSpecification;
import org.qubership.integration.platform.chain.model.SpecificationGroup;
import org.qubership.integration.platform.codegen.SystemModelCodeGenerator;
import org.qubership.integration.platform.codegen.TargetProtocol;
import org.qubership.integration.platform.codegen.model.CodegenSystemModel;
import org.qubership.integration.platform.compiler.CompilerService;
import org.qubership.integration.platform.compiler.JarBuilder;
import org.qubership.integration.platform.maven.plugin.domain.adapters.ServiceSpecificationCodegenAdapter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.Manifest;

import static java.util.Objects.isNull;

@Slf4j
@Service
public class DtoLibraryCompilationService {
    private final CompilerService compilerService;
    private final List<SystemModelCodeGenerator> codeGenerators;

    @Autowired
    public DtoLibraryCompilationService(
        CompilerService compilerService,
        List<SystemModelCodeGenerator> codeGenerators
    ) {
        this.compilerService = compilerService;
        this.codeGenerators = codeGenerators;
    }

    public byte[] generateJar(
        IntegrationService service,
        SpecificationGroup specificationGroup,
        ServiceSpecification serviceSpecification
    ) throws Exception {
        SystemModelCodeGenerator codeGenerator = getCodeGenerator(service.getProtocol());
        if (isNull(codeGenerator)) {
            return null;
        }
        log.debug("Generating library source code for {} specification '{}' ({})",
            service.getProtocol(), serviceSpecification.getName(), serviceSpecification.getId());
        CodegenSystemModel codegenModel = new ServiceSpecificationCodegenAdapter(service, specificationGroup, serviceSpecification);
        Map<String, String> code = codeGenerator.generateCode(codegenModel);
        if (code.isEmpty()) {
            log.debug("Specification '{}' ({}) has no DTO classes.",
                serviceSpecification.getName(), serviceSpecification.getId());
        }
        Manifest manifest = codeGenerator.generateManifest(codegenModel);
        log.debug("Compiling library for service specification '{}' ({})",
            serviceSpecification.getName(), serviceSpecification.getId());
        Map<String, byte[]> compiledCode = code.isEmpty() ? Collections.emptyMap() : compilerService.compile(code);
        JarBuilder jarBuilder = new JarBuilder();
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            jarBuilder.writeJar(outputStream, compiledCode, manifest);
            outputStream.close();
            return outputStream.toByteArray();
        }
    }

    private SystemModelCodeGenerator getCodeGenerator(Protocol protocol) {
        if (isNull(protocol)) {
            return null;
        }
        return codeGenerators.stream().filter(generator ->
            Optional.ofNullable(generator.getClass().getAnnotation(TargetProtocol.class))
                .map(TargetProtocol::protocol)
                .map(target -> target.name().equals(protocol.name())).orElse(false)
        ).findFirst().orElse(null);
    }
}
