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

import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.ConfigParameter;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.ConfigParameterRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional
public class ConfigParameterService {

    private final ConfigParameterRepository configParameterRepository;

    public ConfigParameterService(ConfigParameterRepository configParameterRepository) {
        this.configParameterRepository = configParameterRepository;
    }

    public List<ConfigParameter> findAllByNamespace(String namespace) {
        return configParameterRepository.findAllByNamespace(namespace);
    }

    public ConfigParameter findByName(String namespace, String name) {
        return configParameterRepository.findByNamespaceAndName(namespace, name);
    }

    public ConfigParameter createIfNotExists(String namespace, String name) {
        ConfigParameter param = findByName(namespace, name);
        if (param == null) {
            param = new ConfigParameter(namespace, name);
        }
        return param;
    }

    public void deleteByName(String namespace, String name) {
        configParameterRepository.deleteByNamespaceAndName(namespace, name);
    }

    public void delete(ConfigParameter param) {
        configParameterRepository.delete(param);
    }

    public void deleteAllByNamespace(String namespace) {
        configParameterRepository.deleteAllByNamespace(namespace);
    }

    public List<ConfigParameter> update(List<ConfigParameter> params) {
        return params.stream().map(this::update).collect(Collectors.toList());
    }

    public ConfigParameter update(ConfigParameter param) {
        ConfigParameter newParameter = createIfNotExists(param.getNamespace(), param.getName());
        newParameter.checkValueType(param.getValueType());
        newParameter.setRawValueIfTypeMatch(param.getValue(), param.getValueType());
        newParameter = configParameterRepository.save(newParameter);

        return newParameter;
    }

    public void flush() {
        configParameterRepository.flush();
    }
}
