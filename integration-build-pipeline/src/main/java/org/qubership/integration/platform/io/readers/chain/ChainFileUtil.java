package org.qubership.integration.platform.io.readers.chain;

public final class ChainFileUtil {
    private ChainFileUtil() {}

    public static boolean isChainFile(String fileName) {
        return (fileName.endsWith(".yaml") || fileName.endsWith(".yml"))
            && (fileName.startsWith("chain-") || fileName.contains(".chain."));
    }
}
