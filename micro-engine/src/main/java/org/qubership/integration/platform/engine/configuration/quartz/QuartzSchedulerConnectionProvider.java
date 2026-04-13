package org.qubership.integration.platform.engine.configuration.quartz;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.inject.literal.NamedLiteral;
import jakarta.enterprise.inject.spi.CDI;
import org.quartz.utils.ConnectionProvider;

import java.sql.Connection;
import java.sql.SQLException;

public class QuartzSchedulerConnectionProvider implements ConnectionProvider {
    private AgroalDataSource dataSource;

    @Override
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public void shutdown() throws SQLException {

    }

    @Override
    public void initialize() throws SQLException {
        dataSource = CDI.current().select(AgroalDataSource.class, NamedLiteral.of("configs")).get();
    }
}
