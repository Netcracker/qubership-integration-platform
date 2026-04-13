package org.qubership.integration.platform.engine.configuration.opensearch;

import com.netcracker.cloud.dbaas.client.management.classifier.DbaaSClassifierBuilder;
import com.netcracker.cloud.dbaas.common.classifier.DbaaSClassifierFactory;
import com.netcracker.cloud.dbaas.common.classifier.TenantClassifierBuilder;
import jakarta.enterprise.inject.Produces;

import java.util.Map;

import static com.netcracker.cloud.dbaas.client.DbaasConst.LOGICAL_DB_NAME;

public class DbaasOpenSearchClassifierFactoryProducer {
    @Produces
    DbaaSClassifierFactory opensearchClassifierFactory() {
        return new DbaaSClassifierFactory() {
            @Override
            public DbaaSClassifierBuilder newTenantClassifierBuilder(Map<String, Object> primaryClassifier) {
                return new TenantClassifierBuilder(primaryClassifier)
                        .withCustomKey(LOGICAL_DB_NAME, "sessions");
            }

            @Override
            public DbaaSClassifierBuilder newServiceClassifierBuilder(Map<String, Object> primaryClassifier) {
                return super.newServiceClassifierBuilder(primaryClassifier);
            }
        };
    }
}
