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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;

public class CamelJobLogFilter extends Filter<ILoggingEvent> {
    @Override
    public FilterReply decide(ILoggingEvent event) {
        return  (event.getLevel().equals(Level.ERROR) &&
                event.getLoggerName().equals("org.apache.camel.component.quartz.CamelJob") ||
                event.getLevel().equals(Level.INFO) &&
                event.getLoggerName().equals("org.quartz.core.JobRunShell")) &&
                event.getThrowableProxy() != null &&
                event.getThrowableProxy().getMessage() != null &&
                event.getThrowableProxy().getMessage().startsWith("No CamelContext could be found with name") ?
                    FilterReply.DENY :
                    FilterReply.NEUTRAL;
    }
}
