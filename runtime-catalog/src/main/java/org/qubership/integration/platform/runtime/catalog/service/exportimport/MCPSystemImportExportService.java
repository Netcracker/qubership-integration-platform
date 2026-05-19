package org.qubership.integration.platform.runtime.catalog.service.exportimport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ServicesNotFoundException;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.chain.ImportSystemsAndInstructionsResult;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.IgnoreResult;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.ImportInstructionAction;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.ImportInstructionsConfig;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.ImportSystemResult;
import org.qubership.integration.platform.runtime.catalog.model.system.exportimport.ExportedSystemObject;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.mcp.MCPSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.AbstractSystemEntity;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.imports.ImportSystemStatus;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.imports.remote.SystemCompareAction;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.exportimport.ImportMode;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.exportimport.system.SystemsCommitRequest;
import org.qubership.integration.platform.runtime.catalog.service.ActionsLogService;
import org.qubership.integration.platform.runtime.catalog.service.MCPSystemService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.deserializer.MCPSystemDeserializer;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.instructions.ImportInstructionsService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer.ArchiveWriter;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer.MCPSystemSerializer;
import org.qubership.integration.platform.runtime.catalog.util.ExportImportUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.CollectionUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Objects.isNull;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.MCP_SERVICE_YAML_NAME_POSTFIX;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.ZIP_EXTENSION;
import static org.qubership.integration.platform.runtime.catalog.util.ExportImportUtils.*;
import static org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED;

@Slf4j
@Service
@Transactional
public class MCPSystemImportExportService {
    private final TransactionTemplate transactionTemplate;
    private final YAMLMapper yamlMapper;
    private final MCPSystemService mcpSystemService;
    private final ActionsLogService actionLogger;
    private final MCPSystemSerializer mcpSystemSerializer;
    private final MCPSystemDeserializer mcpSystemDeserializer;
    private final ArchiveWriter archiveWriter;
    private final ImportInstructionsService importInstructionsService;
    private final ImportSessionService importProgressService;
    private final URI mcpServiceSchemaUri;

    @Autowired
    public MCPSystemImportExportService(
            TransactionTemplate transactionTemplate,
            @Qualifier("yamlExportImportMapper") YAMLMapper yamlMapper,
            MCPSystemService mcpSystemService,
            ActionsLogService actionLogger,
            MCPSystemSerializer mcpSystemSerializer,
            MCPSystemDeserializer mcpSystemDeserializer,
            ArchiveWriter archiveWriter,
            ImportInstructionsService importInstructionsService,
            ImportSessionService importProgressService,
            @Value("${qip.json.schemas.mcp-service:http://qubership.org/schemas/product/qip/mcp-service}") URI mcpServiceSchemaUri
    ) {
        this.transactionTemplate = transactionTemplate;
        this.yamlMapper = yamlMapper;
        this.mcpSystemService = mcpSystemService;
        this.actionLogger = actionLogger;
        this.mcpSystemSerializer = mcpSystemSerializer;
        this.mcpSystemDeserializer = mcpSystemDeserializer;
        this.archiveWriter = archiveWriter;
        this.importInstructionsService = importInstructionsService;
        this.importProgressService = importProgressService;
        this.mcpServiceSchemaUri = mcpServiceSchemaUri;
    }

    public byte[] export(List<String> ids) {
        List<MCPSystem> systems = isNull(ids)
                ? mcpSystemService.findAll()
                : mcpSystemService.findAllById(ids);
        if (systems.isEmpty()) {
            return null;
        }

        List<ExportedSystemObject> exportedSystems = exportSystems(systems);
        byte[] data = archiveWriter.writeArchive(exportedSystems);
        systems.forEach(this::logExport);
        return data;
    }

    private List<ExportedSystemObject> exportSystems(List<MCPSystem> systems) {
        return systems.stream().map(this::exportSystem).toList();
    }

    private ExportedSystemObject exportSystem(MCPSystem system) {
        try {
            return mcpSystemSerializer.serialize(system);
        } catch (JsonProcessingException e) {
            String message = String.format("Failed to export system: %s (%s)",
                    system.getName(), system.getId());
            throw new RuntimeException(message, e);
        }
    }

    @Transactional(propagation = NOT_SUPPORTED)
    public List<ImportSystemResult> importSystems(
            MultipartFile file,
            List<String> ids
    ) {
        List<ImportSystemResult> response = new ArrayList<>();
        String fileExtension = FilenameUtils.getExtension(file.getOriginalFilename());
        logArchiveImport(file.getOriginalFilename());
        if (ZIP_EXTENSION.equalsIgnoreCase(fileExtension)) {
            String exportDirectory = Paths.get(FileUtils.getTempDirectory().getAbsolutePath(),
                    UUID.randomUUID().toString()).toString();
            List<File> extractedSystemFiles;

            try (InputStream fs = file.getInputStream()) {
                extractedSystemFiles = extractSystemsFromZip(fs, exportDirectory, MCP_SERVICE_YAML_NAME_POSTFIX);
            } catch (IOException e) {
                deleteFile(exportDirectory);
                throw new RuntimeException("Unexpected error while archive unpacking: " + e.getMessage(), e);
            } catch (RuntimeException e) {
                deleteFile(exportDirectory);
                throw e;
            }

            Set<String> servicesToImport = importInstructionsService.performServiceIgnoreInstructions(
                    extractedSystemFiles.stream()
                            .map(ExportImportUtils::extractSystemIdFromFileName)
                            .collect(Collectors.toSet()),
                    false)
                    .idsToImport();
            for (File singleSystemFile : extractedSystemFiles) {
                String serviceId = extractSystemIdFromFileName(singleSystemFile);
                if (!servicesToImport.contains(serviceId)) {
                    addIgnoredServiceResult(response, serviceId);
                    continue;
                }

                ImportSystemResult result = importOneSystemInTransaction(singleSystemFile, ids);
                if (result != null) {
                    response.add(result);
                }
            }

            deleteFile(exportDirectory);
        } else {
            throw new RuntimeException("Unsupported file extension: " + fileExtension);
        }

        return response;
    }

    @Transactional(propagation = NOT_SUPPORTED)
    public ImportSystemsAndInstructionsResult importSystems(
            File importDirectory,
            SystemsCommitRequest systemCommitRequest,
            String importId
    ) {
        if (systemCommitRequest.getImportMode() == ImportMode.NONE) {
            return new ImportSystemsAndInstructionsResult();
        }

        List<File> systemsFiles = extractMcpServiceFilesFromDirectory(importDirectory);

        List<String> systemIds = systemCommitRequest.getImportMode() == ImportMode.FULL
                ? Collections.emptyList()
                : systemCommitRequest.getSystemIds();

        IgnoreResult ignoreResult = importInstructionsService.performServiceIgnoreInstructions(
                systemsFiles.stream()
                        .map(ExportImportUtils::extractSystemIdFromFileName)
                        .collect(Collectors.toSet()),
                true);
        int total = systemsFiles.size();
        int counter = 0;
        List<ImportSystemResult> response = new ArrayList<>();
        for (File systemFile : systemsFiles) {
            String serviceId = extractSystemIdFromFileName(systemFile);
            if (!ignoreResult.idsToImport().contains(serviceId)) {
                addIgnoredServiceResult(response, serviceId);
                continue;
            }

            importProgressService.calculateImportStatus(
                    importId, total, counter, ImportSessionService.COMMON_VARIABLES_IMPORT_PERCENTAGE_THRESHOLD,
                    ImportSessionService.SERVICE_IMPORT_PERCENTAGE_THRESHOLD);
            counter++;

            ImportSystemResult result = importOneSystemInTransaction(systemFile, systemIds);

            if (result != null) {
                response.add(result);
            }
        }

        return new ImportSystemsAndInstructionsResult(response, ignoreResult.importInstructionResults());
    }

    public List<ImportSystemResult> getImportPreview(MultipartFile file) {
        List<ImportSystemResult> result = new ArrayList<>();
        String fileExtension = FilenameUtils.getExtension(file.getOriginalFilename());
        if (ZIP_EXTENSION.equalsIgnoreCase(fileExtension)) {
            String dataDirectory = Paths.get(FileUtils.getTempDirectory().getAbsolutePath(),
                    UUID.randomUUID().toString()).toString();
            List<File> extractedSystemFiles = new ArrayList<>();

            try (InputStream fs = file.getInputStream()) {
                extractedSystemFiles = extractSystemsFromZip(fs, dataDirectory, MCP_SERVICE_YAML_NAME_POSTFIX);
            } catch (ServicesNotFoundException e) {
                deleteFile(dataDirectory);
            } catch (IOException e) {
                deleteFile(dataDirectory);
                throw new RuntimeException("Unexpected error while archive unpacking: " + e.getMessage(), e);
            } catch (RuntimeException e) {
                deleteFile(dataDirectory);
                throw e;
            }

            ImportInstructionsConfig instructionsConfig = importInstructionsService
                    .getServiceImportInstructionsConfig(Set.of(ImportInstructionAction.IGNORE));
            for (File singleSystemFile : extractedSystemFiles) {
                result.add(getSystemChanges(singleSystemFile, instructionsConfig));
            }
            deleteFile(dataDirectory);
        } else {
            throw new RuntimeException("Unsupported file extension: " + fileExtension);
        }

        return result;
    }

    public List<ImportSystemResult> getImportPreview(
            File importDirectory,
            ImportInstructionsConfig instructionsConfig
    ) {
        List<File> contextServiceFiles = extractMcpServiceFilesFromDirectory(importDirectory);

        List<ImportSystemResult> importSystemResults = new ArrayList<>();
        for (File systemFile : contextServiceFiles) {
            importSystemResults.add(getSystemChanges(systemFile, instructionsConfig));
        }

        return importSystemResults;
    }

    private void addIgnoredServiceResult(List<ImportSystemResult> response, String serviceId) {
        response.add(ImportSystemResult.builder()
                .id(serviceId)
                .name(serviceId)
                .status(ImportSystemStatus.IGNORED)
                .build());
        log.info("Service {} ignored as a part of import exclusion list", serviceId);
    }

    private List<File> extractMcpServiceFilesFromDirectory(File importDirectory) {
        List<File> systemsFiles;
        try {
            systemsFiles = extractSystemsFromImportDirectory(importDirectory.getAbsolutePath(),
                    MCP_SERVICE_YAML_NAME_POSTFIX);
        } catch (IOException e) {
            throw new RuntimeException("Error while extracting MCP service files", e);
        }
        return systemsFiles.stream()
                .filter(this::isMCPServiceFile)
                .collect(Collectors.toList());
    }

    private ObjectNode getFileNode(File file) throws IOException {
        return (ObjectNode) yamlMapper.readTree(file);
    }

    private boolean isMCPServiceFile(File file) {
        try {
            ObjectNode node = getFileNode(file);
            JsonNode schemaNode = node.get("$schema");
            if (schemaNode != null && schemaNode.isTextual()) {
                String fileSchema = schemaNode.asText();
                return mcpServiceSchemaUri.toString().equals(fileSchema);
            }
            return false;
        } catch (Exception e) {
            log.warn("Failed to check schema for file {}: {}", file.getName(), e.getMessage());
            return false;
        }
    }

    private ImportSystemResult getSystemChanges(File mainSystemFile, ImportInstructionsConfig instructionsConfig) {
        ImportSystemResult resultSystemCompareDTO;

        String systemId = null;
        String systemName = null;

        try {
            ObjectNode serviceNode = getFileNode(mainSystemFile);
            MCPSystem baseSystem = getBaseSystemDeserializationResult(serviceNode);
            systemId = baseSystem.getId();
            systemName = baseSystem.getName();
            Long systemModifiedWhen = baseSystem.getModifiedWhen() != null ? baseSystem.getModifiedWhen().getTime() : 0;
            ImportInstructionAction instructionAction = instructionsConfig.getIgnore().contains(systemId)
                    ? ImportInstructionAction.IGNORE
                    : null;

            resultSystemCompareDTO = ImportSystemResult.builder()
                    .id(systemId)
                    .modified(systemModifiedWhen)
                    .instructionAction(instructionAction)
                    .build();
            setCompareSystemResult(baseSystem, resultSystemCompareDTO);
        } catch (RuntimeException | IOException e) {
            log.error("Exception while system compare: ", e);
            resultSystemCompareDTO = ImportSystemResult.builder()
                    .id(systemId)
                    .name(systemName)
                    .requiredAction(SystemCompareAction.ERROR)
                    .message("Exception while system compare: " + e.getMessage())
                    .build();
        }
        return resultSystemCompareDTO;
    }

    private MCPSystem getBaseSystemDeserializationResult(JsonNode serviceNode)
            throws JsonProcessingException {
        MCPSystem result = new MCPSystem();

        JsonNode idNode = serviceNode.get(AbstractSystemEntity.Fields.id);
        String systemId = (idNode == null || idNode.isNull()) ? null : idNode.asText();
        if (systemId == null) {
            throw new RuntimeException("Missing id field in system file");
        }

        JsonNode nameNode = serviceNode.get(AbstractSystemEntity.Fields.name);
        String systemName = (nameNode == null || nameNode.isNull()) ? "" : nameNode.asText();

        Timestamp modifiedWhen = serviceNode.get(AbstractSystemEntity.Fields.modifiedWhen) != null
                ? new Timestamp(serviceNode.get(AbstractSystemEntity.Fields.modifiedWhen).asLong())
                : null;

        result.setId(systemId);
        result.setName(systemName);
        result.setModifiedWhen(modifiedWhen);

        return result;
    }

    private void setCompareSystemResult(MCPSystem system, ImportSystemResult result) {
        mcpSystemService.findById(system.getId()).ifPresentOrElse(
                oldSystem -> {
                    result.setName(oldSystem.getName());
                    result.setRequiredAction(SystemCompareAction.UPDATE);
                },
                () -> {
                    result.setName(system.getName());
                    result.setRequiredAction(SystemCompareAction.CREATE);
                }
        );
    }

    private synchronized ImportSystemResult importOneSystemInTransaction(
            File mainServiceFile,
            List<String> systemIds
    ) {
        ImportSystemResult result;
        Optional<MCPSystem> baseSystemOptional = Optional.empty();

        try {
            ObjectNode serviceNode = getFileNode(mainServiceFile);
            MCPSystem system = getBaseSystemDeserializationResult(serviceNode);
            baseSystemOptional = Optional.of(system);
            if (!CollectionUtils.isEmpty(systemIds) && !systemIds.contains(system.getId())) {
                return null;
            }
            result = transactionTemplate.execute((status) -> {
                MCPSystem mcpSystem = system;
                try {
                    mcpSystem = mcpSystemDeserializer.deserialize(mainServiceFile);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }

                ImportSystemStatus importStatus = enrichAndSaveSystem(mcpSystem);
                return ImportSystemResult.builder()
                        .id(mcpSystem.getId())
                        .name(mcpSystem.getName())
                        .status(importStatus)
                        .build();
            });
        } catch (Exception e) {
            result = ImportSystemResult.builder()
                    .id(baseSystemOptional.map(MCPSystem::getId).orElse(null))
                    .name(baseSystemOptional.map(MCPSystem::getName).orElse(""))
                    .status(ImportSystemStatus.ERROR)
                    .message(e.getMessage())
                    .build();
            log.warn("Exception when importing MCP system {} ({})", result.getName(), result.getId(), e);
        }
        return result;
    }

    private ImportSystemStatus enrichAndSaveSystem(MCPSystem system) {
        Optional<MCPSystem> oldSystem = mcpSystemService.findById(system.getId());

        if (oldSystem.isPresent()) {
            mcpSystemService.updateLabels(system, oldSystem.get().getLabels(),  system.getLabels());
            system.setLabels(oldSystem.get().getLabels());
            mcpSystemService.update(system);
            return ImportSystemStatus.UPDATED;
        } else {
            mcpSystemService.create(system, true);
            return ImportSystemStatus.CREATED;
        }
    }

    private void logExport(MCPSystem system) {
        actionLogger.logAction(ActionLog.builder()
                .entityType(EntityType.MCP_SYSTEM)
                .entityId(system.getId())
                .entityName(system.getName())
                .operation(LogOperation.EXPORT)
                .build()
        );
    }

    private void logArchiveImport(String archiveName) {
        actionLogger.logAction(ActionLog.builder()
                .entityType(EntityType.SERVICES)
                .entityId(null)
                .entityName(archiveName)
                .operation(LogOperation.IMPORT)
                .build());
    }
}
