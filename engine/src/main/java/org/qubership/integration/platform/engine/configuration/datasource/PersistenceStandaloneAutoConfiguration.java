package org.qubership.integration.platform.engine.configuration.datasource;

import com.zaxxer.hikari.HikariDataSource;
import org.qubership.integration.platform.engine.configuration.datasource.properties.HikariConfigProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

@AutoConfiguration
@ConditionalOnProperty(name = "qip.standalone", havingValue = "true")
@EnableConfigurationProperties(HikariConfigProperties.class)
public class PersistenceStandaloneAutoConfiguration {

    private final HikariConfigProperties properties;

    @Autowired
    public PersistenceStandaloneAutoConfiguration(HikariConfigProperties properties) {
        this.properties = properties;
    }

    @Bean("checkpointDataSource")
    public DataSource checkpointDataSource() {
        return new HikariDataSource(properties.getDatasource("checkpoints-datasource"));
    }

    @Primary
    @Bean("qrtzDataSource")
    public DataSource qrtzDataSource() {
        return new HikariDataSource(properties.getDatasource("qrtz-datasource"));
    }
}
