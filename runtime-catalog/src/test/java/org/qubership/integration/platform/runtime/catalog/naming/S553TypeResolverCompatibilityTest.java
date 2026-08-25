package org.qubership.integration.platform.runtime.catalog.naming;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.GoldenServiceCorpus;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a Runtime Catalog built during #553 makes of a plain service in an archive this one writes.
 *
 * <p>That build is the other direction of the same compatibility question {@link PreS553MigrationBarrierCompatibilityTest}
 * asks about {@code main}. It sits <i>after</i> the current one in migration terms — its registry already holds V105 —
 * so the version barrier lets today's documents straight through, and the type resolver is left to answer alone. It
 * reads the file name first and {@code content.integrationSystemType} second, and deliberately never reads
 * {@code $schema}, which is where this version puts the type. Every plain service of a current archive therefore
 * resolves nothing, and the resolver has to say so.
 *
 * <p>The resolver below is a <b>frozen copy</b> of {@code ServiceDeserializer.resolveServiceType} together with
 * {@code ServiceTypeFiles.typeFromFileName} as they stood at {@code 2934600a6}. <b>Never update it to match current
 * code.</b> Today's resolver reads {@code $schema} first and answers every one of these files; rewriting the copy to
 * agree would delete the only measurement of the build that cannot.
 *
 * <p>Why the outcome is safe: {@code ServiceImportException} is the one exception
 * {@code ServiceDeserializer.deserializeSystem} rethrows unwrapped, and {@code SystemExportImportService} catches per
 * file and answers {@code ImportSystemStatus.ERROR} with the message. One service, one actionable row, naming the file
 * and both ways to fix it — not a silent skip, and not a service imported as the wrong type.
 */
class S553TypeResolverCompatibilityTest {

    /**
     * The #553-era service migration registry. {@code git ls-tree 2934600a6 -- .../migrations/system} holds V100
     * through V105, so a current archive claims nothing that build cannot name.
     */
    private static final List<Integer> S553_REGISTRY_VERSIONS = List.of(100, 101, 102, 103, 104, 105);

    /**
     * {@code ServiceTypeFiles.POSTFIXES_BY_TYPE} at that commit, literals and all. Reading today's
     * {@code ExportImportConstants} would let a rename here rewrite a resolver no version would then have shipped.
     */
    private static final Map<IntegrationSystemType, String> S553_POSTFIXES_BY_TYPE = new EnumMap<>(Map.of(
            IntegrationSystemType.EXTERNAL, ".external-service.",
            IntegrationSystemType.INTERNAL, ".internal-service.",
            IntegrationSystemType.IMPLEMENTED, ".implemented-service."));

    /** {@code ServiceTypeFiles.postfixes()}: the {@code EnumMap} values, so declaration order of the enum. */
    private static final List<String> S553_POSTFIXES = List.copyOf(S553_POSTFIXES_BY_TYPE.values());

    static Stream<String> plainServiceIds() {
        return Stream.of(
                GoldenServiceCorpus.EXTERNAL_SERVICE_ID,
                GoldenServiceCorpus.INTERNAL_SERVICE_ID,
                GoldenServiceCorpus.IMPLEMENTED_SERVICE_ID);
    }

    @ParameterizedTest(name = "post553 / {0}")
    @MethodSource("plainServiceIds")
    @DisplayName("a #553-era import clears the barrier and then resolves no type at all")
    void theS553ResolverFailsPerServiceOnACurrentArchive(String serviceId) {
        Path file = GoldenServiceCorpus.serviceFile(GoldenServiceCorpus.POST553, serviceId);
        String fileName = file.getFileName().toString();
        ObjectNode document = GoldenServiceCorpus.read(file);

        // The barrier is not what fails here: that build holds every version the document claims.
        assertTrue(FrozenMigrationBarrier.unknownVersions(document, S553_REGISTRY_VERSIONS).isEmpty(),
                () -> "the current export of " + serviceId + " claims a version the #553-era registry does not hold: "
                        + FrozenMigrationBarrier.unknownVersions(document, S553_REGISTRY_VERSIONS));
        assertDoesNotThrow(() -> FrozenMigrationBarrier.requireImportable(document, S553_REGISTRY_VERSIONS));

        // Neither source that build consults answers. The name states a kind, and the field is gone from the format.
        assertEquals(Optional.empty(), frozenTypeFromFileName(fileName),
                () -> "the current export names " + serviceId + " with a per-type postfix again");
        assertNull(frozenTypeFromDocument(document),
                () -> "the current export of " + serviceId + " states content.integrationSystemType again");

        ServiceImportRefusal refusal = assertThrows(ServiceImportRefusal.class,
                () -> frozenResolveServiceType(fileName, document),
                () -> "the #553-era resolver types " + serviceId + " of a current archive, which it cannot read a"
                        + " type from — a silent mistype is the outcome this row exists to rule out");

        // The message is the row a user sees, so it is asserted rather than the exception type alone: it names the
        // file, both sources it tried, and both repairs.
        assertTrue(refusal.getMessage().contains(fileName), refusal::getMessage);
        assertTrue(refusal.getMessage().contains("states no service type"), refusal::getMessage);
        assertTrue(refusal.getMessage().contains("content.integrationSystemType"), refusal::getMessage);
        S553_POSTFIXES.forEach(postfix ->
                assertTrue(refusal.getMessage().contains(postfix), refusal::getMessage));
        assertTrue(refusal.getMessage().endsWith("The service is not imported."), refusal::getMessage);
    }

    static Stream<Arguments> preS553Services() {
        return Stream.of(
                Arguments.of(GoldenServiceCorpus.EXTERNAL_SERVICE_ID, IntegrationSystemType.EXTERNAL),
                Arguments.of(GoldenServiceCorpus.INTERNAL_SERVICE_ID, IntegrationSystemType.INTERNAL),
                Arguments.of(GoldenServiceCorpus.IMPLEMENTED_SERVICE_ID, IntegrationSystemType.IMPLEMENTED));
    }

    /**
     * The control. The same frozen resolver over a pre-#553 archive, whose file names state a kind exactly as today's
     * do, types every service from the document field — so what it refuses above is the removal of that field, not
     * the {@code .service.} name it shares with the older format.
     */
    @ParameterizedTest(name = "pre553-current / {0}")
    @MethodSource("preS553Services")
    @DisplayName("the same resolver types a pre-#553 archive from the document field")
    void theS553ResolverStillReadsTheDocumentField(String serviceId, IntegrationSystemType expectedType) {
        Path file = GoldenServiceCorpus.serviceFile(GoldenServiceCorpus.PRE553_CURRENT, serviceId);
        ObjectNode document = GoldenServiceCorpus.read(file);

        assertEquals(expectedType, frozenResolveServiceType(file.getFileName().toString(), document));
    }

    // --- the frozen resolver -----------------------------------------------------------------------------------------

    /**
     * {@code ServiceDeserializer.resolveServiceType} at {@code 2934600a6}, verbatim apart from returning the type
     * instead of setting it on the entity. Frozen on purpose. See the class comment before touching it.
     */
    private static IntegrationSystemType frozenResolveServiceType(String fileName, ObjectNode document) {
        IntegrationSystemType fromFileName = frozenTypeFromFileName(fileName).orElse(null);
        IntegrationSystemType fromDocument = frozenTypeFromDocument(document);

        if (fromFileName == null && fromDocument == null) {
            throw new ServiceImportRefusal(
                    ("Service file %s states no service type: its name carries no type postfix and"
                            + " content.integrationSystemType is absent. Rename the file with one of %s, or set"
                            + " content.integrationSystemType, then re-import. The service is not imported.")
                            .formatted(fileName, String.join(", ", S553_POSTFIXES)));
        }
        if (fromFileName != null && fromDocument != null && fromFileName != fromDocument) {
            throw new ServiceImportRefusal(
                    ("Service file %s states type %s in its name and %s in content.integrationSystemType. Correct one"
                            + " of the two so they agree, then re-import. The service is not imported.")
                            .formatted(fileName, fromFileName, fromDocument));
        }
        return fromFileName != null ? fromFileName : fromDocument;
    }

    /** {@code ServiceTypeFiles.typeFromFileName} at the same commit: a substring test per postfix, first match wins. */
    private static Optional<IntegrationSystemType> frozenTypeFromFileName(String fileName) {
        if (fileName == null) {
            return Optional.empty();
        }
        return S553_POSTFIXES_BY_TYPE.entrySet().stream()
                .filter(entry -> fileName.contains(entry.getValue()))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    /**
     * The second source: {@code content.integrationSystemType}, read the way Jackson and
     * {@code IntegrationSystemDtoMapper} read it at that commit. The root fallback stands in for V101, which wraps a
     * flat legacy document under {@code content} before the DTO is built.
     */
    private static IntegrationSystemType frozenTypeFromDocument(ObjectNode document) {
        JsonNode content = document.get("content");
        JsonNode type = content != null && content.isObject() ? content.get("integrationSystemType") : null;
        if (type == null) {
            type = document.get("integrationSystemType");
        }
        return type == null || type.isNull() ? null : IntegrationSystemType.valueOf(type.asText());
    }

    /** Stands in for {@code ServiceImportException}, which the import surfaces as the row message unwrapped. */
    static final class ServiceImportRefusal extends RuntimeException {
        ServiceImportRefusal(String message) {
            super(message);
        }
    }
}
