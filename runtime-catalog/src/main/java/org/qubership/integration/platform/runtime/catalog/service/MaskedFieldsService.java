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

import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.MaskedField;
import org.qubership.integration.platform.catalog.persistence.configs.repository.chain.MaskedFieldRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Slf4j
@Service
@Transactional
public class MaskedFieldsService {
    private static final String MASKED_FIELD_WITH_ID_NOT_FOUND_MESSAGE = "Can't find masked field with id: ";

    private final MaskedFieldRepository maskedRepository;

    @Autowired
    public MaskedFieldsService(MaskedFieldRepository maskedRepository) {
        this.maskedRepository = maskedRepository;
    }

    public MaskedField findById(String fieldId) {
        return maskedRepository.findById(fieldId).orElseThrow(() -> new EntityNotFoundException(MASKED_FIELD_WITH_ID_NOT_FOUND_MESSAGE + fieldId));
    }

    public MaskedField save(MaskedField maskedField) {
        return maskedRepository.save(maskedField);
    }

    public void deleteAllByChainIdAndFlush(String chainId) {
        maskedRepository.deleteAllByChainId(chainId);
        maskedRepository.flush();
    }

    public void setActualizedMaskedFields(Set<MaskedField> currentMaskedFieldStates, Set<MaskedField> newMaskedFieldStates) {
        maskedRepository.actualizeCollectionState(currentMaskedFieldStates, newMaskedFieldStates);
    }
}
