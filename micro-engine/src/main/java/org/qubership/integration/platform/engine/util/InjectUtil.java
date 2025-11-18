package org.qubership.integration.platform.engine.util;

import jakarta.enterprise.inject.AmbiguousResolutionException;
import jakarta.enterprise.inject.Instance;

import java.util.Optional;

public class InjectUtil {
    public static <T> Optional<T> injectOptional(Instance<T> instance) {
        if (instance.isAmbiguous()) {
            throw new AmbiguousResolutionException();
        }
        return instance.stream().findFirst();
    }
}
