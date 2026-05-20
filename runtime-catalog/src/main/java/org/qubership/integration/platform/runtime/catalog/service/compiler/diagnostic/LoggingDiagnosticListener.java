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

import org.slf4j.Logger;

import java.io.IOException;
import java.util.Locale;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticListener;
import javax.tools.JavaFileObject;

import static java.util.Objects.nonNull;

public class LoggingDiagnosticListener implements DiagnosticListener<JavaFileObject> {
    private final Logger logger;

    public LoggingDiagnosticListener(Logger logger) {
        this.logger = logger;
    }

    @Override
    public void report(Diagnostic<? extends JavaFileObject> diagnostic) {
        String message = getDiagnosticMessage(diagnostic);
        switch (diagnostic.getKind()) {
            case WARNING:
            case MANDATORY_WARNING:
                logger.warn(message);
                break;
            case ERROR:
                logger.error(message);
                break;
            case NOTE:
            case OTHER:
                logger.info(message);
                break;
        }
    }

    private String getDiagnosticMessage(Diagnostic<? extends JavaFileObject> diagnostic) {
        JavaFileObject source = diagnostic.getSource();
        StringBuilder sb = new StringBuilder();
        if (nonNull(source)) {
            sb.append(diagnostic.getSource().getName())
                    .append(':')
                    .append(diagnostic.getLineNumber())
                    .append(':');
        }
        sb.append(diagnostic.getMessage(Locale.getDefault()));
        if (diagnostic.getStartPosition() != Diagnostic.NOPOS) {
            sb.append(System.lineSeparator()).append(getCodeFragment(diagnostic));
        }
        return sb.toString();
    }

    private CharSequence getCodeFragment(Diagnostic<? extends JavaFileObject> diagnostic) {
        int start = Long.valueOf(diagnostic.getStartPosition()).intValue();
        int end = Long.valueOf(diagnostic.getEndPosition()).intValue();
        try {
            CharSequence code = diagnostic.getSource().getCharContent(true);
            if (start == end) {
                end = findEOLPosition(code, start);
            }
            return code.subSequence(start, end);
        } catch (IOException exception) {
            logger.error("Failed to get code fragment for diagnostic message", exception);
            return "";
        }
    }

    private static int findEOLPosition(CharSequence text, int start) {
        int index = start;
        while ((index < text.length()) && text.charAt(index) != '\n' && text.charAt(index) != '\r') {
            ++index;
        }
        return index;
    }
}
