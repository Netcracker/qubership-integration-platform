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

package org.qubership.integration.platform.runtime.catalog.service.compiler;

import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.service.compiler.diagnostic.CompoundDiagnosticListener;
import org.qubership.integration.platform.runtime.catalog.service.compiler.diagnostic.FirstErrorCollectorDiagnosticListener;
import org.qubership.integration.platform.runtime.catalog.service.compiler.diagnostic.LoggingDiagnosticListener;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import javax.tools.*;

@Slf4j
public class InMemoryCompiler {
    public Map<String, byte[]> compile(Map<String, String> sources) throws CompilationError {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        FirstErrorCollectorDiagnosticListener<JavaFileObject> firstErrorCollector =
                new FirstErrorCollectorDiagnosticListener<>();
        DiagnosticListener<? super JavaFileObject> diagnosticListener =
                new CompoundDiagnosticListener<>(firstErrorCollector, new LoggingDiagnosticListener(log));
        StandardJavaFileManager standardJavaFileManager = compiler.getStandardFileManager(diagnosticListener, null, null);
        try (InMemoryFileManager fileManager = new InMemoryFileManager(
                new CustomClassLoaderFileManager(
                        this.getClass().getClassLoader(),
                        standardJavaFileManager
                )
        )) {
            List<? extends JavaFileObject> compilationUnits = sources.entrySet().stream().map(entry -> {
                String className = entry.getKey();
                String code = entry.getValue();
                return new JavaSourceFromString(className, code);
            }).collect(Collectors.toList());
            JavaCompiler.CompilationTask compilationTask = compiler.getTask(
                    null, fileManager, diagnosticListener, null, null, compilationUnits);
            boolean isCompilationWasSuccessful = compilationTask.call();
            if (!isCompilationWasSuccessful) {
                String message = firstErrorCollector.getFirstErrorDiagnostic()
                        .map(error -> error.getMessage(Locale.getDefault()))
                        .orElse("Failed to compile code.");
                throw new CompilationError(message);
            }
            return fileManager.getOutputFiles().stream().collect(
                    Collectors.toMap(JavaFileObject::getName, InMemoryOutputFileObject::getBytes));
        } catch (IOException exception) {
            throw new CompilationError("Failed to compile code.", exception);
        }
    }
}
