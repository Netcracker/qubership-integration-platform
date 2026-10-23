import org.apache.camel.component.google.pubsub.GooglePubsubConstants

Map attributes = exchange.getMessage().getHeader(GooglePubsubConstants.ATTRIBUTES, Map)
String messageId = exchange.getMessage().getHeader(GooglePubsubConstants.MESSAGE_ID, String)
Map attempts = exchange.getContext().getRegistry().lookupByName('snapshotPubSubAttempts')
int attempt = attempts.merge(messageId, 1, { previous, next -> previous + next })
exchange.getMessage().setHeader('snapshotDeliveryAttempt', attempt)
exchange.getMessage().setHeader('snapshotMainExecuted', true)
if (attributes.get('snapshotFailOnce') == 'true' && attempt == 1) {
    throw new IllegalStateException('Pub/Sub snapshot script failure')
}
exchange.getMessage().setBody('Processed')
