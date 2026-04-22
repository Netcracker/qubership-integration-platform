package org.qubership.integration.platform.engine.camel.dsl;

import org.apache.camel.support.ResourceSupport;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

public class SimpleResource extends ResourceSupport {
    private final byte[] content;

    public SimpleResource(String scheme, String location, byte[] content) {
        super(scheme, location);
        this.content = content;
    }

    @Override
    public boolean exists() {
        return true;
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return new ByteArrayInputStream(content);
    }
}
