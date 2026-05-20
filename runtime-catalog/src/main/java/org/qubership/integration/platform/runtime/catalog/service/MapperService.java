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

package org.qubership.integration.platform.runtime.catalog.service;

import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.mapper.MappingInterpretation;
import org.qubership.integration.platform.runtime.catalog.mapper.MappingInterpreter;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.MappingDescription;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class MapperService {

    private final Map<String, MappingInterpreter> interpreters = new HashMap<>();

    @Autowired
    public MapperService(List<MappingInterpreter> interpreters) {
        for (MappingInterpreter interpreter : interpreters) {
            MappingInterpretation interpretation = interpreter.getClass().getAnnotation(MappingInterpretation.class);
            if (interpretation != null) {
                this.interpreters.put(interpretation.value(), interpreter);
            }
        }
    }

    private MappingInterpreter getInterpreter(String interpreterName) {
        return this.interpreters.get(interpreterName);
    }

    public String getMappingInterpretation(MappingDescription mappingDescription) {
        MappingInterpreter interpreter = getInterpreter("AtlasMap");
        return interpreter.getInterpretation(mappingDescription);
    }

}
