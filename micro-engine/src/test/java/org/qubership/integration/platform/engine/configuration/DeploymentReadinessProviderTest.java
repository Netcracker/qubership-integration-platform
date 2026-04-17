package org.qubership.integration.platform.engine.configuration;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.events.CommonVariablesUpdatedEvent;
import org.qubership.integration.platform.engine.events.SecuredVariablesUpdatedEvent;
import org.qubership.integration.platform.engine.events.UpdateEvent;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.util.DevModeUtil;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class DeploymentReadinessProviderTest {

    private final DeploymentReadinessProvider provider = new DeploymentReadinessProvider();

    @Mock
    private DevModeUtil devModeUtil;

    @Test
    void shouldReturnOnlyCommonVariablesUpdatedEventWhenDevModeIsEnabled() {
        when(devModeUtil.isDevMode()).thenReturn(true);

        EventClassesContainerWrapper wrapper = provider.deploymentReadinessEvents(devModeUtil);
        Set<Class<? extends UpdateEvent>> events = (Set<Class<? extends UpdateEvent>>) wrapper.getEventClasses();

        assertEquals(3, events.size());
        assertTrue(events.contains(CommonVariablesUpdatedEvent.class));
    }

    @Test
    void shouldReturnCommonAndSecuredVariablesUpdatedEventsWhenDevModeIsDisabled() {
        when(devModeUtil.isDevMode()).thenReturn(false);

        EventClassesContainerWrapper wrapper = provider.deploymentReadinessEvents(devModeUtil);
        Set<Class<? extends UpdateEvent>> events = (Set<Class<? extends UpdateEvent>>) wrapper.getEventClasses();

        assertEquals(4, events.size());
        assertTrue(events.contains(CommonVariablesUpdatedEvent.class));
        assertTrue(events.contains(SecuredVariablesUpdatedEvent.class));
    }

    @Test
    void shouldReturnUnmodifiableSetOfEvents() {
        when(devModeUtil.isDevMode()).thenReturn(false);

        EventClassesContainerWrapper wrapper = provider.deploymentReadinessEvents(devModeUtil);

        assertThrows(UnsupportedOperationException.class,
                () -> wrapper.getEventClasses().add(CommonVariablesUpdatedEvent.class));
    }
}
