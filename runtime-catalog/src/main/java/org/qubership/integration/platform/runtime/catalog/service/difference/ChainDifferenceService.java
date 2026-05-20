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

package org.qubership.integration.platform.runtime.catalog.service.difference;

import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ChainDifferenceClientException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ComparisonEntityNotFoundException;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.SwimlaneChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.ChainRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.SnapshotBaseRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.stream.Collectors;

/**
 * Finds the differences between two chains/snapshots.
 *
 * @since 2024.3
 */
@Service
public class ChainDifferenceService {

    private final ChainRepository chainRepository;
    private final SnapshotBaseRepository snapshotRepository;
    private final ChainElementDifferenceService chainElementDiffService;

    @Autowired
    public ChainDifferenceService(
            ChainRepository chainRepository,
            SnapshotBaseRepository snapshotRepository,
            ChainElementDifferenceService chainElementDiffService
    ) {
        this.chainRepository = chainRepository;
        this.snapshotRepository = snapshotRepository;
        this.chainElementDiffService = chainElementDiffService;
    }

    /**
     * Finds the differences for the following cases (leftChainId and rightChainId are mandatory):
     * <li>between two snapshots if the leftSnapshotId and rightSnapshotId are specified;</li>
     * <li>between left chain and right snapshot if the leftSnapshotId is not specified, the rightSnapshotId is specified;</li>
     * <li>between left snapshot and right chain if the leftSnapshotId is specified, the rightSnapshotId is not specified;</li>
     * <li>between two chains if the leftSnapshotId and rightSnapshotId are not specified.</li>
     *
     * @param entityDiffRequest contains chain ids/snapshot ids
     * @return difference result containing the left and right chain/snapshot entities,
     *      as well as a list of difference result objects of chain elements
     */
    public EntityDifferenceResult findChainsDifferences(ChainDifferenceRequest entityDiffRequest) {
        if (entityDiffRequest.getLeftSnapshotId() != null) {
            Snapshot leftSnapshot = findSnapshotById(entityDiffRequest.getLeftSnapshotId());
            if (entityDiffRequest.getRightSnapshotId() != null) {
                return findChainsDifferences(leftSnapshot, findSnapshotById(entityDiffRequest.getRightSnapshotId()));
            }
            if (entityDiffRequest.getRightChainId() != null) {
                return findChainsDifferences(leftSnapshot, findChainById(entityDiffRequest.getRightChainId()));
            }
        } else if (entityDiffRequest.getLeftChainId() != null) {
            Chain leftChain = findChainById(entityDiffRequest.getLeftChainId());
            if (entityDiffRequest.getRightSnapshotId() != null) {
                return findChainsDifferences(leftChain, findSnapshotById(entityDiffRequest.getRightSnapshotId()));
            }
            if (entityDiffRequest.getRightChainId() != null) {
                return findChainsDifferences(leftChain, findChainById(entityDiffRequest.getRightChainId()));
            }
        }
        throw new ChainDifferenceClientException("The chain id or snapshot id must be specified for each side");
    }

    public EntityDifferenceResult findChainsDifferences(Chain leftChain, Chain rightChain) {
        return new EntityDifferenceResult(
                leftChain,
                rightChain,
                findElementsDifferences(
                        filterOutElementsFromSwimlanes(leftChain.getElements()),
                        filterOutElementsFromSwimlanes(rightChain.getElements()),
                        !StringUtils.equals(leftChain.getId(), rightChain.getId())
                )
        );
    }

    public EntityDifferenceResult findChainsDifferences(Snapshot leftSnapshot, Snapshot rightSnapshot) {
        return new EntityDifferenceResult(
                leftSnapshot,
                rightSnapshot,
                findElementsDifferences(
                        filterOutElementsFromSwimlanes(leftSnapshot.getElements()),
                        filterOutElementsFromSwimlanes(rightSnapshot.getElements()),
                        !StringUtils.equals(leftSnapshot.getChain().getId(), rightSnapshot.getChain().getId())
                )
        );
    }

    public EntityDifferenceResult findChainsDifferences(Chain leftChain, Snapshot rightSnapshot) {
        return new EntityDifferenceResult(
                leftChain,
                rightSnapshot,
                findElementsDifferences(
                        filterOutElementsFromSwimlanes(leftChain.getElements()),
                        filterOutElementsFromSwimlanes(rightSnapshot.getElements()),
                        !StringUtils.equals(leftChain.getId(), rightSnapshot.getChain().getId())
                )
        );
    }

    public EntityDifferenceResult findChainsDifferences(Snapshot leftSnapshot, Chain rightChain) {
        return new EntityDifferenceResult(
                leftSnapshot,
                rightChain,
                findElementsDifferences(
                        filterOutElementsFromSwimlanes(leftSnapshot.getElements()),
                        filterOutElementsFromSwimlanes(rightChain.getElements()),
                        !StringUtils.equals(leftSnapshot.getChain().getId(), rightChain.getId())
                )
        );
    }

    private Snapshot findSnapshotById(String id) {
        return snapshotRepository.findById(id)
                .orElseThrow(() -> new ComparisonEntityNotFoundException("Snapshot with id " + id + " not found"));
    }

    private Chain findChainById(String id) {
        return chainRepository.findById(id)
                .orElseThrow(() -> new ComparisonEntityNotFoundException("Chain with id " + id + " not found"));
    }

    private List<DifferenceResult<ChainElement>> findElementsDifferences(
            List<ChainElement> leftElements,
            List<ChainElement> rightElements,
            boolean differentChains
    ) {
        List<DifferenceResult<ChainElement>> diffResults = new ArrayList<>();
        List<String> processedElementIds = new ArrayList<>();

        for (ChainElement leftElement : leftElements) {
            ChainElement comparableRightElement = null;
            Queue<ChainElement> comparableRightElements = rightElements.stream()
                    .filter(rightElement -> compareElements(leftElement, rightElement, differentChains))
                    .collect(Collectors.toCollection(LinkedList::new));
            while (comparableRightElements.peek() != null) {
                ChainElement currentElement = comparableRightElements.poll();
                if (comparableRightElement == null && !processedElementIds.contains(currentElement.getId())) {
                    comparableRightElement = currentElement;
                    processedElementIds.add(comparableRightElement.getId());
                }
            }
            diffResults.add(chainElementDiffService.findDifferences(leftElement, comparableRightElement));
        }

        rightElements.stream()
                .filter(rightElement -> !processedElementIds.contains(rightElement.getId()))
                .forEach(rightElement -> diffResults.add(chainElementDiffService.findDifferences(null, rightElement)));
        return diffResults;
    }

    private boolean compareElements(ChainElement leftElement, ChainElement rightElement, boolean differentChains) {
        if (!differentChains) {
            String leftElementId = extractElementId(leftElement);
            String rightElementId = extractElementId(rightElement);
            return StringUtils.equals(leftElementId, rightElementId);
        }
        if (!StringUtils.equals(leftElement.getType(), rightElement.getType())) {
            return false;
        }
        return StringUtils.equals(leftElement.getName(), rightElement.getName())
                && StringUtils.equals(leftElement.getDescription(), rightElement.getDescription())
                && leftElement.getProperties().equals(rightElement.getProperties());
    }

    private List<ChainElement> filterOutElementsFromSwimlanes(List<ChainElement> elements) {
        return elements.stream()
                .filter(element -> !(element instanceof SwimlaneChainElement))
                .toList();
    }

    private String extractElementId(ChainElement element) {
        return element.getChain() != null ? element.getId() : element.getOriginalId();
    }
}
