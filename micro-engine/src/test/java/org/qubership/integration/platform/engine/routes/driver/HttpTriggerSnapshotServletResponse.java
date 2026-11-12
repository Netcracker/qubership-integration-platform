package org.qubership.integration.platform.engine.routes.driver;

import com.sun.net.httpserver.HttpExchange;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

final class HttpTriggerSnapshotServletResponse implements InvocationHandler {
    private final HttpExchange exchange;
    private final ByteArrayOutputStream body = new ByteArrayOutputStream();
    private final ServletOutputStream output = new ServletOutputStream() {
        @Override
        public void write(int value) {
            body.write(value);
        }

        @Override
        public void write(byte[] values, int offset, int length) {
            body.write(values, offset, length);
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(WriteListener listener) {
            throw new UnsupportedOperationException("The HTTP snapshot response uses blocking output.");
        }
    };
    private final HttpServletResponse response;
    private PrintWriter writer;
    private Charset charset = StandardCharsets.UTF_8;
    private int status = 200;
    private int bufferSize = 8192;
    private Long contentLength;
    private Locale locale = Locale.ROOT;
    private boolean committed;

    HttpTriggerSnapshotServletResponse(HttpExchange exchange) {
        this.exchange = exchange;
        response = (HttpServletResponse) Proxy.newProxyInstance(
                HttpServletResponse.class.getClassLoader(), new Class<?>[]{HttpServletResponse.class}, this);
    }

    HttpServletResponse response() {
        return response;
    }

    void finish() throws IOException {
        if (writer != null) {
            writer.flush();
        }
        byte[] bytes = body.toByteArray();
        long length = bytes.length == 0 ? -1 : contentLength == null ? 0 : contentLength;
        exchange.sendResponseHeaders(status, length);
        committed = true;
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] arguments) throws Exception {
        return switch (method.getName()) {
            case "toString" -> "Snapshot servlet response";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == arguments[0];
            case "setStatus" -> {
                status = (int) arguments[0];
                yield null;
            }
            case "getStatus" -> status;
            case "setHeader", "setIntHeader", "setDateHeader" -> {
                exchange.getResponseHeaders().set((String) arguments[0], String.valueOf(arguments[1]));
                yield null;
            }
            case "addHeader", "addIntHeader", "addDateHeader" -> {
                exchange.getResponseHeaders().add((String) arguments[0], String.valueOf(arguments[1]));
                yield null;
            }
            case "getHeader" -> exchange.getResponseHeaders().getFirst((String) arguments[0]);
            case "getHeaders" -> List.copyOf(exchange.getResponseHeaders().getOrDefault((String) arguments[0], List.of()));
            case "getHeaderNames" -> List.copyOf(exchange.getResponseHeaders().keySet());
            case "containsHeader" -> exchange.getResponseHeaders().containsKey((String) arguments[0]);
            case "setContentType" -> {
                exchange.getResponseHeaders().set("Content-Type", (String) arguments[0]);
                yield null;
            }
            case "getContentType" -> exchange.getResponseHeaders().getFirst("Content-Type");
            case "setCharacterEncoding" -> {
                charset = Charset.forName((String) arguments[0]);
                yield null;
            }
            case "getCharacterEncoding" -> charset.name();
            case "setContentLength", "setContentLengthLong" -> {
                contentLength = ((Number) arguments[0]).longValue();
                yield null;
            }
            case "getOutputStream" -> output;
            case "getWriter" -> {
                if (writer == null) {
                    writer = new PrintWriter(new OutputStreamWriter(body, charset));
                }
                yield writer;
            }
            case "setBufferSize" -> {
                bufferSize = (int) arguments[0];
                yield null;
            }
            case "getBufferSize" -> bufferSize;
            case "flushBuffer" -> {
                if (writer != null) {
                    writer.flush();
                }
                yield null;
            }
            case "isCommitted" -> committed;
            case "resetBuffer" -> {
                body.reset();
                yield null;
            }
            case "reset" -> {
                body.reset();
                exchange.getResponseHeaders().clear();
                status = 200;
                contentLength = null;
                yield null;
            }
            case "setLocale" -> {
                locale = (Locale) arguments[0];
                yield null;
            }
            case "getLocale" -> locale;
            case "encodeURL", "encodeRedirectURL" -> arguments[0];
            default -> throw new UnsupportedOperationException("Unsupported snapshot servlet response method: " + method.getName());
        };
    }
}
