package org.qubership.integration.platform.runtime.catalog.naming;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The migration-version barrier of {@code FileMigrationService.migrate(ObjectNode, Collection)}, a <b>frozen copy</b>
 * of the lines as they stood at {@code main} ({@code b54d5dee0}). The same lines stand unchanged at
 * {@code 2934600a6}, the #553-era build — {@code git diff main 2934600a6} over that file shows one added
 * {@code isLegacyExport()} accessor and nothing else — so one copy serves both compatibility tests. What separates the
 * two versions is the migration registry each of them holds, and that is a parameter here.
 *
 * <p><b>Never update this copy to match current code.</b> It is what an <i>older</i> Runtime Catalog does with an
 * archive this one writes; rewriting it to match today's barrier would compare this version against itself, which the
 * rest of the suite already does.
 */
final class FrozenMigrationBarrier {

    /** The message {@code MigrationException} carries when a document claims a version the registry does not hold. */
    static final String NEWER_VERSION_MESSAGE = "Unable to import an entity exported from a newer version";

    private FrozenMigrationBarrier() {
    }

    /** The versions a document claims that {@code registryVersions} does not hold — {@code nonexistentVersions}. */
    static Set<Integer> unknownVersions(ObjectNode document, Collection<Integer> registryVersions) {
        Set<Integer> nonexistent = new HashSet<>(documentVersions(document));
        nonexistent.removeAll(registryVersions);
        return nonexistent;
    }

    /**
     * The barrier itself. Production throws a checked {@code MigrationException} here; this copy throws an unchecked
     * one of its own so the frozen code stays self-contained. The message is the production literal, which is what
     * reaches the import row.
     */
    static void requireImportable(ObjectNode document, Collection<Integer> registryVersions) {
        Set<Integer> nonexistentVersions = unknownVersions(document, registryVersions);
        if (!nonexistentVersions.isEmpty()) {
            throw new FrozenMigrationException(NEWER_VERSION_MESSAGE, nonexistentVersions);
        }
    }

    /**
     * {@code content.migrations} first, then a root {@code migrations}: the two {@code VersionsGetterStrategy}
     * implementations a service document reaches, in their {@code @Order}. The legacy flat export writes the field at
     * the root, every current-format one under {@code content}.
     *
     * <p>Literals, not the {@code ImportFileMigration} constants. Reading the constants would let a rename in today's
     * code rewrite this reader, and it would then measure a document shape no version ever wrote.
     */
    private static List<Integer> documentVersions(ObjectNode document) {
        JsonNode content = document.get("content");
        JsonNode migrations = content != null && content.isObject() ? content.get("migrations") : null;
        if (migrations == null) {
            migrations = document.get("migrations");
        }
        if (migrations == null) {
            // VersionsGetterService.getVersions when no strategy answers.
            throw new IllegalStateException("Failed to get a migration data");
        }
        return Arrays.stream(migrations.asText().replaceAll("[\\[\\]]", "").split(","))
                .map(String::trim)
                .filter(text -> !text.isEmpty())
                .map(Integer::parseInt)
                .toList();
    }

    /** Stands in for the checked {@code MigrationException} the barrier throws. */
    static final class FrozenMigrationException extends RuntimeException {
        private final transient Set<Integer> nonexistentVersions;

        FrozenMigrationException(String message, Set<Integer> nonexistentVersions) {
            super(message);
            this.nonexistentVersions = nonexistentVersions;
        }

        Set<Integer> getNonexistentVersions() {
            return nonexistentVersions;
        }
    }
}
