package org.qubership.integration.platform.runtime.catalog.service.exportimport;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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
 * <p>{@value GoldenServiceCorpus#PRE553_CURRENT} and {@value GoldenServiceCorpus#LEGACY_FLAT} are historical
 * snapshots: only a checkout that predates #553 can produce them. Every method here checks the archive it built
 * <b>before</b> writing anything, so running the wrong one on the wrong checkout fails without touching the baseline.
 */
class GoldenCorpusCapture {

    private static final String PRE553_ONLY = "This checkout writes the post-#553 service file names, so it cannot"
            + " reproduce a pre-#553 baseline. Restore the captured set from git instead of regenerating it.";

    @Test
    void capturePre553Current() throws IOException {
        byte[] archive = GoldenServiceCorpus.archive(false);
        assertTrue(carries(archive, "svc-external.service.qip.yaml"), PRE553_ONLY);

        write(archive, PRE553_CURRENT);
    }

    @Test
    void captureLegacyFlat() throws IOException {
        // The legacy format is unchanged by #553, so its own file names prove nothing about the checkout. What does
        // is the current-format exporter alongside it: only a pre-#553 one still writes the plain `.service.` name.
        assertTrue(carries(GoldenServiceCorpus.archive(false), "svc-external.service.qip.yaml"), PRE553_ONLY);
        byte[] archive = GoldenServiceCorpus.archive(true);
        assertTrue(carries(archive, "service-svc-external.yaml"), "the legacy format writes the flat service- prefix");

        write(archive, LEGACY_FLAT);
    }

    @Test
    void capturePost553() throws IOException {
        byte[] archive = GoldenServiceCorpus.archive(false);
        assertTrue(carries(archive, "svc-external.external-service.qip.yaml"),
                "This checkout still writes the pre-#553 file names, so there is no post-#553 set to capture.");

        write(archive, POST553);
    }

    private static void write(byte[] archive, String setName) throws IOException {
        Path target = GoldenServiceCorpus.sourceSet(setName);
        GoldenServiceCorpus.unzipInto(archive, target);
    }

    private static boolean carries(byte[] archive, String fileName) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (entry.getName().endsWith("/" + fileName)) {
                    return true;
                }
            }
        }
        return false;
    }
}
