def describe = { value ->
    switch (value) {
        case null:
            return 'null'
        case java.nio.ByteBuffer:
            def buffer = value.duplicate()
            byte[] bytes = new byte[buffer.remaining()]
            buffer.get(bytes)
            return 'java.nio.ByteBuffer:' + bytes.collect { byteValue -> byteValue & 0xFF }.join(',')
        case org.apache.kafka.common.utils.Bytes:
            def payload = value.get()
            return 'org.apache.kafka.common.utils.Bytes:' + payload.collect { byteValue -> byteValue & 0xFF }.join(',')
        default:
            return value.class.name + ':' + value.toString()
    }
}
exchange.message.body = describe(exchange.message.body) + '|key:' + describe(exchange.message.getHeader('kafka.KEY'))
