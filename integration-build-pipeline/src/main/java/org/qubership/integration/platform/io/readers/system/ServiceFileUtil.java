package org.qubership.integration.platform.io.readers.system;

import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.*;

public final class ServiceFileUtil {
    private ServiceFileUtil() {}

    public static boolean isIntegrationSystemFile(String fileName) {
        return (fileName.endsWith(".yaml") || fileName.endsWith(".yml"))
            && (fileName.startsWith(SERVICE_YAML_NAME_PREFIX)
                || fileName.contains(SERVICE_YAML_NAME_POSTFIX)
            );
    }
}
