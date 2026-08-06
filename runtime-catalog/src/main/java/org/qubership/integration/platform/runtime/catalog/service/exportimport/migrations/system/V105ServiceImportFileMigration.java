package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.system;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Stamps version 105 onto exported service documents and changes nothing inside them.
 *
 * <p><b>The no-op is the point.</b> Version 105 moved a service's type out of {@code content.integrationSystemType}
 * and into the file name, and nothing has to be migrated forward for that:
 * {@code ServiceDeserializer.resolveServiceType} runs on every document it deserializes, migrated or not. The version
 * stamp is the whole reason this class exists; delete it as an empty migration and the compatibility barrier goes too.
 *
 * <p>That barrier is narrower than "an older QIP rejects a newer archive". Such a QIP discovers only the
 * {@code service-} prefix and the {@code .service.} postfix, so a per-type name such as
 * {@code <id>.external-service.<app>.yaml} is invisible to it rather than reported: no row, no error. What does trip
 * the barrier is every document it still discovers, namely context services (stamped 105 from this same list by
 * {@code ContextServiceDtoMapper}) and anything exported under the legacy flat name. Those it refuses one service at a
 * time, so the rest of the archive still imports.
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
