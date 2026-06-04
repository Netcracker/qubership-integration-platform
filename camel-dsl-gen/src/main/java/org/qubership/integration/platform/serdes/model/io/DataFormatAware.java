package org.qubership.integration.platform.serdes.model.io;

import org.jetbrains.annotations.NotNull;

public interface DataFormatAware {
    @NotNull
    DataFormat getDataFormat();
}
