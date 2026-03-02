package org.qubership.integration.platform.engine.service.deployment.processing.actions.context.before;

import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.pubsub.v1.SubscriptionAdminClient;
import com.google.cloud.pubsub.v1.SubscriptionAdminSettings;
import com.google.cloud.pubsub.v1.TopicAdminClient;
import com.google.cloud.pubsub.v1.TopicAdminSettings;
import com.google.pubsub.v1.SubscriptionName;
import com.google.pubsub.v1.TopicName;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.spring.SpringCamelContext;
import org.qubership.integration.platform.engine.model.ChainElementType;
import org.qubership.integration.platform.engine.model.ElementOptions;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.model.deployment.update.ElementProperties;
import org.qubership.integration.platform.engine.service.VariablesService;
import org.qubership.integration.platform.engine.service.deployment.processing.ElementProcessingAction;
import org.qubership.integration.platform.engine.service.deployment.processing.qualifiers.OnBeforeDeploymentContextCreated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.Map;

import static com.google.auth.oauth2.ServiceAccountCredentials.fromStream;

@Slf4j
@Component
@ConditionalOnProperty(
        name = "qip.camel.component.pubsub.predeploy-check-enabled",
        havingValue = "true",
        matchIfMissing = true
)
@OnBeforeDeploymentContextCreated
public class PubSubConnectionCheckAction extends ElementProcessingAction {

    private final VariablesService variablesService;

    @Autowired
    public PubSubConnectionCheckAction(VariablesService variablesService) {
        this.variablesService = variablesService;
    }

    @Override
    public boolean applicableTo(ElementProperties properties) {
        ChainElementType elementType = ChainElementType.fromString(
                properties.getProperties().get(CamelConstants.ChainProperties.ELEMENT_TYPE)
        );
        return ChainElementType.isPubSubElement(elementType);
    }

    @Override
    public void apply(SpringCamelContext context, ElementProperties properties, DeploymentInfo deploymentInfo) {
        ChainElementType elementType = ChainElementType.fromString(
                properties.getProperties().get(CamelConstants.ChainProperties.ELEMENT_TYPE)
        );

        try {
            Map<String, String> props = properties.getProperties();

            String destinationName = getProp(props, ElementOptions.DESTINATION_NAME);
            String projectId = getProp(props, ElementOptions.PROJECT_ID);
            String serviceAccountKey = getProp(props, ElementOptions.SERVICE_ACCOUNT_KEY);
            byte[] serviceAccountDecodedKey = Base64.getDecoder().decode(serviceAccountKey);
            ServiceAccountCredentials credentials = fromStream(new ByteArrayInputStream(serviceAccountDecodedKey));

            if (ChainElementType.PUBSUB_TRIGGER.equals(elementType)) {
                SubscriptionName subscriptionName = SubscriptionName.newBuilder()
                        .setProject(projectId)
                        .setSubscription(destinationName)
                        .build();
                SubscriptionAdminSettings settings = SubscriptionAdminSettings.newBuilder().setCredentialsProvider(
                        () -> credentials
                ).build();
                try (SubscriptionAdminClient client = SubscriptionAdminClient.create(settings)) {
                    client.getSubscription(subscriptionName);
                }
            } else if (ChainElementType.PUBSUB_SENDER.equals(elementType)) {
                TopicName topicName = TopicName.of(projectId, destinationName);
                TopicAdminSettings settings = TopicAdminSettings.newBuilder()
                        .setCredentialsProvider(() -> credentials)
                        .build();
                try (TopicAdminClient client = TopicAdminClient.create(settings)) {
                    client.getTopic(topicName);
                }
            }
        } catch (Exception e) {
            // it is not retriable case
            log.error("Deployment is failed because PubSub predeploy check is failed, subscription or topic is not found in Google Cloud PubSub", e);
            throw new RuntimeException("Deployment is failed because PubSub predeploy check is failed, subscription or topic is not found in Google Cloud PubSub", e);
        }
    }

    private String getProp(Map<String, String> properties, String name) {
        return variablesService.injectVariables(properties.get(name));
    }
}
