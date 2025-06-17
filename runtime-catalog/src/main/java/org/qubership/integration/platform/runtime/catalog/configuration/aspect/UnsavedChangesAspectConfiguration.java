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

package org.qubership.integration.platform.runtime.catalog.configuration.aspect;

import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.qubership.integration.platform.runtime.catalog.model.ChainDiff;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Dependency;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.MaskedField;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ContainerChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.ChainRepository;
import org.qubership.integration.platform.runtime.catalog.service.migration.MigratedChain;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Optional;

@Aspect
@Configuration
public class UnsavedChangesAspectConfiguration {

    private final ChainRepository chainRepository;

    @Autowired
    public UnsavedChangesAspectConfiguration(ChainRepository chainRepository) {
        this.chainRepository = chainRepository;
    }

    @Pointcut("@annotation(org.qubership.integration.platform.runtime.catalog.configuration.aspect.ChainModification)")
    public void detectChainUnsavedChanges() {
    }

    @AfterReturning(value = "detectChainUnsavedChanges()", returning = "returningValue")
    public void markChainAsUnsaved(Object returningValue) {
        String chainId = null;
        boolean markUnsavedChanges = true;

        if (returningValue != null) {
            if (returningValue instanceof List<?> returningList && !returningList.isEmpty()) {
                if (returningList.get(0) instanceof ChainElement firstElement) {
                    chainId = getChainIdFromElement(firstElement);
                }

                if (returningList.get(0) instanceof Dependency firstDependency) {
                    chainId = getChainIdFromElement(firstDependency.getElementFrom() != null
                            ? firstDependency.getElementFrom()
                            : firstDependency.getElementTo());
                }
            }

            /*In this case chain metadata does not change
             "Unsaved changes" mark will not be applied */
            if (returningValue instanceof Chain) {
                markUnsavedChanges = false;
                chainId = ((Chain) returningValue).getId();
            }

            if (returningValue instanceof Dependency dependency) {
                chainId = getChainIdFromElement(dependency.getElementFrom() != null ? dependency.getElementFrom()
                        : dependency.getElementTo());
            }

            if (returningValue instanceof ChainElement) {
                chainId = getChainIdFromElement((ChainElement) returningValue);
            }

            if (returningValue instanceof ContainerChainElement containerChainElement) {
                if (containerChainElement.getChain() != null) {
                    chainId = containerChainElement.getChain().getId();
                }
            }

            if (returningValue instanceof ChainDiff) {
                chainId = extractChainIdFromChainDiff((ChainDiff) returningValue);
            }

            if (returningValue instanceof MaskedField) {
                if (((MaskedField) returningValue).getChain() != null) {
                    chainId = ((MaskedField) returningValue).getChain().getId();
                }
            }

            if (returningValue instanceof MigratedChain migratedChain && migratedChain.chain() != null) {
                chainId = migratedChain.chain().getId();
            }
        }

        if (chainId != null && markUnsavedChanges) {
            Chain chain = chainRepository.getReferenceById(chainId);
            if (!chain.isUnsavedChanges()) {
                chain.setLastImportHash("0");
                chain.setUnsavedChanges(true);
                chainRepository.save(chain);
            }
        }
    }

    private String getChainIdFromElement(ChainElement chainElement) {
        if (chainElement.getChain() != null) {
            return chainElement.getChain().getId();
        }
        return null;
    }

    private String extractChainIdFromChainDiff(ChainDiff chainDiff) {
        return extractChainIdFromElementList(chainDiff.getCreatedElements())
                .or(() -> extractChainIdFromElementList(chainDiff.getUpdatedElements()))
                .or(() -> extractChainIdFromElementList(chainDiff.getRemovedElements()))
                .or(() -> extractChainIdFromDependencyList(chainDiff.getCreatedDependencies()))
                .or(() -> extractChainIdFromDependencyList(chainDiff.getRemovedDependencies()))
                .orElse(null);
    }

    private Optional<String> extractChainIdFromElementList(List<ChainElement> elements) {
        if (!elements.isEmpty()) {
            return Optional.ofNullable(getChainIdFromElement(elements.get(0)));
        }
        return Optional.empty();
    }

    private Optional<String> extractChainIdFromDependencyList(List<Dependency> dependencies) {
        if (!dependencies.isEmpty()) {
            Dependency dependency = dependencies.get(0);
            return Optional.ofNullable(getChainIdFromElement(
                    dependency.getElementFrom() != null
                            ? dependency.getElementFrom()
                            : dependency.getElementTo()
            ));
        }
        return Optional.empty();
    }
}
