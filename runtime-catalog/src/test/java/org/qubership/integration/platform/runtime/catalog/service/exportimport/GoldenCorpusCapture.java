package org.qubership.integration.platform.runtime.catalog.service.exportimport;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.util.ExportImportUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.GoldenServiceCorpus.APP_NAME;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.GoldenServiceCorpus.EXTERNAL_SERVICE_ID;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.GoldenServiceCorpus.LEGACY_FLAT;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.GoldenServiceCorpus.POST553;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.GoldenServiceCorpus.POST553_DOTTED;
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

    private static final String PRE553_ONLY = "This checkout stamps the per-type service schemas, so it cannot"
            + " reproduce a pre-#553 baseline. Restore the captured set from git instead of regenerating it.";

    // The guards read the document, not the name. The name stopped telling the two checkouts apart when the type
    // moved into `$schema`: this checkout writes the same `<id>.service.<app>.yaml` a pre-#553 one did. What only a
    // pre-#553 checkout writes is the plain service schema on a plain service.
    private static final String PLAIN_SERVICE_SCHEMA = GoldenServiceCorpus.SCHEMAS.getService();

    private static final String EXTERNAL_SERVICE_SCHEMA = GoldenServiceCorpus.SCHEMAS.getExternalService();

    @Test
    void capturePre553Current() throws IOException {
        byte[] archive = GoldenServiceCorpus.archive(false);
        assertTrue(stamps(archive, PLAIN_SERVICE_SCHEMA), PRE553_ONLY);

        write(archive, PRE553_CURRENT);
    }

    @Test
    void captureLegacyFlat() throws IOException {
        // The legacy format is unchanged by #553, so its own file names prove nothing about the checkout. What does
        // is the current-format exporter alongside it: only a pre-#553 one stamps the plain service schema.
        assertTrue(stamps(GoldenServiceCorpus.archive(false), PLAIN_SERVICE_SCHEMA), PRE553_ONLY);
        byte[] archive = GoldenServiceCorpus.archive(true);
        assertTrue(carries(archive, legacyFileName()), "the legacy format writes the flat service- prefix");

        write(archive, LEGACY_FLAT);
    }

    @Test
    void capturePost553() throws IOException {
        byte[] archive = GoldenServiceCorpus.archive(false);
        assertTrue(stamps(archive, EXTERNAL_SERVICE_SCHEMA),
                "This checkout still stamps the pre-#553 service schema, so there is no post-#553 set to capture.");
        assertTrue(carries(archive, currentFormatFileName()), "the current format writes the type-less name");

        write(archive, POST553);
    }

    @Test
    void capturePost553Dotted() throws IOException {
        byte[] archive = GoldenServiceCorpus.archive(
                GoldenServiceCorpus.exportServices(List.of(GoldenServiceCorpus.dottedApiService()), false), false);
        assertTrue(carries(archive, dottedApiGroupFileName()),
                "This checkout no longer writes a dotted api group id verbatim, so there is no set to capture.");

        write(archive, POST553_DOTTED);
    }

    /** The name the current-format exporter writes today, so a renamed postfix moves the guard with it. */
    private static String currentFormatFileName() {
        return ExportImportUtils.generateMainSystemFileExportName(EXTERNAL_SERVICE_ID, APP_NAME, false);
    }

    private static String legacyFileName() {
        return ExportImportUtils.generateMainSystemFileExportName(EXTERNAL_SERVICE_ID, APP_NAME, true);
    }

    private static String dottedApiGroupFileName() {
        return ExportImportUtils.generateSpecificationGroupFileExportName(
                GoldenServiceCorpus.DOTTED_API_GROUP_ID, APP_NAME, false);
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

    /** Whether any document in the archive states {@code schemaUri} as its {@code $schema}. */
    private static boolean stamps(byte[] archive, String schemaUri) throws IOException {
        String stated = "$schema: \"" + schemaUri + "\"";
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (!entry.isDirectory() && new String(zip.readAllBytes(), UTF_8).contains(stated)) {
                    return true;
                }
            }
        }
        return false;
    }
}
