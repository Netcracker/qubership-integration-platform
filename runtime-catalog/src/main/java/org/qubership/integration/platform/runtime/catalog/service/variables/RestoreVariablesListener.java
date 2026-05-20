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

package org.qubership.integration.platform.runtime.catalog.service.variables;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RestoreVariablesListener {
    private final DefaultVariablesService defaultVariablesService;

    @Autowired
    public RestoreVariablesListener(DefaultVariablesService defaultVariablesService) {
        this.defaultVariablesService = defaultVariablesService;
    }

    @Async
    @EventListener
    public void onApplicationEvent(ApplicationStartedEvent event) {
            try {
                defaultVariablesService.restoreVariables();
            } catch (Exception e) {
                MDC.put("error_code", "8051");
                log.error("Event Listener execution failed with error: can't to restore variables, listener: {}",
                        RestoreVariablesListener.class.getName());
                MDC.remove("error_code");
            }
    }
}
