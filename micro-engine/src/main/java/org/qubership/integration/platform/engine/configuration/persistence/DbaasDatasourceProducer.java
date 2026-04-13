package org.qubership.integration.platform.engine.configuration.persistence;

import com.netcracker.cloud.core.quarkus.dbaas.datasource.DbaaSDataSource;
import com.netcracker.cloud.core.quarkus.dbaas.datasource.service.DbaaSPostgresDbCreationService;
import com.netcracker.cloud.dbaas.common.classifier.TenantClassifierBuilder;
import io.agroal.api.AgroalDataSource;
import io.quarkus.arc.profile.IfBuildProfile;
import io.smallrye.common.constraint.NotNull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Collections;

import static com.netcracker.cloud.dbaas.client.DbaasConst.*;

@IfBuildProfile("dbaas")
public class DbaasDatasourceProducer {
    @ConfigProperty(name = "cloud.microservice.name")
    String microserviceName;

    @ConfigProperty(name = "cloud.microservice.namespace")
    String namespace;

    @Produces
    @Named("configs")
    @ApplicationScoped
    public AgroalDataSource configsDataSource(@NotNull DbaaSPostgresDbCreationService dataSourceCreationService) {
        return new DbaaSDataSource(
                new TenantClassifierBuilder(Collections.emptyMap())
                        .withProperty(NAMESPACE, namespace)
                        .withProperty(MICROSERVICE_NAME, microserviceName)
                        .withCustomKey(LOGICAL_DB_NAME, "configs"),
                dataSourceCreationService
        );
    }
}
