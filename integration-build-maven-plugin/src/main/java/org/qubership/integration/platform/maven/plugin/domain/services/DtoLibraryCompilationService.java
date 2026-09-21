package org.qubership.integration.platform.maven.plugin.domain.services;

import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.codegen.SystemModelCodeGenerator;
import org.qubership.integration.platform.compiler.CompilerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

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

    //public byte[] generateJar(Model model) {}
}
