package org.qubership.integration.platform.io.writers.camel.xml.templates.helpers;

import com.github.jknack.handlebars.Helper;
import com.github.jknack.handlebars.Options;
import org.qubership.integration.platform.io.writers.camel.xml.templates.TemplatesHelper;

import java.io.IOException;
import java.util.Map;
import java.util.stream.StreamSupport;

/**
 * Renders the block unless the {@code after} property holds a response handler with the
 * {@code default} code. The property may be a list or, in legacy data, a single object.
 */
@TemplatesHelper("unless-default-handler")
public class UnlessDefaultHandlerHelper extends BaseHelper implements Helper<Object> {

    @Override
    public Object apply(Object context, Options options) throws IOException {
        Object after = getPropertyValue("after", options);
        boolean hasDefault = after instanceof Iterable<?> handlers
                ? StreamSupport.stream(handlers.spliterator(), false).anyMatch(this::isDefault)
                : isDefault(after);
        return hasDefault ? options.inverse() : options.fn();
    }

    private boolean isDefault(Object handler) {
        return handler instanceof Map<?, ?> map && "default".equals(map.get("code"));
    }
}
