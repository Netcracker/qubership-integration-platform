package org.qubership.integration.platform.ai.flow.persistence;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.serverlessworkflow.impl.persistence.PersistenceInstanceHandlers;
import jakarta.enterprise.inject.Instance;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class FlowPersistenceReadinessTest {

  @Test
  void invalidDatasourceFailsWithClearEnglishPersistenceError() throws Exception {
    DataSource broken = mock(DataSource.class);
    when(broken.getConnection()).thenThrow(new SQLException("Connection to localhost:1 refused"));
    FlowPersistenceReadiness readiness = new FlowPersistenceReadiness(broken, present());
    FlowPersistenceException failure =
        assertThrows(FlowPersistenceException.class, readiness::ping);
    assertTrue(failure.getMessage().startsWith("Flow persistence failed:"));
    assertTrue(failure.getMessage().contains("unable to ping Flow datasource"));
    assertFalse(failure.getMessage().isBlank());
  }

  @Test
  void missingJpaProviderFailsClosed() {
    DataSource dataSource = mock(DataSource.class);
    @SuppressWarnings("unchecked")
    Instance<PersistenceInstanceHandlers> absent = mock(Instance.class);
    when(absent.isUnsatisfied()).thenReturn(true);
    FlowPersistenceReadiness readiness = new FlowPersistenceReadiness(dataSource, absent);
    FlowPersistenceException failure =
        assertThrows(FlowPersistenceException.class, readiness::ping);
    assertTrue(failure.getMessage().startsWith("Flow persistence failed:"));
    assertTrue(failure.getMessage().contains("JPA persistence provider is not available"));
  }

  @SuppressWarnings("unchecked")
  private static Instance<PersistenceInstanceHandlers> present() {
    Instance<PersistenceInstanceHandlers> instance = mock(Instance.class);
    when(instance.isUnsatisfied()).thenReturn(false);
    when(instance.get()).thenReturn(mock(PersistenceInstanceHandlers.class));
    return instance;
  }
}
