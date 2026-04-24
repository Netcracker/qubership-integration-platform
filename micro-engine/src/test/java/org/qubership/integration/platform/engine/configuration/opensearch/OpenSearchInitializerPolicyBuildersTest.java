package org.qubership.integration.platform.engine.configuration.opensearch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.opensearch.ism.model.Conditions;
import org.qubership.integration.platform.engine.opensearch.ism.model.ISMTemplate;
import org.qubership.integration.platform.engine.opensearch.ism.model.Policy;
import org.qubership.integration.platform.engine.opensearch.ism.model.State;
import org.qubership.integration.platform.engine.opensearch.ism.model.Transition;
import org.qubership.integration.platform.engine.opensearch.ism.model.actions.DeleteAction;
import org.qubership.integration.platform.engine.opensearch.ism.model.actions.RolloverAction;
import org.qubership.integration.platform.engine.opensearch.ism.model.time.TimeValue;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.OpenSearchTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class OpenSearchInitializerPolicyBuildersTest {

    private final OpenSearchInitializer initializer = new OpenSearchInitializer();

    @Mock
    private OpenSearchProperties properties;

    @Mock
    private OpenSearchProperties.RolloverProperties rolloverProperties;

    @BeforeEach
    void setUp() {
        initializer.properties = properties;
    }

    @Test
    void shouldBuildRolloverPolicyWithConfiguredThresholds() throws Exception {
        TimeValue minIndexAge = TimeValue.timeValueDays(1);
        TimeValue minDeleteAge = TimeValue.timeValueDays(14);

        when(properties.rollover()).thenReturn(rolloverProperties);
        when(rolloverProperties.minIndexAge()).thenReturn(minIndexAge);
        when(rolloverProperties.minIndexSize()).thenReturn(Optional.of("10gb"));
        when(rolloverProperties.minRolloverAgeToDelete()).thenReturn(minDeleteAge);

        Policy policy = OpenSearchTestUtils.invoke(
                initializer,
                "buildRolloverPolicy",
                new Class<?>[]{String.class},
                "sessions"
        );

        assertEquals("sessions-rollover-policy", policy.getPolicyId());
        assertEquals("QIP sessions-* rollover policy.", policy.getDescription());
        assertEquals("rollover", policy.getDefaultState());
        assertEquals(2, policy.getStates().size());

        State rolloverState = policy.getStates().getFirst();
        assertEquals("rollover", rolloverState.getName());
        assertEquals(1, rolloverState.getActions().size());

        RolloverAction rolloverAction = assertInstanceOf(
                RolloverAction.class,
                rolloverState.getActions().getFirst()
        );
        assertEquals(minIndexAge, rolloverAction.getMinIndexAge());
        assertEquals("10gb", rolloverAction.getMinSize());

        assertEquals(1, rolloverState.getTransitions().size());
        Transition transition = rolloverState.getTransitions().getFirst();
        assertEquals("delete", transition.getStateName());

        Conditions conditions = transition.getConditions();
        assertEquals(minDeleteAge, conditions.getMinRolloverAge());

        State deleteState = policy.getStates().get(1);
        assertEquals("delete", deleteState.getName());
        assertEquals(1, deleteState.getActions().size());
        assertInstanceOf(DeleteAction.class, deleteState.getActions().getFirst());

        assertEquals(1, policy.getIsmTemplate().size());
        ISMTemplate ismTemplate = policy.getIsmTemplate().getFirst();
        assertEquals(List.of("sessions-*"), ismTemplate.getIndexPatterns());
    }

    @Test
    void shouldBuildRolloverPolicyWithoutDeleteConditionsWhenDeleteAgeIsNull() throws Exception {
        TimeValue minIndexAge = TimeValue.timeValueDays(1);

        when(properties.rollover()).thenReturn(rolloverProperties);
        when(rolloverProperties.minIndexAge()).thenReturn(minIndexAge);
        when(rolloverProperties.minIndexSize()).thenReturn(Optional.empty());
        when(rolloverProperties.minRolloverAgeToDelete()).thenReturn(null);

        Policy policy = OpenSearchTestUtils.invoke(
                initializer,
                "buildRolloverPolicy",
                new Class<?>[]{String.class},
                "sessions"
        );

        State rolloverState = policy.getStates().getFirst();
        RolloverAction rolloverAction = assertInstanceOf(
                RolloverAction.class,
                rolloverState.getActions().getFirst()
        );

        assertEquals(minIndexAge, rolloverAction.getMinIndexAge());
        assertNull(rolloverAction.getMinSize());
        assertNull(rolloverState.getTransitions().getFirst().getConditions());
    }

    @Test
    void shouldBuildOldIndexRolloverPolicyWithAgeAndSizeTransitions() throws Exception {
        TimeValue minAge = TimeValue.timeValueDays(15);

        when(properties.rollover()).thenReturn(rolloverProperties);
        when(rolloverProperties.minIndexSize()).thenReturn(Optional.of("10gb"));

        Policy policy = OpenSearchTestUtils.invoke(
                initializer,
                "buildOldIndexRolloverPolicy",
                new Class<?>[]{String.class, TimeValue.class},
                "sessions",
                minAge
        );

        assertEquals("sessions-old-index-rollover-policy", policy.getPolicyId());
        assertEquals("QIP old index rollover policy.", policy.getDescription());
        assertEquals("schedule_to_delete", policy.getDefaultState());
        assertEquals(2, policy.getStates().size());

        State scheduleToDelete = policy.getStates().getFirst();
        assertEquals("schedule_to_delete", scheduleToDelete.getName());
        assertEquals(2, scheduleToDelete.getTransitions().size());

        Transition ageTransition = scheduleToDelete.getTransitions().getFirst();
        assertEquals("delete", ageTransition.getStateName());
        assertEquals(minAge, ageTransition.getConditions().getMinIndexAge());

        Transition sizeTransition = scheduleToDelete.getTransitions().get(1);
        assertEquals("delete", sizeTransition.getStateName());
        assertEquals("10gb", sizeTransition.getConditions().getMinSize());

        State deleteState = policy.getStates().get(1);
        assertEquals("delete", deleteState.getName());
        assertEquals(1, deleteState.getActions().size());
        assertInstanceOf(DeleteAction.class, deleteState.getActions().getFirst());
    }
}
