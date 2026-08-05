package org.qubership.integration.platform.runtime.catalog.service.exportimport;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.GoldenServiceCorpus.LEGACY_FLAT;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.GoldenServiceCorpus.POST553;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.GoldenServiceCorpus.PRE553_CURRENT;

/**
 * Regenerates the golden corpus described by {@link GoldenServiceCorpus} into {@code src/test/resources}.
 *
 * <p>Not a test — the class name is outside Surefire's include patterns, so the suite never runs it. Run one method
 * explicitly:
 *
 * <pre>mvn -pl runtime-catalog test -Dtest=GoldenCorpusCapture#capturePost553 -DfailIfNoTests=false</pre>
 *
 * <p>{@link #capturePre553Current()} is a historical snapshot: it only produces the pre-#553 format on a checkout that
 * predates #553, and asserts that, so a later run fails loudly instead of overwriting the baseline with today's format.
 */
class GoldenCorpusCapture {

    @Test
    void capturePre553Current() throws IOException {
        Path target = capture(false, PRE553_CURRENT);

        assertTrue(exists(target, "svc-external.service.qip.yaml"),
                "This checkout already writes the post-#553 file names, so it cannot reproduce the pre-#553 baseline. "
                        + "Restore the captured set from git instead of regenerating it.");
    }

    @Test
    void captureLegacyFlat() throws IOException {
        Path target = capture(true, LEGACY_FLAT);

        assertTrue(exists(target, "service-svc-external.yaml"), "the legacy format writes the flat service- prefix");
    }

    @Test
    void capturePost553() throws IOException {
        Path target = capture(false, POST553);

        assertTrue(exists(target, "svc-external.external-service.qip.yaml"),
                "This checkout still writes the pre-#553 file names, so there is no post-#553 set to capture.");
    }

    private static Path capture(boolean legacy, String setName) throws IOException {
        Path target = GoldenServiceCorpus.sourceSet(setName);
        GoldenServiceCorpus.unzipInto(GoldenServiceCorpus.archive(legacy), target);
        return target;
    }

    private static boolean exists(Path setRoot, String serviceFileName) {
        return java.nio.file.Files.exists(setRoot
                .resolve(ExportImportConstants.ARCH_PARENT_DIR)
                .resolve(GoldenServiceCorpus.EXTERNAL_SERVICE_ID)
                .resolve(serviceFileName));
    }
}
