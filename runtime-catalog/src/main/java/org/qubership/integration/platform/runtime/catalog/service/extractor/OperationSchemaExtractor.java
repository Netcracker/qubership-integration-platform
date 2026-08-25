package org.qubership.integration.platform.runtime.catalog.service.extractor;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.parsers.SpecificationParserException;
import org.qubership.integration.platform.parsers.impl.AsyncapiSpecificationParser;
import org.qubership.integration.platform.parsers.impl.GraphqlSpecificationParser;
import org.qubership.integration.platform.parsers.impl.ProtobufSpecificationParser;
import org.qubership.integration.platform.parsers.impl.SwaggerSpecificationParser;
import org.qubership.integration.platform.parsers.model.ParsedOperation;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationImportException;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Rebuilds an operation's request/response schemas and its specification slice on demand from the raw
 * specification source, replacing the schemas import used to materialize into the {@code operations}
 * table. Extraction reuses each protocol's persistence-free {@code parseOperations} core; it never
 * reimplements schema production.
 *
 * <p>Every read parses the source fresh. Reads fetch one operation at a time on small specs, so there
 * is no cache; a caller that needs the whole document at once takes {@link #extractAll} instead of one
 * {@code extract} per operation.
 */
@Slf4j
@Service
public class OperationSchemaExtractor {

    private static final String SPECIFICATION_FILE_PROCESSING_ERROR =
            "An error occurred during parsing specification file";

    private static final String SYNTHETIC_SOURCE_NAME = "source.proto";
    private static final int KEYS_TO_DESCRIBE = 10;
    private static final ExtractedSchemas EMPTY_SCHEMAS = new ExtractedSchemas(null, null, null);

    private final SwaggerSpecificationParser swaggerSpecificationParser;
    private final AsyncapiSpecificationParser asyncapiSpecificationParser;
    private final GraphqlSpecificationParser graphqlSpecificationParser;
    private final ProtobufSpecificationParser protobufSpecificationParser;

    @Autowired
    public OperationSchemaExtractor(
            SwaggerSpecificationParser swaggerSpecificationParser,
            AsyncapiSpecificationParser asyncapiSpecificationParser,
            GraphqlSpecificationParser graphqlSpecificationParser,
            ProtobufSpecificationParser protobufSpecificationParser) {
        this.swaggerSpecificationParser = swaggerSpecificationParser;
        this.asyncapiSpecificationParser = asyncapiSpecificationParser;
        this.graphqlSpecificationParser = graphqlSpecificationParser;
        this.protobufSpecificationParser = protobufSpecificationParser;
    }

    /**
     * The extractor output behind the stable {@code OperationInfo} contract. {@code requestSchema} and
     * {@code responseSchemas} are {@code null} for protocols that carry no schemas, such as WSDL and
     * GraphQL.
     */
    public record ExtractedSchemas(
            JsonNode specification,
            Map<String, JsonNode> requestSchema,
            Map<String, JsonNode> responseSchemas
    ) {
    }

    /**
     * Key of an operation inside one parsed document: exact path plus case-insensitive method, the pair
     * {@code matchOperation} compares. Build it through {@link #of} so both sides normalize alike.
     */
    public record OperationKey(String path, String method) {

        public static OperationKey of(String path, String method) {
            return new OperationKey(path, method == null ? null : method.toUpperCase(Locale.ROOT));
        }
    }

    /**
     * Renders keys for a log line, capped so one bad document cannot flood the log. Callers that report a key miss
     * share this, so the same miss reads the same way on the import side and on the export side.
     */
    public static String describeKeys(List<OperationKey> keys) {
        String listed = keys.stream()
                .limit(KEYS_TO_DESCRIBE)
                .map(key -> key.method() + " " + key.path())
                .collect(Collectors.joining(", "));
        return keys.size() > KEYS_TO_DESCRIBE
                ? listed + ", and " + (keys.size() - KEYS_TO_DESCRIBE) + " more"
                : listed;
    }

    /**
     * Persistence-free, single-source seam for tests. Wraps the raw source as one
     * {@link SpecificationSource} and delegates to the multi-source method, so the parity gate can drive
     * extraction with corpus fixtures that never touch the database. Production always calls the
     * {@link SpecificationSource}-list overload.
     */
    ExtractedSchemas extract(String rawSource, OperationProtocol protocol, String path, String method) {
        requireProtocol(protocol);
        if (hasNoSchemaExtraction(protocol)) {
            return EMPTY_SCHEMAS;
        }
        SpecificationSource source = SpecificationSource.builder()
                .name(SYNTHETIC_SOURCE_NAME)
                .isMainSource(true)
                .source(rawSource)
                .build();
        return extract(List.of(source), protocol, path, method);
    }

    /**
     * Production seam. Takes every {@link SpecificationSource} the model owns: protobuf needs all of them
     * (a {@code service.proto} can reference a message declared in a second file), while other protocols
     * parse only the main source.
     */
    public ExtractedSchemas extract(
            List<SpecificationSource> sources, OperationProtocol protocol, String path, String method) {
        requireProtocol(protocol);
        if (hasNoSchemaExtraction(protocol) || lacksParseableSource(sources, protocol)) {
            return EMPTY_SCHEMAS;
        }
        List<ParsedOperation> operations = parseDocument(sources, protocol, true);
        return toExtractedSchemas(matchOperation(operations, path, method));
    }

    /**
     * Bulk seam for callers that need every operation of one model, such as the legacy export. The document
     * is parsed once, whereas {@link #extract} reparses it per call and so costs one parse per operation.
     *
     * <p>{@code withSchemas} is the cost switch: the {@code specification} slice is built either way, while
     * request and response schemas inline every referenced component into every operation and every response
     * code. A caller that reads only {@code specification} passes {@code false}.
     *
     * <p>A protocol with no schemas and a source with nothing to parse yield an empty map, and a key claimed
     * by two operations drops out of it — the bulk counterpart of the per-operation throw on an ambiguous
     * match. A parse failure still surfaces, so the caller decides how to degrade.
     */
    public Map<OperationKey, ExtractedSchemas> extractAll(
            List<SpecificationSource> sources, OperationProtocol protocol, boolean withSchemas) {
        requireProtocol(protocol);
        if (hasNoSchemaExtraction(protocol) || lacksParseableSource(sources, protocol)) {
            return Map.of();
        }
        Map<OperationKey, ExtractedSchemas> schemasByOperation = new HashMap<>();
        Set<OperationKey> ambiguousKeys = new HashSet<>();
        for (ParsedOperation operation : parseDocument(sources, protocol, withSchemas)) {
            OperationKey key = OperationKey.of(operation.getPath(), operation.getMethod());
            if (schemasByOperation.put(key, toExtractedSchemas(operation)) != null) {
                ambiguousKeys.add(key);
            }
        }
        for (OperationKey key : ambiguousKeys) {
            schemasByOperation.remove(key);
            log.warn("Ambiguous operation for path='{}', method='{}': its schemas are not extracted",
                    key.path(), key.method());
        }
        return schemasByOperation;
    }

    // Parses on the request thread, inside the caller's read-only transaction, with no size cap: every parser core
    // wraps its failures in SpecificationImportException, so a bad source costs one parse and degrades to null schemas.
    private List<ParsedOperation> parseDocument(
            List<SpecificationSource> sources, OperationProtocol protocol, boolean withSchemas) {
        List<org.qubership.integration.platform.parsers.SpecificationSource> librarySources = toLibrarySources(sources);
        Consumer<String> silent = message -> { };
        try {
            return parseWithLibrary(protocol, librarySources, silent);
        } catch (SpecificationParserException e) {
            // The read path answers with the catalog's import-facing exception, the same one the import path
            // surfaces, so a caller has one type to degrade on rather than two.
            throw new SpecificationImportException(SPECIFICATION_FILE_PROCESSING_ERROR, e);
        }
    }

    private List<ParsedOperation> parseWithLibrary(
            OperationProtocol protocol,
            List<org.qubership.integration.platform.parsers.SpecificationSource> librarySources,
            Consumer<String> silent) {
        return switch (protocol) {
            case HTTP -> swaggerSpecificationParser.parseSpecification(null, librarySources, silent).getOperations();
            case AMQP, KAFKA -> asyncapiSpecificationParser.parseSpecification(null, librarySources, silent).getOperations();
            case GRAPHQL -> graphqlSpecificationParser.parseSpecification(null, librarySources, silent).getOperations();
            case GRPC -> protobufSpecificationParser.parseSpecification(null, librarySources, silent).getOperations();
            default -> List.of(); // SOAP/METAMODEL never reach here: filtered out by hasNoSchemaExtraction above
        };
    }

    // The library parsers read their own source type and always build schemas; `withSchemas` no longer changes
    // the parse, only whether the caller keeps what came back.
    private static List<org.qubership.integration.platform.parsers.SpecificationSource> toLibrarySources(
            List<SpecificationSource> sources) {
        return sources == null ? List.of() : sources.stream()
                .map(source -> new org.qubership.integration.platform.parsers.SpecificationSource(
                        source.getName(), source.getSource(), source.isMainSource()))
                .toList();
    }

    private static String mainSourceText(List<SpecificationSource> sources) {
        if (sources == null) {
            return null;
        }
        return sources.stream()
                .filter(SpecificationSource::isMainSource)
                .findFirst()
                .or(() -> sources.stream().findFirst())
                .map(SpecificationSource::getSource)
                .orElse(null);
    }

    // A blank main source (no content to parse) degrades to EMPTY_SCHEMAS up front instead of handing a
    // null/blank string to a parser. Protobuf is exempt: it parses every source, not just the main one.
    private static boolean lacksParseableSource(List<SpecificationSource> sources, OperationProtocol protocol) {
        return protocol != OperationProtocol.GRPC && StringUtils.isBlank(mainSourceText(sources));
    }

    private static void requireProtocol(OperationProtocol protocol) {
        if (protocol == null) {
            throw new SpecificationImportException("Cannot extract schemas: operation protocol is not set.");
        }
    }

    /**
     * Whether the protocol has an extraction path at all. Callers that strip a stored value on the assumption that
     * extraction can rebuild it must gate on this, so the strip and the rebuild stay one decision rather than two
     * protocol lists that can drift apart.
     */
    public static boolean canExtractSchemas(OperationProtocol protocol) {
        return protocol != null && !hasNoSchemaExtraction(protocol);
    }

    // SOAP and METAMODEL carry no request/response schemas by design; degrade to EMPTY_SCHEMAS up front
    // instead of ever attempting a parse.
    private static boolean hasNoSchemaExtraction(OperationProtocol protocol) {
        return protocol == OperationProtocol.SOAP || protocol == OperationProtocol.METAMODEL;
    }

    private static ExtractedSchemas toExtractedSchemas(ParsedOperation operation) {
        return new ExtractedSchemas(
                operation.getSpecification(),
                operation.getRequestSchema(),
                operation.getResponseSchemas());
    }

    private static ParsedOperation matchOperation(List<ParsedOperation> operations, String path, String method) {
        List<ParsedOperation> byPathAndMethod = operations.stream()
                .filter(operation -> Objects.equals(operation.getPath(), path)
                        && method != null
                        && method.equalsIgnoreCase(operation.getMethod()))
                .toList();
        if (byPathAndMethod.size() == 1) {
            return byPathAndMethod.getFirst();
        }
        if (byPathAndMethod.size() > 1) {
            throw new IllegalArgumentException(
                    "Ambiguous operation for path='" + path + "', method='" + method + "'");
        }
        throw new IllegalArgumentException(
                "No operation matched path='" + path + "', method='" + method + "'");
    }
}
