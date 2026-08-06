package org.qubership.integration.platform.runtime.catalog.service.exportimport;

import com.fasterxml.jackson.databind.JsonNode;
import org.qubership.integration.platform.runtime.catalog.configuration.ApplicationJsonSchemaProperties;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.util.ExportImportUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.CONTENT;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.CONTEXT_SERVICE_YAML_NAME_POSTFIX;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.EXTERNAL_SERVICE_YAML_NAME_POSTFIX;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.IMPLEMENTED_SERVICE_YAML_NAME_POSTFIX;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.INTEGRATION_SYSTEM_TYPE;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.INTERNAL_SERVICE_YAML_NAME_POSTFIX;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.MCP_SERVICE_YAML_NAME_POSTFIX;

/**
 * The one place that knows how a service type is spelled: in a file name, in a {@code $schema}, and in a document.
 * Import discovery, type resolution, the exporter, and the V105 pair all read it from here, so the spellings cannot
 * drift apart. It also recognizes the two kinds whose name states no type at all, the context and the MCP service,
 * because the plain-service import has to tell their files from its own.
 */
@Component
public class ServiceTypeFiles {

    private static final String SCHEMA = "$schema";

    private static final Map<IntegrationSystemType, String> POSTFIXES_BY_TYPE = new EnumMap<>(Map.of(
            IntegrationSystemType.EXTERNAL, EXTERNAL_SERVICE_YAML_NAME_POSTFIX,
            IntegrationSystemType.INTERNAL, INTERNAL_SERVICE_YAML_NAME_POSTFIX,
            IntegrationSystemType.IMPLEMENTED, IMPLEMENTED_SERVICE_YAML_NAME_POSTFIX));

    /** The postfixes of the two kinds whose name states no type: the context and the MCP service. */
    private static final List<String> TYPELESS_POSTFIXES =
            List.of(CONTEXT_SERVICE_YAML_NAME_POSTFIX, MCP_SERVICE_YAML_NAME_POSTFIX);

    private final Map<IntegrationSystemType, String> schemaUrisByType;
    private final Map<String, String> schemaUrisByTypelessPostfix;

    @Autowired
    public ServiceTypeFiles(ApplicationJsonSchemaProperties schemas) {
        this.schemaUrisByType = new EnumMap<>(Map.of(
                IntegrationSystemType.EXTERNAL, schemas.getExternalService(),
                IntegrationSystemType.INTERNAL, schemas.getInternalService(),
                IntegrationSystemType.IMPLEMENTED, schemas.getImplementedService()));
        this.schemaUrisByTypelessPostfix = Map.of(
                CONTEXT_SERVICE_YAML_NAME_POSTFIX, schemas.getContextService(),
                MCP_SERVICE_YAML_NAME_POSTFIX, schemas.getMcpService());
    }

    /** The name postfix an export writes for {@code type}, and the one import resolves the type back from. */
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
     * The type a service file name states, or empty when it states none. The legacy {@code service-<id>.yaml} name and
     * the plain {@code .service.} postfix both carry the type in the document instead, and a context or MCP name
     * carries no per-type postfix at all.
     *
     * <p>A current-format name is read only where an export writes the type, right after the id, so an id or an app
     * prefix that merely contains a postfix states nothing. No two postfixes can start there, which is why the enum
     * order never decides the answer. A name stating a postfix there is current-format even when its id wears the
     * legacy flat prefix, which autodiscovery mints routinely; only a name stating none of them is the flat one, and
     * that one states no type.
     */
    public static Optional<IntegrationSystemType> typeFromFileName(String fileName) {
        if (fileName == null || ExportImportUtils.isLegacyFlatServiceName(fileName)) {
            return Optional.empty();
        }
        return POSTFIXES_BY_TYPE.entrySet().stream()
                .filter(entry -> ExportImportUtils.statesPostfix(fileName, entry.getValue()))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    /**
     * The type a raw document states, read before any migration runs, so both places have to be tried: the current
     * format keeps the field under {@code content}, the legacy flat format at the root. A value no longer in the enum
     * reads as no type, and the caller falls back to whatever else states one.
     */
    public static Optional<IntegrationSystemType> typeFromDocument(JsonNode document) {
        if (document == null) {
            return Optional.empty();
        }
        JsonNode stated = document.path(CONTENT).path(INTEGRATION_SYSTEM_TYPE);
        if (stated.isMissingNode()) {
            stated = document.path(INTEGRATION_SYSTEM_TYPE);
        }
        if (!stated.isTextual()) {
            return Optional.empty();
        }
        try {
            return Optional.of(IntegrationSystemType.valueOf(stated.asText()));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    /**
     * Whether the name is one a context or an MCP export writes. Only such a name can belong to another import, so it
     * is the only one whose document has to be read at all; a caller holding just a name can leave every other file to
     * the plain-service import unread.
     */
    public static boolean statesContextOrMCPPostfix(String fileName) {
        return fileName != null
                && TYPELESS_POSTFIXES.stream().anyMatch(postfix -> ExportImportUtils.statesPostfix(fileName, postfix));
    }

    /**
     * Whether the file is the context or the MCP service file another import already has: its name states that kind's
     * postfix, and its document says it is that kind. Both halves are needed. The name alone is the one shape two
     * scans claim — {@code service-ctx.context-service.qip.yaml} is the context file of {@code service-ctx} and the
     * legacy flat plain-service file of {@code ctx.context-service.qip} — and the document alone says nothing about
     * which scan found it.
     *
     * <p>The name is weighed first, so a caller that parses the document only for this answer can skip the parse
     * whenever {@link #statesContextOrMCPPostfix} is false.
     *
     * <p>The document is read exactly as {@code ContextExportImportService} and {@code MCPSystemImportExportService}
     * read it, by {@code $schema} against the same configured URI, so this answers true only for a file those imports
     * really take. A {@code $schema} they do not recognize leaves the file to the plain-service import, which is where
     * an unclaimed file belongs.
     *
     * <p>This does not walk back the rule that {@code $schema} never states a service type on import. The question
     * here is which kind of document this is, not which of the three plain types it is, and it is asked only about a
     * name a typeless kind's export writes.
     */
    public boolean isContextOrMCPServiceFile(String fileName, JsonNode document) {
        if (!statesContextOrMCPPostfix(fileName) || document == null) {
            return false;
        }
        JsonNode schema = document.path(SCHEMA);
        return schema.isTextual() && schemaUrisByTypelessPostfix.entrySet().stream()
                .anyMatch(entry -> entry.getValue().equals(schema.asText())
                        && ExportImportUtils.statesPostfix(fileName, entry.getKey()));
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
