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

package org.qubership.integration.platform.runtime.catalog.service.compiler.diagnostic;

import java.util.Optional;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticListener;

import static java.util.Objects.isNull;

public class FirstErrorCollectorDiagnosticListener<S> implements DiagnosticListener<S> {
    private Diagnostic<? extends S> firstErrorDiagnostic;

    public FirstErrorCollectorDiagnosticListener() {
        this.firstErrorDiagnostic = null;
    }


    @Override
    public void report(Diagnostic<? extends S> diagnostic) {
        if (isNull(firstErrorDiagnostic) && Diagnostic.Kind.ERROR.equals(diagnostic.getKind())) {
            this.firstErrorDiagnostic = diagnostic;
        }
    }

    public Optional<Diagnostic<? extends S>> getFirstErrorDiagnostic() {
        return Optional.ofNullable(firstErrorDiagnostic);
    }

    public void reset() {
        firstErrorDiagnostic = null;
    }
}
