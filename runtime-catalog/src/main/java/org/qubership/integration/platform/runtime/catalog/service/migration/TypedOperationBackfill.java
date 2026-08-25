package org.qubership.integration.platform.runtime.catalog.service.migration;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.parsers.SpecificationSource;
import org.qubership.integration.platform.parsers.impl.WsdlSpecificationParser;
import org.qubership.integration.platform.parsers.model.ParsedOperation;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.AsyncapiOperation;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.GraphqlOperation;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.OpenapiOperation;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.ProtobufOperation;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.TypedOperation;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.WsdlOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ProtocolExtractionService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Rebuilds {@code typed} and the API-level specification metadata for pre-migration rows.
 *
 * <p>The column-derived methods read data the row already stores — the {@code path} and {@code method}
 * columns and slices of the {@code specification} jsonb — and reparse nothing. The reparse pass fills the two
 * fields with no column-side origin: {@code specificationVersion} through a root-document read and WSDL
 * {@code protocol}/{@code binding} by reparsing the main source, reusing the import-time logic so backfilled
 * and freshly imported rows agree.
 *
 * <p>The methods are pure over their arguments, so the fixture-driven tests run without a database. A model
 * with no source cannot be reparsed: it is reported rather than thrown on. The column-derived path needs no
 * collaborators; pass nulls when only that path is used (the Flyway migration).
 */
@Slf4j
@Component
public class TypedOperationBackfill {

    private static final String ID_FIELD = "$id";
    private static final String SUMMARY_FIELD = "summary";
    private static final String DEPRECATED_FIELD = "deprecated";
    private static final String GRAPHQL_OPERATION_FIELD = "operation";

    private final ProtocolExtractionService protocolExtractionService;
    private final WsdlSpecificationParser wsdlSpecificationParser;

    public TypedOperationBackfill(ProtocolExtractionService protocolExtractionService,
                                  WsdlSpecificationParser wsdlSpecificationParser) {
        this.protocolExtractionService = protocolExtractionService;
        this.wsdlSpecificationParser = wsdlSpecificationParser;
    }

    /**
     * Reconstructs the typed payload from the operation's columns and its specification jsonb. Column-derived
     * for every protocol except WSDL, whose protocol and binding are reparsed from {@code rawSource} when present
     * and left null otherwise (method/path are WSDL constants, so a sourceless WSDL row still gets a bare
     * {@link WsdlOperation}). Returns null only for METAMODEL, which never reaches the API level.
     */
    public TypedOperation backfillTyped(Operation operation, JsonNode specification,
                                        OperationProtocol protocol, String rawSource) {
        if (protocol == null) {
            return null;
        }
        return switch (protocol) {
            case HTTP -> new OpenapiOperation(
                    text(specification, SUMMARY_FIELD),
                    operation.getPath(),
                    lower(operation.getMethod()),
                    bool(specification, DEPRECATED_FIELD));
            // No summary to read: the async resolvers write only the AsyncConstants keys (topic, queue,
            // exchangeName, username, maasClassifierName) into specification, in this version and every earlier one.
            case KAFKA, AMQP -> new AsyncapiOperation(null, operation.getPath(), operation.getMethod());
            case GRAPHQL -> new GraphqlOperation(operation.getMethod(), text(specification, GRAPHQL_OPERATION_FIELD));
            case GRPC -> backfillProtobuf(operation, specification);
            case SOAP -> {
                // Reparse the real protocol and binding when a source and name are present. Otherwise fall back to
                // the system protocol ("SOAP" in this branch); binding needs the source, so it stays null and fills
                // on the next import. The name guard covers the Flyway migration, which passes a nameless operation.
                WsdlOperation reparsed = operation.getName() == null
                        ? null
                        : reparseWsdlTypedByName(rawSource).get(operation.getName());
                yield reparsed != null ? reparsed : new WsdlOperation("SOAP", null);
            }
            case METAMODEL -> null;
        };
    }

    /**
     * Column-derived overload for callers with no source to reparse, such as the Flyway migration.
     */
    public TypedOperation backfillTyped(Operation operation, JsonNode specification, OperationProtocol protocol) {
        return backfillTyped(operation, specification, protocol, null);
    }

    /**
     * Maps {@link OperationProtocol} onto the api.schema.yaml specificationType enum, reusing the single
     * mapping in {@link ProtocolExtractionService}. Backfilled for existing models so they export as valid api.
     */
    public String backfillSpecificationType(OperationProtocol protocol) {
        return ProtocolExtractionService.mapSpecificationType(protocol);
    }

    /**
     * Reads the specification standard version from the main source through a root-document read, reusing
     * {@link ProtocolExtractionService}. Null for GraphQL and gRPC, which carry no such marker, and for a
     * missing source.
     */
    public String backfillSpecificationVersion(OperationProtocol protocol, String rawSource) {
        return protocolExtractionService.extractSpecificationVersion(protocol, rawSource);
    }

    /**
     * Entry point for the deferred one-off admin reparse; unwired on purpose, no production caller yet. The
     * Flyway migration leaves the reparse-only fields null by design and they fill on the next import, so this
     * pass exists for an explicit admin run (see docs/api-model-migration.md).
     *
     * <p>Reparse pass over the fields with no column-side origin. For each model it fills
     * {@code specificationVersion} and, for WSDL, every operation's protocol and binding from the main source.
     * A model with no source cannot be reparsed: its id is collected and logged, and its operations keep their
     * column-derived typed, instead of the pass throwing on the first sourceless row.
     *
     * @return the ids of models that could not be completed, for the migration audit log
     */
    public List<String> backfillReparseOnlyFields(Collection<ModelReparse> models) {
        List<String> incompleteModelIds = new ArrayList<>();
        for (ModelReparse entry : models) {
            SystemModel model = entry.model();
            if (entry.rawMainSource() == null) {
                incompleteModelIds.add(model.getId());
                continue;
            }
            model.setSpecificationVersion(backfillSpecificationVersion(entry.protocol(), entry.rawMainSource()));
            if (entry.protocol() == OperationProtocol.SOAP) {
                Map<String, WsdlOperation> typedByName = reparseWsdlTypedByName(entry.rawMainSource());
                for (Operation operation : model.getOperations()) {
                    WsdlOperation typed = typedByName.get(operation.getName());
                    if (typed != null) {
                        operation.setTyped(typed);
                    }
                }
            }
        }
        if (!incompleteModelIds.isEmpty()) {
            log.warn("Backfill reparse skipped {} model(s) with no specification source: {}",
                    incompleteModelIds.size(), incompleteModelIds);
        }
        return incompleteModelIds;
    }

    // The library parser records the SOAP protocol and binding in each operation's specification node, so a
    // reparse recovers both without a catalog-side WSDL API. Rows the Flyway migration passes carry no source
    // and keep the "SOAP"/null fallback until their next import.
    private Map<String, WsdlOperation> reparseWsdlTypedByName(String rawSource) {
        if (rawSource == null) {
            return Map.of();
        }
        try {
            List<ParsedOperation> operations = wsdlSpecificationParser
                    .parseSpecification(null, List.of(new SpecificationSource(null, rawSource, true)), message -> { })
                    .getOperations();
            Map<String, WsdlOperation> typedByName = new HashMap<>();
            for (ParsedOperation operation : operations) {
                if (operation.getName() != null) {
                    typedByName.put(operation.getName(), new WsdlOperation(
                            text(operation.getSpecification(), "protocol"),
                            text(operation.getSpecification(), "binding")));
                }
            }
            return typedByName;
        } catch (Exception e) {
            log.warn("Unable to reparse WSDL source", e);
            return Map.of();
        }
    }

    // path is javaPackage + "." + service (ProtobufSpecificationParser builds it from the java_package option,
    // falling back to the proto package); invert it so derivePath reproduces the stored path. The proto package
    // lives in the specification's $id tail, which reads package.service.rpc.
    private ProtobufOperation backfillProtobuf(Operation operation, JsonNode specification) {
        String path = operation.getPath();
        String rpcMethod = operation.getMethod();
        int lastDot = path == null ? -1 : path.lastIndexOf('.');
        String javaPackage = lastDot < 0 ? null : path.substring(0, lastDot);
        String service = lastDot < 0 ? path : path.substring(lastDot + 1);
        String packageName = protoPackageFromId(specification, service, rpcMethod);
        return new ProtobufOperation(packageName, service, rpcMethod, javaPackage);
    }

    private String protoPackageFromId(JsonNode specification, String service, String rpcMethod) {
        String id = findFirstId(specification);
        if (id == null) {
            return null;
        }
        String qualifiedName = id.substring(id.lastIndexOf('/') + 1);
        String suffix = "." + service + "." + rpcMethod;
        return qualifiedName.endsWith(suffix)
                ? qualifiedName.substring(0, qualifiedName.length() - suffix.length())
                : null;
    }

    private String findFirstId(JsonNode node) {
        if (node == null) {
            return null;
        }
        JsonNode id = node.get(ID_FIELD);
        if (id != null && id.isTextual()) {
            return id.asText();
        }
        for (JsonNode child : node) {
            String found = findFirstId(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Boolean bool(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asBoolean();
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    /**
     * A model queued for the reparse pass: the model to mutate, its resolved protocol, and its main source
     * ({@code null} when the model carries no source).
     */
    public record ModelReparse(SystemModel model, OperationProtocol protocol, String rawMainSource) {
    }
}
