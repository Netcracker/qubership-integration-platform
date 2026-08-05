package org.qubership.integration.platform.runtime.catalog.service.exportimport;

import org.qubership.integration.platform.runtime.catalog.configuration.ApplicationJsonSchemaProperties;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.EXTERNAL_SERVICE_YAML_NAME_POSTFIX;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.IMPLEMENTED_SERVICE_YAML_NAME_POSTFIX;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.INTERNAL_SERVICE_YAML_NAME_POSTFIX;

/**
 * The one place that knows how a service type is spelled in a file name and in a {@code $schema}. Import discovery,
 * type resolution, the exporter, and the V105 pair all read it from here, so the three spellings cannot drift apart.
 */
@Component
public class ServiceTypeFiles {

    private static final Map<IntegrationSystemType, String> POSTFIXES_BY_TYPE = new EnumMap<>(Map.of(
            IntegrationSystemType.EXTERNAL, EXTERNAL_SERVICE_YAML_NAME_POSTFIX,
            IntegrationSystemType.INTERNAL, INTERNAL_SERVICE_YAML_NAME_POSTFIX,
            IntegrationSystemType.IMPLEMENTED, IMPLEMENTED_SERVICE_YAML_NAME_POSTFIX));

    private final Map<IntegrationSystemType, String> schemaUrisByType;

    @Autowired
    public ServiceTypeFiles(ApplicationJsonSchemaProperties schemas) {
        this.schemaUrisByType = new EnumMap<>(Map.of(
                IntegrationSystemType.EXTERNAL, schemas.getExternalService(),
                IntegrationSystemType.INTERNAL, schemas.getInternalService(),
                IntegrationSystemType.IMPLEMENTED, schemas.getImplementedService()));
    }

    /**
     * The name postfix an export writes for {@code type}, and the one import resolves the type back from. Static like
     * {@link #postfixes()}: the postfixes are compile-time constants, and {@code ExportImportUtils} builds the export
     * file name from a static context.
     */
    public static String postfix(IntegrationSystemType type) {
        return POSTFIXES_BY_TYPE.get(Objects.requireNonNull(type, "service type"));
    }

    /** Every per-type postfix, for the import-side directory scan. */
    public static Collection<String> postfixes() {
        return List.copyOf(POSTFIXES_BY_TYPE.values());
    }

    /** The {@code $schema} an export stamps on a service of {@code type}. */
    public String schemaUri(IntegrationSystemType type) {
        return schemaUrisByType.get(Objects.requireNonNull(type, "service type"));
    }

    /**
     * The type a service file name states, or empty when it states none — the legacy {@code service-<id>.yaml} name
     * and the pre-#553 {@code .service.} postfix both carry the type in the document instead. A context or MCP service
     * name matches nothing here: neither contains a per-type postfix. A name carrying two postfixes states no single
     * type either, so it resolves to none rather than to whichever the enum happens to declare first.
     *
     * <p>Static like {@link #postfix(IntegrationSystemType)}: the postfixes are compile-time constants, and only the
     * schema URIs come from configuration.
     */
    public static Optional<IntegrationSystemType> typeFromFileName(String fileName) {
        if (fileName == null) {
            return Optional.empty();
        }
        List<IntegrationSystemType> stated = POSTFIXES_BY_TYPE.entrySet().stream()
                .filter(entry -> fileName.contains(entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();
        return stated.size() == 1 ? Optional.of(stated.get(0)) : Optional.empty();
    }

    /**
     * The type a per-type {@code $schema} states, for the revert migration, which works on a document with no file
     * name to read. Do not use this on the import path: the VS Code extension stamps whatever a project's
     * {@code .config.qip.yaml} configures (see {@code vscode-extension/.config.qip.yaml.example}), so an incoming
     * {@code $schema} is an arbitrary string and identifies nothing. The file name is the import-side source.
     */
    public Optional<IntegrationSystemType> typeFromSchemaUri(String schemaUri) {
        if (schemaUri == null) {
            return Optional.empty();
        }
        return schemaUrisByType.entrySet().stream()
                .filter(entry -> entry.getValue().equals(schemaUri))
                .map(Map.Entry::getKey)
                .findFirst();
    }
}
