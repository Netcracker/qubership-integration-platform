package org.qubership.integration.platform.ai.config.persistence;

import static com.netcracker.cloud.dbaas.client.DbaasConst.LOGICAL_DB_NAME;
import static com.netcracker.cloud.dbaas.client.DbaasConst.MICROSERVICE_NAME;
import static com.netcracker.cloud.dbaas.client.DbaasConst.NAMESPACE;

import com.netcracker.cloud.core.quarkus.dbaas.datasource.DbaaSDataSource;
import com.netcracker.cloud.core.quarkus.dbaas.datasource.service.DbaaSPostgresDbCreationService;
import com.netcracker.cloud.dbaas.common.classifier.TenantClassifierBuilder;
import io.agroal.api.AgroalDataSource;
import io.smallrye.common.constraint.NotNull;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Produces;
import java.util.Collections;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * PostgreSQL datasource for A2A task persistence via declarative DBaaS ({@code logicalDbName=ai_a2a}).
 *
 * <p>Replaces the default {@code DataSourceAggregator} datasource, which omits {@code logicalDbName}
 * and causes dbaas-agent 403. Hibernate ORM, Flow JPA, and JDBC use the unqualified default
 * {@link javax.sql.DataSource}.
 */
@Alternative
@Priority(10)
public class DbaasDatasourceProducer {

  private static final String A2A_LOGICAL_DB_NAME = "ai_a2a";

  @ConfigProperty(name = "cloud.microservice.name")
  String microserviceName;

  @ConfigProperty(name = "cloud.microservice.namespace")
  String namespace;

  @Produces
  @Default
  @ApplicationScoped
  public AgroalDataSource aiA2aDataSource(@NotNull DbaaSPostgresDbCreationService dataSourceCreationService) {
    return new DbaaSDataSource(
        new TenantClassifierBuilder(Collections.emptyMap())
            .withProperty(NAMESPACE, namespace)
            .withProperty(MICROSERVICE_NAME, microserviceName)
            .withCustomKey(LOGICAL_DB_NAME, A2A_LOGICAL_DB_NAME),
        dataSourceCreationService);
  }
}
