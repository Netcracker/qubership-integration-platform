package org.qubership.integration.platform.serdes.impl.io.factory;

import org.qubership.integration.platform.serdes.model.io.DataFormat;

import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface ForDataFormat {
    DataFormat value();
}
