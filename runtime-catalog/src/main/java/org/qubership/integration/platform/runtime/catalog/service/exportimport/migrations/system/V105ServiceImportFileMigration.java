package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.system;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Stamps version 105 onto exported service documents and changes nothing inside them.
 *
 * <p>Version 105 is the release that moved a service's type out of {@code content.integrationSystemType} and into the
 * file name. Nothing has to be migrated forward for that: {@code ServiceDeserializer.resolveServiceType} takes the type
 * from the file name and falls back to the field, and it does so for every document it deserializes, whether or not any
 * migration ran. {@code makeMigration} therefore returns the node untouched. The version stamp is the whole point of
 * this class; delete the class as an empty migration and the compatibility barrier below goes with it.
 *
 * <p>That barrier is narrower than "an older QIP rejects an archive written after #553", and the release note has to
 * say so. {@code FileMigrationService.migrate} throws on a claimed version it does not know, and
 * {@code SystemExportImportService.importOneSystemInTransaction} catches that and reports the one service as
 * {@code ImportSystemStatus.ERROR}; the rest of the archive still imports. An older QIP also sees only what its own
 * discovery finds, and {@code ExportImportUtils.extractSystemsFromImportDirectory} matches the {@code service-} prefix
 * and the {@code .service.} postfix, neither of which a per-type name such as
 * {@code <id>.external-service.<app>.yaml} contains. A plain service written after #553 is therefore invisible to an
 * older QIP rather than rejected by it: no row in the import result, no error. What does trip the barrier is every
 * document an older QIP still discovers: context services, which keep the {@code .context-service.} name and are
 * stamped 105 from this same migration list by {@code ContextServiceDtoMapper}, and anything exported under the legacy
 * flat name. In a mixed archive that erroring context service is the only visible sign the archive came from a newer
 * QIP. The safety property holds either way: no service without a type is ever persisted.
 */
@Slf4j
@Component
public class V105ServiceImportFileMigration implements ServiceImportFileMigration {

    @Override
    public int getVersion() {
        return 105;
    }

    @Override
    public boolean isIdempotent() {
        // A no-op is always safe to re-run, so the rollout path runs it instead of claiming it as applied.
        return true;
    }

    @Override
    public ObjectNode makeMigration(ObjectNode fileNode) {
        log.debug("Applying service migration: {}", getVersion());
        return fileNode;
    }
}
