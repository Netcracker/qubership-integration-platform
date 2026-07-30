package org.qubership.integration.platform.runtime.catalog.service.parsers.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationImportException;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource;
import org.qubership.integration.platform.runtime.catalog.service.extractor.ExtractorTestParsers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Every {@code parseOperations} core wraps a raw parser failure in a {@link SpecificationImportException} that
 * chains the root exception as its cause, so the on-demand read path can log what actually broke. Guards against
 * a regression to the non-chaining {@code (String, Exception)} behavior that once dropped the cause for three of
 * the four protocols.
 */
class ParserCoreExceptionChainingTest {

    private static final String MALFORMED = "this is not a valid specification {{{";

    /** Unterminated JSON, so the deserializer fails before the "is this OpenAPI?" guard can reject it cleanly. */
    private static final String UNPARSEABLE_OPENAPI = "{\"openapi\": \"3.0.0\", \"paths\": {";

    @Test
    @DisplayName("Swagger core chains the root parser exception as the cause")
    void swaggerCoreChainsCause() {
        SwaggerSpecificationParser parser = ExtractorTestParsers.swaggerParser();

        SpecificationImportException exception = assertThrows(SpecificationImportException.class,
                () -> parser.parseOperations(UNPARSEABLE_OPENAPI, true, message -> { }));

        assertCauseIsPreserved(exception);
    }

    @Test
    @DisplayName("AsyncAPI core chains the root parser exception as the cause")
    void asyncapiCoreChainsCause() {
        AsyncapiSpecificationParser parser = ExtractorTestParsers.asyncapiParser();

        SpecificationImportException exception = assertThrows(SpecificationImportException.class,
                () -> parser.parseOperations(MALFORMED, OperationProtocol.KAFKA, true));

        assertCauseIsPreserved(exception);
    }

    @Test
    @DisplayName("GraphQL core chains the root parser exception as the cause")
    void graphqlCoreChainsCause() {
        GraphqlSpecificationParser parser = ExtractorTestParsers.graphqlParser();

        SpecificationImportException exception = assertThrows(SpecificationImportException.class,
                () -> parser.parseOperations(MALFORMED));

        assertCauseIsPreserved(exception);
    }

    @Test
    @DisplayName("Protobuf core chains the root parser exception as the cause")
    void protobufCoreChainsCause() {
        ProtobufSpecificationParser parser = ExtractorTestParsers.protobufParser();
        SpecificationSource source = new SpecificationSource();
        source.setName("broken.proto");
        source.setSource(MALFORMED);
        List<SpecificationSource> sources = List.of(source);

        SpecificationImportException exception = assertThrows(SpecificationImportException.class,
                () -> parser.parseOperations(sources, true));

        assertCauseIsPreserved(exception);
    }

    /** The cause must be the same object the REST error handlers read through {@code getOriginalException}. */
    private static void assertCauseIsPreserved(SpecificationImportException exception) {
        assertNotNull(exception.getCause(), "root parser exception must survive as the cause");
        assertSame(exception.getOriginalException(), exception.getCause());
    }
}
