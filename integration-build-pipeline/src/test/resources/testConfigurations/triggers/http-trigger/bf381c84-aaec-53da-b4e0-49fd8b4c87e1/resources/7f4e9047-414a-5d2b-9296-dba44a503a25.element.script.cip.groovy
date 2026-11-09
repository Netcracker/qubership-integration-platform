import java.nio.charset.StandardCharsets

exchange.getMessage().setHeader('Content-Type', 'application/octet-stream')
exchange.getMessage().setBody(new ByteArrayInputStream('Wire café'.getBytes(StandardCharsets.UTF_8)))
