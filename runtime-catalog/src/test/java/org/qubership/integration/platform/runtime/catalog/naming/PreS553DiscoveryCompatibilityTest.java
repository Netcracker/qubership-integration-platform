package org.qubership.integration.platform.runtime.catalog.naming;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.GoldenServiceCorpus;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a Runtime Catalog from before #553 discovers in an archive this one writes.
 *
 * <p>Nothing else measures this. Every other compatibility assertion in the suite is written in terms of the current
 * discovery code, which knows the per-type postfixes as a read-only format — so it answers what *this* version finds,
 * and the question here is what an older one finds.
 *
 * <p>The predicate below is a <b>frozen copy</b> of {@code ExportImportUtils.extractSystemsFromImportDirectory} as it
 * stood at {@code 8285c644b~1}. <b>Never update it to match current code.</b> Updating it to make a case pass deletes
 * the only thing this class measures — it would then compare this version against itself, which the rest of the suite
 * already does.
 */
class PreS553DiscoveryCompatibilityTest {

    /**
     * The postfix an old import passed for plain services. It has to be named per run: the frozen predicate takes it
     * as a parameter, and its {@code service-} branch is unconditional, so "the exact list it finds" has no answer
     * without one. Today's code gates that branch behind {@code scansPlainServices}.
     */
    private static final String PLAIN_POSTFIX = ExportImportConstants.SERVICE_YAML_NAME_POSTFIX;

    private static final String CONTEXT_POSTFIX = ExportImportConstants.CONTEXT_SERVICE_YAML_NAME_POSTFIX;

    static Stream<Arguments> discoveryCases() {
        return Stream.of(
                // An old QIP scanning a current archive for plain services finds all three, because the name is the
                // one it has always known. #553 renamed it and this row read `List.of()` — the files were there and
                // its predicate walked past them, with no error and no warning. Moving the type into `$schema` gave
                // the name back, and with it the discovery.
                //
                // What such an import then makes of the files is a separate question this class does not ask: it
                // reads no type from a document whose `content.integrationSystemType` is absent, and V105 is a
                // version it does not know. Discovery is the half that used to fail in silence.
                Arguments.of(GoldenServiceCorpus.POST553, PLAIN_POSTFIX, List.of(
                        "services/svc-external/svc-external.service.qip.yaml",
                        "services/svc-implemented/svc-implemented.service.qip.yaml",
                        "services/svc-internal/svc-internal.service.qip.yaml")),

                // The same scan over a pre-#553 archive, which is now the same set of names, file for file.
                Arguments.of(GoldenServiceCorpus.PRE553_CURRENT, PLAIN_POSTFIX, List.of(
                        "services/svc-external/svc-external.service.qip.yaml",
                        "services/svc-implemented/svc-implemented.service.qip.yaml",
                        "services/svc-internal/svc-internal.service.qip.yaml")),

                // The downgrade. Only this row supports "an older QIP can import the legacy archive", and only for
                // the three plain services: the context and MCP files sit right beside them and are not found.
                Arguments.of(GoldenServiceCorpus.LEGACY_FLAT, PLAIN_POSTFIX, List.of(
                        "services/svc-external/service-svc-external.yaml",
                        "services/svc-implemented/service-svc-implemented.yaml",
                        "services/svc-internal/service-svc-internal.yaml")),

                // The context scan finds its file in both current-format trees: #553 did not touch that name.
                Arguments.of(GoldenServiceCorpus.POST553, CONTEXT_POSTFIX, List.of(
                        "services/ctx-golden/ctx-golden.context-service.qip.yaml")),
                Arguments.of(GoldenServiceCorpus.PRE553_CURRENT, CONTEXT_POSTFIX, List.of(
                        "services/ctx-golden/ctx-golden.context-service.qip.yaml")),

                // The context scan over the legacy archive finds the three *plain* services and not the context file
                // it asked for. Two facts at once: `context-service-<id>.yaml` is discovered by nothing, in any
                // version, and the old predicate's `service-` branch ignored the postfix it was handed.
                // Supports: "a context service does not survive the downgrade, and the archive gives no warning."
                Arguments.of(GoldenServiceCorpus.LEGACY_FLAT, CONTEXT_POSTFIX, List.of(
                        "services/svc-external/service-svc-external.yaml",
                        "services/svc-implemented/service-svc-implemented.yaml",
                        "services/svc-internal/service-svc-internal.yaml")));
    }

    @ParameterizedTest(name = "{0} scanned for {1}")
    @MethodSource("discoveryCases")
    @DisplayName("a pre-#553 import discovers exactly these files")
    void preS553DiscoveryPartition(String setName, String postfix, List<String> expectedFound) {
        Path root = GoldenServiceCorpus.set(setName);
        List<String> all = GoldenServiceCorpus.relativeFileNames(root);
        assertTrue(all.containsAll(expectedFound),
                () -> "the expected list names files the " + setName + " tree does not hold: " + expectedFound);

        List<String> found = frozenDiscovery(root, postfix).stream()
                .map(file -> root.relativize(file.toPath()).toString())
                .sorted()
                .toList();

        // One assertion, not two: the missed half is `all` minus `found` by construction, so asserting it as well
        // would restate what this line already settled and could never fail on its own.
        assertEquals(expectedFound.stream().sorted().toList(), found,
                () -> "a pre-#553 import of " + setName + " scanned for " + postfix + " finds a different set."
                        + " It misses " + all.stream().filter(name -> !found.contains(name)).sorted().toList());
    }

    // --- the frozen predicate ----------------------------------------------------------------------------------------

    /**
     * {@code ExportImportUtils.extractSystemsFromImportDirectory} at {@code 8285c644b~1}, verbatim apart from the
     * constants it read. Frozen on purpose. See the class comment before touching it.
     */
    private static List<File> frozenDiscovery(Path root, String yamlPostfix) {
        Path start = root.resolve(ExportImportConstants.ARCH_PARENT_DIR);
        if (!Files.exists(start)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(start)) {
            return walk.filter(Files::isRegularFile)
                    .map(Path::toFile)
                    // Literals, not constants. Reading SERVICE_YAML_NAME_PREFIX and YAML_EXTENSION would let a
                    // rename in today's code rewrite this predicate, and it would then measure a discovery no
                    // version ever performed. These are the values those constants held at that commit.
                    .filter(file -> (file.getName().startsWith("service-") && file.getName().endsWith("yaml"))
                                    || file.getName().contains(yamlPostfix))
                    .sorted(Comparator.comparing(File::getPath))
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
