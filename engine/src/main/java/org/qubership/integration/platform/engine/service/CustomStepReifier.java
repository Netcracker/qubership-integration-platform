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

package org.qubership.integration.platform.engine.service;

import org.apache.camel.Processor;
import org.apache.camel.Route;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.StepDefinition;
import org.apache.camel.processor.StepProcessor;
import org.apache.camel.reifier.ProcessorReifier;

import java.util.Collection;
import java.util.Collections;
import java.util.List;


public class CustomStepReifier extends ProcessorReifier<StepDefinition> {

    public CustomStepReifier(Route route, ProcessorDefinition<?> definition) {
        super(route, StepDefinition.class.cast(definition));
    }

    @Override
    public Processor createProcessor() throws Exception {
        return this.createChildProcessor(true);
    }

    @Override
    protected Processor createCompositeProcessor(List<Processor> list) throws Exception {
        String stepId = getId(definition);
        if (list.isEmpty()) {
            return null;
        }
        return new StepProcessor(camelContext, list, stepId);
    }

    protected Processor createOutputsProcessor(Collection<ProcessorDefinition<?>> outputs) throws Exception {
        Processor processor = super.createOutputsProcessor(outputs);
        if (!(processor instanceof StepProcessor)) {
            return createCompositeProcessor(Collections.singletonList(processor));
        }
        return processor;
    }
}
