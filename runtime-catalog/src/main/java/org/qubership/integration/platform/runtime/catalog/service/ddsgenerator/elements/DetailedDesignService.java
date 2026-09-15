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

import com.vladsch.flexmark.formatter.Formatter;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import freemarker.cache.NullCacheStorage;
import freemarker.cache.TemplateLoader;
import freemarker.template.Configuration;
import freemarker.template.Template;
import io.micrometer.core.instrument.util.IOUtils;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;
import org.qubership.integration.platform.library.constants.CamelNames;
import org.qubership.integration.platform.library.constants.CamelOptions;
import org.qubership.integration.platform.library.util.ResourceLoaderUtils;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ddsgenerator.DetailedDesignInternalException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ddsgenerator.TemplateDataBuilderException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ddsgenerator.TemplateDataEscapingException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ddsgenerator.TemplateProcessingException;
import org.qubership.integration.platform.runtime.catalog.model.dds.TemplateData;
import org.qubership.integration.platform.runtime.catalog.model.dds.TemplateSequenceDiagram;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.DetailedDesignTemplate;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.DetailedDesignTemplateRepository;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.dds.DDSResponse;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.dds.DDSSpecificationSource;
import org.qubership.integration.platform.runtime.catalog.service.ActionsLogService;
import org.qubership.integration.platform.runtime.catalog.service.OperationService;
import org.qubership.integration.platform.runtime.catalog.service.SystemModelService;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ChainFinderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Slf4j
@Service
public class DetailedDesignService {
    public static final String CLASSPATH_DDS_TEMPLATES_PATTERN = "classpath*:dds/templates/*.ftl";
    public static final Pattern TEMPLATE_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_.-]+$");

    private final ChainFinderService chainFinderService;
    private final SystemModelService systemModelService;
    private final OperationService operationService;
    private final ActionsLogService actionLogger;
    private final TemplateDataBuilder templateDataBuilder;
    private final DetailedDesignTemplateRepository designTemplateRepository;

    private final Configuration freemakerConfiguration;

    private final Parser markdownParser;
    private final Formatter markdownRenderer;

    // <id, <name, content>>
    private final Map<String, Pair<String, String>> builtinTemplates = new HashMap<>();

    @Autowired
    public DetailedDesignService(ChainFinderService chainFinderService, SystemModelService systemModelService, OperationService operationService, ActionsLogService actionLogger,
                                 TemplateDataBuilder templateDataBuilder,
                                 DetailedDesignTemplateRepository designTemplateRepository,
                                 Parser markdownParser, Formatter markdownRenderer) {
        this.chainFinderService = chainFinderService;
        this.systemModelService = systemModelService;
        this.operationService = operationService;
        this.actionLogger = actionLogger;
        this.templateDataBuilder = templateDataBuilder;
        this.designTemplateRepository = designTemplateRepository;
        this.freemakerConfiguration = createFreemakerConfiguration();
        this.markdownParser = markdownParser;
        this.markdownRenderer = markdownRenderer;
    }

    @Async
    @EventListener
    public void init(ApplicationReadyEvent event) {
        log.info("Detailed design templates loading started");
        try {
            // load built-in templates from resources
            Map<String, Resource> resources = ResourceLoaderUtils.loadFiles(CLASSPATH_DDS_TEMPLATES_PATTERN);
            for (Map.Entry<String, Resource> dirPathToDescriptorFile : resources.entrySet()) {
                loadBuiltinTemplate(dirPathToDescriptorFile.getKey(), dirPathToDescriptorFile.getValue());
            }
            log.info("Detailed design templates loading finished");
        } catch (Exception e) {
            log.error("Detailed design templates loading failed", e);
        }
    }

    @Transactional
    public DDSResponse buildChainDetailedDesign(String chainId, String templateId) throws TemplateDataBuilderException, TemplateDataEscapingException {
        Chain chain = chainFinderService.findById(chainId);
        List<ChainElement> elements = chain.getElements();
        TemplateData templateData;

        templateData = templateDataBuilder.build(chain, elements);

        // template + data -> markdown
        String content = findTemplateContent(templateId)
                .orElseThrow(() -> new EntityNotFoundException("Detailed design template not found: " + templateId));
        Writer writer = new StringWriter();
        try {
            new Template(templateId, content, freemakerConfiguration).process(templateData, writer);
        } catch (Exception e) {
            log.warn("Failed to build detailed design from template '{}': {}", templateId, e.getMessage());
            throw new TemplateProcessingException("Failed to build detailed design from template '"
                                                  + templateId + "': " + e.getMessage(), e);
        }

        // additional data
        Map<String, DDSSpecificationSource> specs;
        try {
            specs = collectImplementedSpecs(elements);
        } catch (Exception e) {
            log.error("Failed to collect implemented triggers specifications for chain: {}", chainId, e);
            throw new DetailedDesignInternalException("Failed to collect implemented triggers specifications for chain: " + e.getMessage(), e);
        }

        try {
            // markdown -> formatted markdown
            Node document = markdownParser.parse(writer.toString());

            TemplateSequenceDiagram simpleSeqDiagram = templateData.getChain().getDoc().getSimpleSeqDiagram();

            return DDSResponse.builder()
                    .document(markdownRenderer.render(document))
                    .simpleSeqDiagramPlantuml(simpleSeqDiagram.getPlantuml())
                    .simpleSeqDiagramMermaid(simpleSeqDiagram.getMermaid())
                    .triggerSpecifications(specs.values().stream().toList())
                    .build();
        } catch (Exception e) {
            log.error("Failed to perform document formatting for chain: {}", chainId, e);
            throw new DetailedDesignInternalException("Failed to perform document formatting: " + e.getMessage(), e);
        }
    }

    @Transactional
    public List<DetailedDesignTemplate> findCustomTemplates() {
        return designTemplateRepository.findAll();
    }

    @Transactional
    public List<DetailedDesignTemplate> getBuiltInTemplates() {
        return (List<DetailedDesignTemplate>) builtinTemplates.entrySet().stream()
                .map(entry ->
                        DetailedDesignTemplate.builder()
                                .id(entry.getKey())
                                .name(entry.getValue().getLeft())
                                .content(entry.getValue().getRight())
                                .build())
                .toList();
    }

    @Transactional
    public DetailedDesignTemplate createOrUpdateTemplate(String name, String content) {
        if (!checkTemplateName(name)) {
            throw new IllegalArgumentException(
                    "Invalid template name format: " + name + ", must match the pattern: " + TEMPLATE_ID_PATTERN.pattern());
        }

        if (designTemplateRepository.findByName(name) != null) {
            throw new IllegalArgumentException("Template name is not unique");
        }

        String id = buildTemplateId(name);

        if (builtinTemplates.containsKey(id)) {
            throw new EntityExistsException(id);
        }

        DetailedDesignTemplate template = designTemplateRepository.save(
                DetailedDesignTemplate.builder()
                        .id(id)
                        .name(name)
                        .content(content)
                        .build());

        logChainAction(id, LogOperation.CREATE_OR_UPDATE);

        return template;
    }

    @Transactional
    public void deleteTemplates(List<String> templateIds) {
        designTemplateRepository.deleteAllById(templateIds);
        for (String templateId : templateIds) {
            logChainAction(templateId, LogOperation.DELETE);
        }
    }

    @Transactional
    public DetailedDesignTemplate getTemplate(String templateId) {
        if (builtinTemplates.containsKey(templateId)) {
            Pair<String, String> pair = builtinTemplates.get(templateId);
            return DetailedDesignTemplate.builder()
                    .id(buildTemplateId(templateId))
                    .name(pair.getLeft())
                    .content(pair.getRight())
                    .build();
        } else {
            return designTemplateRepository.findById(templateId)
                    .orElseThrow(() -> new EntityNotFoundException("Detailed design template not found: " + templateId));
        }
    }

    private Map<String, DDSSpecificationSource> collectImplementedSpecs(List<ChainElement> elements) {
        Map<String, DDSSpecificationSource> specs = new HashMap<>();

        for (ChainElement element : elements) {
            if (CamelNames.HTTP_TRIGGER_COMPONENT.equals(element.getType())
                && IntegrationSystemType.IMPLEMENTED.toString().equals(element.getPropertyAsString(CamelOptions.SYSTEM_TYPE))) {
                String operationId = element.getPropertyAsString(CamelOptions.OPERATION_ID);
                if (StringUtils.isNotEmpty(operationId)) {
                    Operation operation = operationService.getOperation(operationId);
                    SystemModel spec = operation.getSystemModel();
                    SpecificationSource src = systemModelService.getMainSystemModelSpecSource(spec.getId());

                    if (src != null && StringUtils.isNotEmpty(src.getSource())) {
                        String specificationId = element.getPropertyAsString(CamelOptions.SPECIFICATION_ID);
                        String specificationFileContent = src.getSource();
                        String specificationFileExt = FilenameUtils.getExtension(src.getName());
                        if (!specs.containsKey(specificationId)) {
                            specs.put(specificationId, DDSSpecificationSource
                                    .builder()
                                    .serviceName(spec.getSpecificationGroup().getSystem().getName())
                                    .specificationName(spec.getName())
                                    .specificationId(specificationId)
                                    .fileExtension(specificationFileExt)
                                    .specificationContent(specificationFileContent)
                                    .build());
                        }
                    }
                }
            }
        }

        return specs;
    }

    private Optional<String> findTemplateContent(String templateId) {
        return builtinTemplates.containsKey(templateId)
                ? Optional.of(builtinTemplates.get(templateId).getRight())
                : designTemplateRepository.findById(templateId).map(DetailedDesignTemplate::getContent);
    }

    private Configuration createFreemakerConfiguration() {
        Configuration configuration = new Configuration(Configuration.VERSION_2_3_33);
        // an included template resolves from the same source as the rendered one, never from a stale copy
        configuration.setTemplateLoader(new TemplateLoader() {
            @Override
            public Object findTemplateSource(String name) {
                return findTemplateContent(name).orElse(null);
            }

            @Override
            public long getLastModified(Object templateSource) {
                return -1;
            }

            @Override
            public Reader getReader(Object templateSource, String encoding) {
                return new StringReader((String) templateSource);
            }

            @Override
            public void closeTemplateSource(Object templateSource) {
                // the source is a string, nothing to release
            }
        });
        configuration.setCacheStorage(new NullCacheStorage());
        configuration.setLocalizedLookup(false);
        return configuration;
    }

    private @NotNull String buildTemplateId(String name) {
        return name.toLowerCase();
    }

    private boolean checkTemplateName(String name) {
        return TEMPLATE_ID_PATTERN.matcher(name).find();
    }

    private void loadBuiltinTemplate(String dirPath, Resource descriptorFile) {
        try {
            int start = dirPath.lastIndexOf('/', dirPath.length() - 2) + 1;
            String elementName = dirPath.substring(start);
            if (log.isDebugEnabled()) {
                log.debug("Processing element directory: {}", dirPath);
            }

            if (descriptorFile != null) {
                String name = descriptorFile.getFilename();
                name = name.substring(0, name.length() - 4);
                log.debug("Loading detailed design template: '{}'", name);

                String id = buildTemplateId(name);
                String content = IOUtils.toString(descriptorFile.getInputStream(), StandardCharsets.UTF_8);
                builtinTemplates.put(id, Pair.of(name, content));
            } else {
                log.warn("Descriptor file is missing for {}, skipping", elementName);
            }
        } catch (IOException e) {
            log.error("Error loading element descriptors", e);
            throw new RuntimeException(e);
        }
    }

    private void logChainAction(String templateId, LogOperation operation) {
        actionLogger.logAction(ActionLog.builder()
                .id(templateId)
                .entityName(templateId)
                .entityType(EntityType.DETAILED_DESIGN_TEMPLATE)
                .operation(operation)
                .build());
    }
}
