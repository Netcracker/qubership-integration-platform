package org.qubership.integration.platform.io.readers.chain;

import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.CHAIN_YAML_NAME_POSTFIX;
import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.CHAIN_YAML_NAME_PREFIX;

public final class ChainFileUtil {
    private ChainFileUtil() {}

    public static boolean isChainFile(String fileName) {
        return (fileName.endsWith(".yaml") || fileName.endsWith(".yml"))
            && (fileName.startsWith(CHAIN_YAML_NAME_PREFIX) || fileName.contains(CHAIN_YAML_NAME_POSTFIX));
    }
}
