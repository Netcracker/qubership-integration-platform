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

package org.qubership.integration.platform.runtime.catalog.service.ddsgenerator.elements;

import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ddsgenerator.TemplateDataBuilderException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ddsgenerator.TemplateDataEscapingException;
import org.qubership.integration.platform.runtime.catalog.model.dds.*;
import org.qubership.integration.platform.runtime.catalog.model.designgenerator.DiagramLangType;
import org.qubership.integration.platform.runtime.catalog.model.designgenerator.DiagramMode;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.service.ddsgenerator.elements.converter.ElementDDSConverter;
import org.qubership.integration.platform.runtime.catalog.service.designgenerator.DesignGeneratorService;
import org.qubership.integration.platform.runtime.catalog.util.escaping.EscapeUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Function;

@Slf4j
@Component
public class TemplateDataBuilder {

    public static final Function<ChainElement, Boolean> DEFAULT_ELEMENT_FILTER = (element) -> true;
    private final Map<String, ElementDDSConverter> elementConverters = new HashMap<>();

    private final DesignGeneratorService designGeneratorService;

    @Autowired
    public TemplateDataBuilder(List<ElementDDSConverter> elementConvertersList, DesignGeneratorService designGeneratorService) {
        this.designGeneratorService = designGeneratorService;
        elementConvertersList.forEach(converter -> converter.getTypes().forEach(type -> this.elementConverters.put(type, converter)));
    }

    public TemplateData build(Chain chain, List<ChainElement> chainElements) throws TemplateDataEscapingException, TemplateDataBuilderException {
        TemplateData data;
        try {
            List<TemplateChainElement> httpTriggersImplemented = convertByTypes(chainElements, Map.of("http-trigger",
                    (element) -> IntegrationSystemType.IMPLEMENTED.toString().equals(element.getProperties().get("systemType"))));
            List<TemplateChainElement> httpTriggers = convertByTypes(chainElements, Map.of("http-trigger", DEFAULT_ELEMENT_FILTER));

            List<TemplateChainElement> httpServiceCalls = convertByTypes(chainElements, Map.of("service-call",
                    (element) -> "http".equals(element.getProperty("integrationOperationProtocolType"))
                            && element.getProperty("integrationSystemId") != null
                            && element.getProperty("integrationSpecificationId") != null
                            && element.getProperty("integrationOperationId") != null));

            List<TemplateChainElement> serviceCalls = convertByTypes(chainElements, Map.of("service-call", DEFAULT_ELEMENT_FILTER));
            List<TemplateChainElement> errorHandling = convertByTypes(chainElements, Map.of(
                    "service-call", (element) -> element.getProperty("after") instanceof Collection<?> collection && !collection.isEmpty()));
            List<TemplateChainElement> mappers = convertByTypes(chainElements, Map.of("mapper-2", DEFAULT_ELEMENT_FILTER));

            data = TemplateData.builder()
                    .chain(
                            TemplateChain.builder()
                                    .name(chain.getName())
                                    .description(chain.getDescription())
                                    .masking(TemplateChainMasking.builder()
                                            .fields(chain.getMaskedFields().stream()
                                                    .map(field -> TemplateChainMaskingField.builder().name(field.getName()).build())
                                                    .toList())
                                            .build())
                                    .doc(TemplateChainDoc.builder()
                                            .businessDescription(chain.getBusinessDescription())
                                            .assumptions(chain.getAssumptions())
                                            .outOfScope(chain.getOutOfScope())
                                            .simpleSeqDiagram(buildSimpleSeqDiagram(chain))
                                            .build())
                                    .elements(TemplateChainElements.builder()
                                            .httpTriggers(httpTriggers)
                                            .httpTriggersImplemented(httpTriggersImplemented)
                                            .httpServiceCalls(httpServiceCalls)
                                            .withErrorHandling(errorHandling)
                                            .withAuthorization(serviceCalls)
                                            .mappers(mappers)
                                            .build())
                                    .build()
                    )
                    .build();
        } catch (TemplateDataBuilderException tdbe) {
            throw tdbe;
        } catch (Exception e) {
            log.error("Failed to convert chain data to TemplateData", e);
            throw new TemplateDataBuilderException("Failed to convert chain data to TemplateData: " + e.getMessage(), e);
        }

        try {
            EscapeUtils.escapeMarkdownDataRecursive(data);
        } catch (Exception e) {
            log.error("Failed to escape chain detailed design template data", e);
            throw new TemplateDataEscapingException("Failed to escape chain detailed design template data: " + e.getMessage(), e);
        }
        return data;
    }

    private TemplateSequenceDiagram buildSimpleSeqDiagram(Chain chain) {
        Map<DiagramLangType, String> diagrams =
                designGeneratorService
                        .generateChainSequenceDiagram(chain.getId(), List.of(DiagramMode.SIMPLE))
                        .get(DiagramMode.SIMPLE).getDiagramSources();

        return TemplateSequenceDiagram.builder()
                .mermaid(diagrams.get(DiagramLangType.MERMAID))
                .plantuml(diagrams.get(DiagramLangType.PLANT_UML))
                .build();
    }

    private List<TemplateChainElement> convertByTypes(List<ChainElement> elements, Map<String, Function<ChainElement, Boolean>> typesFilter) throws TemplateDataBuilderException {
        List<TemplateChainElement> result = new ArrayList<>();

        for (ChainElement element : elements) {
            String elementType = element.getType();
            if (typesFilter.containsKey(elementType)) {
                Function<ChainElement, Boolean> filterFunc = typesFilter.get(elementType);
                if (filterFunc.apply(element)) {
                    ElementDDSConverter converter = elementConverters.get(elementType);
                    if (converter != null) {
                        TemplateChainElement templateElement = converter.convert(element);
                        if (templateElement != null) {
                            result.add(templateElement);
                        }
                    } else {
                        log.error("Converter for type: '{}' not implemented", elementType);
                        throw new TemplateDataBuilderException("Converter for type: '" + elementType + "' not implemented");
                    }
                }
            }
        }
        return result;
    }
}
