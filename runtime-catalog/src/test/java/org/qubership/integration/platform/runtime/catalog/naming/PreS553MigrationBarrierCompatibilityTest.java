package org.qubership.integration.platform.runtime.catalog.naming;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.GoldenServiceCorpus;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a Runtime Catalog from before #553 makes of a plain service in an archive this one writes.
 *
 * <p>{@link PreS553DiscoveryCompatibilityTest} settles the half before this one: such an import <i>finds</i> today's
 * plain service files, because the name went back to {@code .service.}. This class is what happens next, and the answer
 * has to be a per-service error rather than a service imported with the wrong type or no type at all.
 *
 * <p>Two frozen pieces carry it, both from {@code main} ({@code b54d5dee0}), the pre-#553 line:
 * <ul>
 *   <li>the migration-version barrier of {@code FileMigrationService}, in {@link FrozenMigrationBarrier};</li>
 *   <li>the type assignment of {@code ServiceDeserializer}, which on that line is Jackson filling
 *       {@code IntegrationSystemContentDto.integrationSystemType} and {@code IntegrationSystemDtoMapper} copying it
 *       onto the entity — no file name is read, and no {@code $schema} is.</li>
 * </ul>
 *
 * <p><b>Never update either copy to match current code.</b> Updating one to make a case pass deletes what this class
 * measures.
 *
 * <p>The order of the two is the point. {@code ServiceDeserializer.deserializeSystem} on {@code main} calls
 * {@code fileMigrationService.migrate(...)} and only then {@code treeToValue} plus {@code toInternalEntity}, so the
 * barrier decides first and the type assignment never runs on a barred document. {@code SystemExportImportService}
 * catches per file and answers {@code ImportSystemStatus.ERROR} with the exception message, so one barred service
 * costs that service and not the archive.
 */
class PreS553MigrationBarrierCompatibilityTest {

    /**
     * Main's whole service migration registry. {@code git ls-tree main -- .../migrations/system} holds V100, V101 and
     * V102 and nothing else, so 103, 104 and 105 are versions it cannot name.
     */
    private static final List<Integer> MAIN_REGISTRY_VERSIONS = List.of(100, 101, 102);

    static Stream<Arguments> barredServices() {
        return Stream.of(
                // The current format. `content.migrations` claims [100..105]; main holds three of those, so the
                // barrier fires on the other three. The last column is what main's type assignment would have read
                // had the barrier let the document through: nothing, because a post-#553 export omits
                // `content.integrationSystemType`. Both outcomes it has are safe — an error row, or a null type it
                // refuses later. Neither is a service imported as the wrong type.
                Arguments.of(GoldenServiceCorpus.POST553, GoldenServiceCorpus.EXTERNAL_SERVICE_ID,
                        Set.of(103, 104, 105), null),
                Arguments.of(GoldenServiceCorpus.POST553, GoldenServiceCorpus.INTERNAL_SERVICE_ID,
                        Set.of(103, 104, 105), null),
                Arguments.of(GoldenServiceCorpus.POST553, GoldenServiceCorpus.IMPLEMENTED_SERVICE_ID,
                        Set.of(103, 104, 105), null),

                // The contrast row. A pre-#553 archive states its type in the document, and main would read it — yet
                // the barrier still stops the import over V103 and V104. The barrier is what decides the outcome, not
                // the type field, so restoring the field to the current format would not make the archive importable
                // there.
                Arguments.of(GoldenServiceCorpus.PRE553_CURRENT, GoldenServiceCorpus.EXTERNAL_SERVICE_ID,
                        Set.of(103, 104), IntegrationSystemType.EXTERNAL),
                Arguments.of(GoldenServiceCorpus.PRE553_CURRENT, GoldenServiceCorpus.INTERNAL_SERVICE_ID,
                        Set.of(103, 104), IntegrationSystemType.INTERNAL),
                Arguments.of(GoldenServiceCorpus.PRE553_CURRENT, GoldenServiceCorpus.IMPLEMENTED_SERVICE_ID,
                        Set.of(103, 104), IntegrationSystemType.IMPLEMENTED));
    }

    @ParameterizedTest(name = "{0} / {1}")
    @MethodSource("barredServices")
    @DisplayName("a pre-#553 import stops at the migration barrier and never reaches the type")
    void preS553ImportIsBarredPerService(
            String setName,
            String serviceId,
            Set<Integer> expectedUnknownVersions,
            IntegrationSystemType typeMainWouldHaveRead
    ) {
        ObjectNode document = GoldenServiceCorpus.read(GoldenServiceCorpus.serviceFile(setName, serviceId));

        FrozenMigrationBarrier.FrozenMigrationException barred =
                assertThrows(FrozenMigrationBarrier.FrozenMigrationException.class,
                        () -> FrozenMigrationBarrier.requireImportable(document, MAIN_REGISTRY_VERSIONS),
                        () -> serviceId + " of " + setName + " passes a pre-#553 migration barrier, so an import there"
                                + " gets as far as the type and has to answer for it");
        assertEquals(FrozenMigrationBarrier.NEWER_VERSION_MESSAGE, barred.getMessage());
        assertEquals(expectedUnknownVersions, barred.getNonexistentVersions(),
                () -> "the versions " + serviceId + " of " + setName + " claims past V102 changed");

        // The counterfactual, asserted because it is what rules out a mistyped import: even with the barrier gone,
        // main reads this type and no other. It never runs here — `migrate` precedes `treeToValue`.
        assertEquals(typeMainWouldHaveRead, frozenTypeAssignment(document),
                () -> "what a pre-#553 import would assign to " + serviceId + " of " + setName + " changed");
    }

    static Stream<String> plainServiceIds() {
        return Stream.of(
                GoldenServiceCorpus.EXTERNAL_SERVICE_ID,
                GoldenServiceCorpus.INTERNAL_SERVICE_ID,
                GoldenServiceCorpus.IMPLEMENTED_SERVICE_ID);
    }

    static Stream<Arguments> legacyServices() {
        return Stream.of(
                Arguments.of(GoldenServiceCorpus.EXTERNAL_SERVICE_ID, IntegrationSystemType.EXTERNAL),
                Arguments.of(GoldenServiceCorpus.INTERNAL_SERVICE_ID, IntegrationSystemType.INTERNAL),
                Arguments.of(GoldenServiceCorpus.IMPLEMENTED_SERVICE_ID, IntegrationSystemType.IMPLEMENTED));
    }

    /**
     * The designated downgrade, at the file level: {@code qip.export.legacy-format=true} writes migrations the older
     * registry holds and puts the type back in the document, so the same import that bars every current-format
     * service clears the barrier here and types the service correctly. Together with the legacy rows of
     * {@link PreS553DiscoveryCompatibilityTest}, this is the whole path — the file is found, admitted, and typed.
     */
    @ParameterizedTest(name = "legacy-flat / {0}")
    @MethodSource("legacyServices")
    @DisplayName("the legacy export clears the same barrier and states its type in the document")
    void theLegacyExportSurvivesTheDowngrade(String serviceId, IntegrationSystemType expectedType) {
        ObjectNode document =
                GoldenServiceCorpus.read(GoldenServiceCorpus.serviceFile(GoldenServiceCorpus.LEGACY_FLAT, serviceId));

        assertTrue(FrozenMigrationBarrier.unknownVersions(document, MAIN_REGISTRY_VERSIONS).isEmpty(),
                () -> "the legacy export of " + serviceId + " claims a version a pre-#553 registry does not hold: "
                        + FrozenMigrationBarrier.unknownVersions(document, MAIN_REGISTRY_VERSIONS));
        assertDoesNotThrow(() -> FrozenMigrationBarrier.requireImportable(document, MAIN_REGISTRY_VERSIONS));

        assertEquals(expectedType, frozenTypeAssignment(document),
                () -> "the legacy export of " + serviceId + " no longer states its type where a pre-#553 import"
                        + " reads it");
    }

    /**
     * A guard on the counterfactual above: the field is absent from the current format, rather than present and
     * ignored. Without this, {@code null} in the last column of {@link #barredServices} would also be what a reader
     * looking in the wrong place returns.
     */
    @ParameterizedTest(name = "post553 / {0}")
    @MethodSource("plainServiceIds")
    @DisplayName("the current export writes no integrationSystemType anywhere in the document")
    void theCurrentExportStatesNoTypeField(String serviceId) {
        ObjectNode document =
                GoldenServiceCorpus.read(GoldenServiceCorpus.serviceFile(GoldenServiceCorpus.POST553, serviceId));

        assertNull(document.get("integrationSystemType"));
        assertNull(document.path("content").get("integrationSystemType"));
    }

    // --- the frozen type assignment ----------------------------------------------------------------------------------

    /**
     * The type a pre-#553 import ends up with, frozen at {@code main} ({@code b54d5dee0}): Jackson reads
     * {@code content.integrationSystemType} into {@code IntegrationSystemContentDto} and
     * {@code IntegrationSystemDtoMapper.toInternalEntity} copies it onto the entity. No file name and no
     * {@code $schema} takes part — that line of code has neither {@code resolveServiceType} nor {@code ServiceTypeFiles}.
     *
     * <p>The root fallback stands in for V101, which moves every field except {@code id} and {@code name} under
     * {@code content} before the DTO is read; the legacy flat document arrives unwrapped and claims [100, 102], so
     * V101 is exactly the migration that runs on it. Freezing the move itself would add a copy and no measurement.
     *
     * <p>Frozen on purpose. See the class comment before touching it.
     */
    private static IntegrationSystemType frozenTypeAssignment(ObjectNode document) {
        JsonNode content = document.get("content");
        JsonNode type = content != null && content.isObject() ? content.get("integrationSystemType") : null;
        if (type == null) {
            type = document.get("integrationSystemType");
        }
        return type == null || type.isNull() ? null : IntegrationSystemType.valueOf(type.asText());
    }
}
