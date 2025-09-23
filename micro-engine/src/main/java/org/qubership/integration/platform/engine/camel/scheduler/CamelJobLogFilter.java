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

package org.qubership.integration.platform.engine.camel.scheduler;

import io.quarkus.logging.LoggingFilter;
import org.jboss.logmanager.Level;

import java.util.logging.Filter;
import java.util.logging.LogRecord;

@LoggingFilter(name = "camel-job-filter")
public class CamelJobLogFilter implements Filter {
    @Override
    public boolean isLoggable(LogRecord record) {
        boolean shouldBeFiltered = (record.getLevel().equals(Level.ERROR)
                && record.getLoggerName().equals("org.apache.camel.component.quartz.CamelJob")
                || record.getLevel().equals(Level.INFO)
                && record.getLoggerName().equals("org.quartz.core.JobRunShell"))
                && record.getThrown() != null
                && record.getThrown().getMessage() != null
                && record.getThrown().getMessage().startsWith("No CamelContext could be found with name");
        return !shouldBeFiltered;
    }
}
