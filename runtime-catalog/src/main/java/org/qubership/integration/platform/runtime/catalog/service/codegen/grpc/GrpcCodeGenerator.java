/*
 * Copyright 2024-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.integration.platform.runtime.catalog.service.codegen.grpc;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.service.codegen.SystemModelCodeGenerator;
import org.qubership.integration.platform.runtime.catalog.service.codegen.TargetProtocol;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

import static java.util.Objects.nonNull;

@Slf4j
@Component
@TargetProtocol(protocol = OperationProtocol.GRPC)
public class GrpcCodeGenerator implements SystemModelCodeGenerator {
    private final String workDirectory;
    private final String protocCompiler;
    private final String grpcJavaPlugin;

    @Autowired
    public GrpcCodeGenerator(
            @Value("${protoc.work-directory}") String workDirectory,
            @Value("${protoc.compiler}") String protocCompiler,
            @Value("${protoc.grpc-java-plugin}") String grpcJavaPlugin
    ) {
        this.workDirectory = workDirectory;
        this.protocCompiler = protocCompiler;
        this.grpcJavaPlugin = grpcJavaPlugin;
    }

    @Override
    public Manifest generateManifest(SystemModel model) {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        return manifest;
    }

    @Override
    public Map<String, String> generateCode(SystemModel model) throws Exception {
        Path rootDirectory = Paths.get(workDirectory, UUID.randomUUID().toString()).toAbsolutePath();
        try {
            Path sourceDirectory = rootDirectory.resolve("src");
            Path outputDirectory = rootDirectory.resolve("out");
            Files.createDirectories(rootDirectory);
            Files.createDirectories(sourceDirectory);
            Files.createDirectories(outputDirectory);

            Collection<Path> inputFiles = new ArrayList<>();
            for (SpecificationSource source : model.getSpecificationSources()) {
                String fileName = source.getName();
                if (!isProtobufFile(fileName)) {
                    continue;
                }
                Path path = sourceDirectory.resolve(fileName);
                Files.createDirectories(path.getParent());
                Files.writeString(path, source.getSource(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                inputFiles.add(path);
            }
            Path outputFile = outputDirectory.resolve("out.jar");

            compileProtobufFiles(rootDirectory, inputFiles, sourceDirectory, outputFile);
            return getSources(outputFile);
        } finally {
            FileUtils.deleteDirectory(rootDirectory.toFile());
        }
    }

    private void compileProtobufFiles(
            Path rootDirectory,
            Collection<Path> inputFiles,
            Path protocPath,
            Path outputFile
    ) throws Exception {
        Path protocArgsFile = rootDirectory.resolve("protoc.args");
        buildProtobufCompilerArgsFile(protocArgsFile, inputFiles, protocPath, outputFile);

        String command = String.format("%s @%s", protocCompiler, protocArgsFile);
        log.debug("Invoking Protobuf compiler: {}", command);
        Process process = Runtime.getRuntime().exec(command);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            String compilerOutput = new String(IOUtils.toByteArray(process.getErrorStream()))
                    .replace(rootDirectory.toString(), "");
            String message = String.format(
                    "Protocol buffer definition compiler returned non-zero exit code: %d."
                            + " Protobuf compiler output:\n%s",
                    exitCode, compilerOutput);
            log.error(message);
            throw new Exception(message);
        }
    }

    private void buildProtobufCompilerArgsFile(
            Path protocArgsFile,
            Collection<Path> inputFiles,
            Path protocPath,
            Path outputFile
    ) throws IOException {
        String argsFileContent = buildProtobufArgsFileContent(inputFiles, protocPath, outputFile);
        Files.writeString(protocArgsFile, argsFileContent, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    }

    private String buildProtobufArgsFileContent(
            Collection<Path> inputFiles,
            Path protocPath,
            Path outputFile
    ) {
        List<String> protocArgs = new ArrayList<>();
        protocArgs.add("--plugin=" + grpcJavaPlugin);
        protocArgs.add("--grpc-java_out=" + outputFile);
        protocArgs.add("--java_out=" + outputFile);
        protocArgs.add("--proto_path=" + protocPath);
        inputFiles.stream().map(Path::toString).forEach(protocArgs::add);
        return String.join(System.lineSeparator(), protocArgs);
    }

    private static Map<String, String> getSources(Path jarPath) throws IOException {
        Map<String, String> sources = new HashMap<>();
        try (
                FileInputStream in = new FileInputStream(jarPath.toFile());
                JarInputStream jarInputStream = new JarInputStream(in)
        ) {
            JarEntry entry = jarInputStream.getNextJarEntry();
            while (nonNull(entry)) {
                String name = entry.getName();
                if (isJavaSourceFile(name)) {
                    String className = getClassName(name);
                    String classSource = new String(IOUtils.toByteArray(jarInputStream));
                    sources.put(className, classSource);
                }
                entry = jarInputStream.getNextJarEntry();
            }
        }
        return sources;
    }

    private static boolean isJavaSourceFile(String fileName) {
        return fileName.endsWith(".java");
    }

    private static boolean isProtobufFile(String fileName) {
        return fileName.endsWith(".proto");
    }

    private static String getClassName(String fileName) {
        return fileName.substring(0, fileName.indexOf(".")).replaceFirst("^/", "").replace('/', '.');
    }
}
