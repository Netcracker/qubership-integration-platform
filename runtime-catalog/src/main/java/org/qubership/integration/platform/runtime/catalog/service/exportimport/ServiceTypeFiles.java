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

import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.CONTENT;
import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.CONTEXT_SERVICE_YAML_NAME_POSTFIX;
import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.INTEGRATION_SYSTEM_TYPE;
import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.MCP_SERVICE_YAML_NAME_POSTFIX;

/**
 * The one place that knows how a service type is spelled: in a {@code $schema} and in a document. Type resolution,
 * the exporter, the rollout converter, and the V105 pair all read it from here, so the spellings cannot drift apart.
 * It also recognizes the two kinds that are their own kind of document, the context and the MCP service, because the
 * plain-service import has to tell their files from its own.
 *
 * <p>A file name states no service type. It used to — {@code <id>.external-service.<app>.yaml} was the whole of
 * #553 — and the three postfixes still exist because discovery reads archives written that way. Nothing resolves a
 * type from them.
 */
@Component
public class ServiceTypeFiles {

    private static final String SCHEMA = "$schema";

    /**
     * The schema's own file name per type, without an extension. This is the format talking, not the deployment: it
     * is what survives a rehost of the schema registry and the older truncated URI form, and it is what types a
     * document exported by an instance whose {@code qip.json.schemas.*} differ from this one's.
     */
    private static final Map<IntegrationSystemType, String> SCHEMA_FILE_STEMS = new EnumMap<>(Map.of(
            IntegrationSystemType.EXTERNAL, "external-service",
            IntegrationSystemType.INTERNAL, "internal-service",
            IntegrationSystemType.IMPLEMENTED, "implemented-service"));

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

    /** The {@code $schema} an export stamps on a service of {@code type}. */
    public String schemaUri(IntegrationSystemType type) {
        return schemaUrisByType.get(Objects.requireNonNull(type, "service type"));
    }

    /** Every {@code $schema} a plain service can be typed by, for an error message that names the remedy. */
    public Collection<String> plainServiceSchemaUris() {
        return List.copyOf(schemaUrisByType.values());
    }

    /** The type a document's {@code $schema} states, for a caller holding the document rather than the URI. */
    public Optional<IntegrationSystemType> typeFromDocumentSchema(JsonNode document) {
        if (document == null) {
            return Optional.empty();
        }
        JsonNode schema = document.path(SCHEMA);
        return schema.isTextual() ? typeFromSchemaUri(schema.asText()) : Optional.empty();
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
     * <p>The question here is which kind of document this is, not which of the three plain types it is, and it is
     * asked only about a name a typeless kind's export writes. The exact-URI comparison is deliberate and is not the
     * two-layer match {@link #typeFromSchemaUri} runs: a file this answers true for is handed to another import
     * entirely, so a loose match here loses a service rather than mistyping one.
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
     * The type a per-type {@code $schema} states. This is what every path types a current-format service by: the
     * archive import, the rollout import, and the V105 revert migration, which works on a document with no file name
     * to read at all.
     *
     * <p>Matched in two layers, because the URI is configurable on both sides — {@code qip.json.schemas.*} here, a
     * project's {@code .config.qip.yaml} in the VS Code extension. The configured value is tried first, so an
     * installation that rehosts its schemas types its own files. Failing that, the schema's own file name decides,
     * which is what carries a document between two installations configured differently and what reads the older URI
     * form that stops at {@code .../external-service}. A project that renames the schema file itself resolves no
     * type, and the import says so rather than guessing.
     */
    public Optional<IntegrationSystemType> typeFromSchemaUri(String schemaUri) {
        if (schemaUri == null) {
            return Optional.empty();
        }
        return firstTypeSpelled(schemaUrisByType, schemaUri)
                .or(() -> firstTypeSpelled(SCHEMA_FILE_STEMS, schemaFileStem(schemaUri)));
    }

    private static Optional<IntegrationSystemType> firstTypeSpelled(
            Map<IntegrationSystemType, String> spellings, String value) {
        return spellings.entrySet().stream()
                .filter(entry -> entry.getValue().equals(value))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    /** The schema's own file name with every extension off — the part a rehost or a truncation leaves alone. */
    public static String schemaFileStem(String schemaUri) {
        // A fragment or a query carries slashes of its own, so the path ends at the first of them, not at the last '/'.
        String path = schemaUri.split("[#?]", 2)[0];
        String lastSegment = path.substring(path.lastIndexOf('/') + 1);
        int extension = lastSegment.indexOf('.');
        return extension < 0 ? lastSegment : lastSegment.substring(0, extension);
    }
}
