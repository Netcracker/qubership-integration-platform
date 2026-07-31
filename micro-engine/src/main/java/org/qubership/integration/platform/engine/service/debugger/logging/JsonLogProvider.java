package org.qubership.integration.platform.engine.service.debugger.logging;

import io.quarkiverse.loggingjson.JsonGenerator;
import io.quarkiverse.loggingjson.JsonProvider;
import io.quarkiverse.loggingjson.providers.KeyValueStructuredArgument;
import jakarta.inject.Singleton;
import org.jboss.logmanager.ExtLogRecord;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.Map;

@Singleton
public class JsonLogProvider implements JsonProvider {
    private static final Map<String, String> MDC_MAPPING = Map.of(
            "requestId", "request_id",
            "X-B3-TraceId", "traceId",
            "X-B3-SpanId", "spanId",
            "logType", "log_type",
            "internalProperty_sessionId", "session_id",
            "chainId", "chain_id",
            "chainName", "chain",
            "elementId", "chain_element_id",
            "elementName", "chain_element");

    @Override
    public void writeTo(JsonGenerator generator, ExtLogRecord event) throws IOException {
        generator.writeStringField("time", event.getInstant().toString());
        generator.writeStringField("level", event.getLevel().toString());
        generator.writeStringField("thread", event.getThreadName());
        generator.writeStringField("class", event.getLoggerClassName());
        if (event.getSourceMethodName() != null) {
            generator.writeStringField("method", event.getSourceMethodName());
        }
        generator.writeStringField("message", event.getFormattedMessage());

        writeMdcFields(generator, event);

        Object[] parameters = event.getParameters();
        if (parameters != null) {
            for (Object param : parameters) {
                if (param instanceof KeyValueStructuredArgument kv) {
                    kv.writeTo(generator);
                }
            }
        }

        writeStacktrace(generator, event);
    }

    private void writeMdcFields(JsonGenerator generator, ExtLogRecord event) {
        Map<String, String> mdc = event.getMdcCopy();
        if (mdc != null) {
            MDC_MAPPING.forEach((key, value) -> {
                if (mdc.containsKey(key)) {
                    try {
                        generator.writeStringField(value, mdc.get(key));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            });
        }
    }

    private void writeStacktrace(JsonGenerator generator, ExtLogRecord event) throws IOException {
        if (event.getThrown() != null) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            event.getThrown().printStackTrace(pw);
            String fullStackTrace = sw.toString();

            generator.writeStringField("stacktrace", fullStackTrace);
        }
    }
}
