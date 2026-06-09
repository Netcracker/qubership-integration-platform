package org.qubership.integration.platform.io.impl.factory;

import org.qubership.integration.platform.io.model.DataFormat;

import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface ForDataFormat {
    DataFormat value();
}
