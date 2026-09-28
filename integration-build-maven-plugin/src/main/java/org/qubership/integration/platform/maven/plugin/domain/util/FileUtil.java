package org.qubership.integration.platform.maven.plugin.domain.util;

import org.apache.commons.lang3.function.FailableFunction;

import java.io.File;
import java.nio.file.Path;

public final class FileUtil {
    private FileUtil() {}

    public static boolean isInDirectory(Path path, Path directory) {
        Path p = path.normalize().toAbsolutePath();
        Path d = directory.normalize().toAbsolutePath();
        return p.startsWith(d);
    }

    public static <R, E extends Throwable> R processFile(File file, FailableFunction<File, R, E> processor) throws Exception {
        try {
            return processor.apply(file);
        } catch (Throwable error) {
            String message = String.format("%s: %s", file.getAbsolutePath(), error.getMessage());
            throw new Exception(message, error);
        }
    }
}
