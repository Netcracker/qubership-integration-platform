package org.qubership.integration.platform.runtime.catalog.service;

import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.ConfigParameterRepository;

import java.sql.Timestamp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConfigParameterServiceTest {

    @Mock
    private ConfigParameterRepository configParameterRepository;

    @InjectMocks
    private ConfigParameterService configParameterService;

    @ParameterizedTest
    @CsvSource({"1, true", "0, false"})
    void tryLockIsTakenOnlyWhenTheStatementChangedARow(int affectedRows, boolean expected) {
        Timestamp staleBefore = new Timestamp(0);
        when(configParameterRepository.acquireLock(anyString(), eq("ns"), eq("lock"), any(), eq(staleBefore)))
                .thenReturn(affectedRows);

        assertThat(configParameterService.tryLock("ns", "lock", staleBefore)).isEqualTo(expected);
    }
}
