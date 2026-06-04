package org.qubership.integration.platform.serdes.impl.io.factory;

import org.qubership.integration.platform.serdes.model.io.DataFormat;

import java.util.Collection;
import java.util.Optional;

public final class DataFormatHelper {
    private DataFormatHelper() {
    }

    public static <T> Optional<T> findObjectForDataFormat(Collection<T> objects, DataFormat dataFormat) {
        return objects.stream()
            .filter(obj -> getForDataFormat(obj)
                .map(dataFormat::equals)
                .orElse(false))
            .findAny();
    }

    public static <T> Optional<DataFormat> getForDataFormat(T obj) {
        ForDataFormat forDataFormat = obj.getClass().getAnnotation(ForDataFormat.class);
        return Optional.ofNullable(forDataFormat).map(ForDataFormat::value);
    }
}
