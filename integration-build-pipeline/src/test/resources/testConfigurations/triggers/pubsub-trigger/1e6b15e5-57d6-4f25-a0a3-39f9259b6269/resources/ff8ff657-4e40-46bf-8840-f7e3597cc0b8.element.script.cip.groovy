import org.apache.camel.component.google.pubsub.GooglePubsubConstants
import org.apache.camel.component.google.pubsub.consumer.GooglePubsubAcknowledge

Map attributes = exchange.getMessage().getHeader(GooglePubsubConstants.ATTRIBUTES, Map)
String messageId = exchange.getMessage().getHeader(GooglePubsubConstants.MESSAGE_ID, String)
Map attempts = exchange.getContext().getRegistry().lookupByName('snapshotPubSubAttempts')
int attempt = attempts.merge(messageId, 1, { previous, next -> previous + next })
exchange.getMessage().setHeader('snapshotDeliveryAttempt', attempt)
if (attributes.get('snapshotFailOnce') == 'true' && attempt == 1) {
    throw new IllegalStateException('Pub/Sub snapshot script failure')
}
GooglePubsubAcknowledge acknowledge = exchange.getMessage().getHeader(
        GooglePubsubConstants.GOOGLE_PUBSUB_ACKNOWLEDGE, GooglePubsubAcknowledge)
if (acknowledge != null) {
    String action = attributes.get('snapshotAckAction') ?: 'ack'
    if (action == 'nack-once' && attempt == 1) {
        acknowledge.nack(exchange)
    } else if (action != 'hold-once' || attempt > 1) {
        acknowledge.ack(exchange)
    }
}
exchange.getMessage().setBody('Triggered')
