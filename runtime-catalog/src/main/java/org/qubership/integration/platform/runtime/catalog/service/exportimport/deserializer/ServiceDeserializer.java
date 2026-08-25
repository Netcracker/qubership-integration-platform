package org.qubership.integration.platform.runtime.catalog.service.exportimport.deserializer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.chain.model.ImportSpecificationGroup;
import org.qubership.integration.platform.chain.model.ImportSpecificationSource;
import org.qubership.integration.platform.chain.model.ImportSystem;
import org.qubership.integration.platform.chain.model.ImportSystemModel;
import org.qubership.integration.platform.io.readers.system.IntegrationSystemReader;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ServiceImportException;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.ApiGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ServiceTypeFiles;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.ApiGroupDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.IntegrationSystemDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.SystemEntitySeam;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.SystemModelDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.extractor.OperationSchemaExtractor;
import org.qubership.integration.platform.runtime.catalog.service.extractor.OperationSchemaExtractor.ExtractedSchemas;
import org.qubership.integration.platform.runtime.catalog.service.extractor.OperationSchemaExtractor.OperationKey;
import org.qubership.integration.platform.runtime.catalog.util.ExportImportUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.CONTEXT_SERVICE_YAML_NAME_POSTFIX;
import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.MCP_SERVICE_YAML_NAME_POSTFIX;

/**
 * Turns an exported service archive into catalog entities.
 *
 * <p>The library {@link IntegrationSystemReader} does the reading: it locates the group and model files beside the
 * service document, runs the import file migrations, and loads each specification source. Two things stay here
 * because they are the catalog's own rules: the service type, which the document states through its {@code $schema}
 * and which no library model carries, and the operation specification slice, which import re-derives from the source
 * for a document that no longer stores it.
 */
@Slf4j
@Component
public class ServiceDeserializer {

    private final YAMLMapper yamlMapper;
    private final IntegrationSystemReader integrationSystemReader;
    private final IntegrationSystemDtoMapper integrationSystemDtoMapper;
    private final ApiGroupDtoMapper apiGroupDtoMapper;
    private final SystemModelDtoMapper systemModelDtoMapper;
    private final OperationSchemaExtractor operationSchemaExtractor;
    private final ServiceTypeFiles serviceTypeFiles;

    @Autowired
    public ServiceDeserializer(
            @Qualifier("defaultYamlMapper") YAMLMapper yamlMapper,
            IntegrationSystemReader integrationSystemReader,
            IntegrationSystemDtoMapper integrationSystemDtoMapper,
            ApiGroupDtoMapper apiGroupDtoMapper,
            SystemModelDtoMapper systemModelDtoMapper,
            OperationSchemaExtractor operationSchemaExtractor,
            ServiceTypeFiles serviceTypeFiles
    ) {
        this.yamlMapper = yamlMapper;
        this.integrationSystemReader = integrationSystemReader;
        this.integrationSystemDtoMapper = integrationSystemDtoMapper;
        this.apiGroupDtoMapper = apiGroupDtoMapper;
        this.systemModelDtoMapper = systemModelDtoMapper;
        this.operationSchemaExtractor = operationSchemaExtractor;
        this.serviceTypeFiles = serviceTypeFiles;
    }

    public IntegrationSystem deserializeSystem(File serviceFile) {
        try {
            ImportSystem importSystem = integrationSystemReader.read(serviceFile);
            IntegrationSystem integrationSystem = toPersistenceEntity(importSystem);

            // Read off the file rather than the model: $schema is what states the type, and no library model
            // carries it. The raw document is used on purpose - migrations never touch $schema.
            resolveServiceType(integrationSystem, serviceFile, yamlMapper.readTree(serviceFile));

            OperationProtocol protocol = integrationSystem.getProtocol();
            for (ApiGroup apiGroup : integrationSystem.getApiGroups()) {
                for (SystemModel systemModel : apiGroup.getSystemModels()) {
                    fillMissingOperationSpecifications(systemModel, protocol);
                    reportMissingSources(systemModel);
                }
            }
            return integrationSystem;
        } catch (ServiceImportException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private IntegrationSystem toPersistenceEntity(ImportSystem importSystem) {
        IntegrationSystem integrationSystem = integrationSystemDtoMapper.toInternalEntity(importSystem);
        for (ImportSpecificationGroup importGroup : importSystem.getSpecificationGroups()) {
            ApiGroup apiGroup = apiGroupDtoMapper.toInternalEntity(importGroup);
            integrationSystem.addApiGroup(apiGroup);
            for (ImportSystemModel importModel : importGroup.getSystemModels()) {
                SystemModel systemModel = systemModelDtoMapper.toInternalEntity(importModel);
                apiGroup.addSystemModel(systemModel);
                for (ImportSpecificationSource importSource : importModel.getSpecificationSources()) {
                    SpecificationSource specificationSource =
                            SystemEntitySeam.toPersistenceSpecificationSource(importSource, importSource.getSource());
                    systemModel.addProvidedSpecificationSource(specificationSource);
                }
            }
        }
        return integrationSystem;
    }

    /**
     * Resolves the service type from {@code $schema}, falling back to {@code content.integrationSystemType}. The
     * import preview calls it as well, so a document the commit path will refuse becomes an error row before the user
     * commits.
     *
     * <p>{@code $schema} is the primary source because it is the only one a current-format file carries. The field
     * stays as a fallback for the legacy flat {@code service-<id>.yaml} name and for every archive written before the
     * per-type schemas existed. The <b>file name</b> is not consulted at all: a name has stated a type only between
     * #553 and this version, and such a file states the same type in its {@code $schema} anyway.
     *
     * <p>The document is the one read off disk, before any migration runs. No forward migration rewrites
     * {@code $schema}, and reading it here keeps the answer the same on both call sites.
     *
     * <p>A type missing from both sources fails the import instead of persisting a null. The column is nullable, and a
     * null surfaces much later as an NPE in {@code EntityType.getSystemType}.
     */
    public void resolveServiceType(IntegrationSystem system, File serviceFile, JsonNode document) {
        String fileName = serviceFile.getName();
        IntegrationSystemType fromSchema = serviceTypeFiles.typeFromDocumentSchema(document).orElse(null);
        // Both sources read off the one document. Taking the field from the entity instead left half the rule
        // outside this method, and the preview path had to prime the entity by hand to reassemble it.
        IntegrationSystemType fromDocument = ServiceTypeFiles.typeFromDocument(document).orElse(null);

        if (fromSchema == null && fromDocument == null) {
            throw new ServiceImportException(system.getId(), system.getName(),
                    ("Service file %s states no service type: $schema is absent or states none of the service schemas,"
                            + " and content.integrationSystemType is absent. Set $schema to one of %s, or set"
                            + " content.integrationSystemType, then re-import. The service is not imported.%s")
                            .formatted(fileName, String.join(", ", serviceTypeFiles.plainServiceSchemaUris()),
                                    otherKindHint(fileName)));
        }
        if (fromSchema != null && fromDocument != null && fromSchema != fromDocument) {
            throw new ServiceImportException(system.getId(), system.getName(),
                    ("Service file %s states type %s in its $schema and %s in content.integrationSystemType. Correct"
                            + " one of the two so they agree, then re-import. The service is not imported.")
                            .formatted(fileName, fromSchema, fromDocument));
        }
        system.setIntegrationSystemType(fromSchema != null ? fromSchema : fromDocument);
    }

    /**
     * The one name shape a plain-service scan cannot claim on its own. {@code service-ctx.context-service.qip.yaml} is
     * both the flat name of {@code ctx.context-service.qip} and the context name of {@code service-ctx}, so the plain
     * scan discovers it too. A file whose document confirms the other kind never reaches here — the plain import
     * leaves it to the import that has it — so what is left is a name reading as one kind and a document stating
     * neither. Say which import the file may belong to, or the row reads as a lost service.
     */
    private static String otherKindHint(String fileName) {
        if (ExportImportUtils.statesPostfix(fileName, CONTEXT_SERVICE_YAML_NAME_POSTFIX)) {
            return " The name also reads as a context service file, which the context service import handles.";
        }
        if (ExportImportUtils.statesPostfix(fileName, MCP_SERVICE_YAML_NAME_POSTFIX)) {
            return " The name also reads as an MCP service file, which the MCP service import handles.";
        }
        return "";
    }

    /**
     * Repopulates the operation {@code specification} column, which the api format no longer carries in the file. The
     * value is re-derived from the raw source the archive ships, so the async MaaS classifier the resolvers store there
     * survives the round trip.
     *
     * <p>An operation that arrived with its own value keeps it: legacy files still carry the field, and the file is
     * authoritative over anything re-derived. A protocol with no schema extraction, a model with no source and a parse
     * failure all leave the column null rather than failing the import.
     */
    private void fillMissingOperationSpecifications(SystemModel systemModel, OperationProtocol protocol) {
        List<Operation> operations = systemModel.getOperations();
        List<SpecificationSource> sources = systemModel.getSpecificationSources();
        if (protocol == null || operations == null || sources == null || sources.isEmpty()
                || operations.stream().allMatch(operation -> operation.getSpecification() != null)) {
            return;
        }
        Map<OperationKey, ExtractedSchemas> schemasByOperation;
        try {
            // withSchemas = false: only the specification slice is read here, and materializing request and response
            // schemas inlines every referenced component into every operation, inside the import transaction.
            schemasByOperation = operationSchemaExtractor.extractAll(sources, protocol, false);
        } catch (RuntimeException exception) {
            // The parsers wrap everything as SpecificationImportException with one fixed message, so only the cause
            // says what actually broke.
            log.warn("Cannot derive operation specifications for imported model {}", systemModel.getId(), exception);
            return;
        }
        List<OperationKey> unmatched = new ArrayList<>();
        int missing = 0;
        for (Operation operation : operations) {
            if (operation.getSpecification() != null) {
                continue;
            }
            missing++;
            OperationKey key = OperationKey.of(operation.getPath(), operation.getMethod());
            ExtractedSchemas schemas = schemasByOperation.get(key);
            if (schemas == null || schemas.specification() == null) {
                unmatched.add(key);
                continue;
            }
            operation.setSpecification(schemas.specification());
        }
        if (!unmatched.isEmpty()) {
            log.warn("Import of specification {}: {} of {} operations did not match the parsed source"
                            + " and keep a null specification. Unmatched operations: {}",
                    systemModel.getId(), unmatched.size(), missing, OperationSchemaExtractor.describeKeys(unmatched));
        }
    }

    /**
     * A missing file among several sources is a warning the reader already logs. A model whose every declared source
     * is missing is a different matter: it would export an empty {@code specifications} list that the api schema
     * rejects, so the import refuses it rather than storing a model nothing can rebuild.
     */
    private void reportMissingSources(SystemModel systemModel) {
        List<SpecificationSource> sources = systemModel.getSpecificationSources();
        if (sources == null || sources.isEmpty()) {
            return;
        }
        long missing = sources.stream().filter(source -> source.getSource() == null).count();
        if (missing == sources.size()) {
            throw new ServiceImportException(systemModel.getId(), systemModel.getName(),
                    ("Specification model %s declares %d source file(s), but none was found on disk. The model has no "
                            + "source to import and cannot produce a valid api export. Restore the missing source files "
                            + "or remove the model, then re-import.").formatted(systemModel.getId(), sources.size()));
        }
    }
}
