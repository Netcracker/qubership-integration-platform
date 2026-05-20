package org.qubership.integration.platform.engine.component.profile;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import org.eclipse.microprofile.config.ConfigProvider;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;

@Alternative
@Priority(1)
@ApplicationScoped
public class TestConfigsDataSourceProducer {

    @Produces
    @ApplicationScoped
    @Named("configs")
    public DataSource configsDataSource() {
        var config = ConfigProvider.getConfig();

        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(config.getValue("tests.datasource.configs.jdbc.url", String.class));
        dataSource.setUser(config.getValue("tests.datasource.configs.username", String.class));
        dataSource.setPassword(config.getValue("tests.datasource.configs.password", String.class));

        return dataSource;
    }
}
