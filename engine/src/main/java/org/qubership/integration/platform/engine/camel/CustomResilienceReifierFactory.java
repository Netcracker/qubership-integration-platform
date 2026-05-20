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

package org.qubership.integration.platform.engine.camel;

import org.apache.camel.Processor;
import org.apache.camel.Route;
import org.apache.camel.model.CircuitBreakerDefinition;
import org.apache.camel.support.TypedProcessorFactory;

public class CustomResilienceReifierFactory extends TypedProcessorFactory<CircuitBreakerDefinition> {

    public CustomResilienceReifierFactory() {
        super(CircuitBreakerDefinition.class);
    }

    @Override
    public Processor doCreateProcessor(Route route, CircuitBreakerDefinition definition) throws Exception {
        return new CustomResilienceReifier(route, definition).createProcessor();
    }

}
