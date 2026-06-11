package org.qubership.integration.platform.engine.camel.listeners.helpers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.metadata.ChainInfo;
import org.qubership.integration.platform.engine.metadata.DeploymentInfo;
import org.qubership.integration.platform.engine.metadata.ElementInfo;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class SdsSchedulerJobsRegistrationHelperTest {

    private static final String CHAIN_ID = "4de96e43-d9d0-4bab-ad6f-d8b19b1d7115";
    private static final String FIRST_ELEMENT_ID = "4842a55b-778c-4093-a011-9682f5295cd2";
    private static final String SECOND_ELEMENT_ID = "8c8755e9-5613-4520-b1d0-9f4bf6b1d3fe";
    private static final String UNKNOWN_ELEMENT_ID = "bcffae48-7184-4117-9a74-86e60a63d22f";

    @Mock
    private DeploymentInfo deploymentInfo;

    @Mock
    private ChainInfo chainInfo;

    private SdsSchedulerJobsRegistrationHelper helper;

    @BeforeEach
    void setUp() {
        helper = new SdsSchedulerJobsRegistrationHelper();
    }

    @Test
    void shouldReturnFalseWhenDeploymentWasNotRegistered() {
        stubDeploymentChainId();

        boolean result = helper.registered(deploymentInfo);

        assertFalse(result);
    }

    @Test
    void shouldReturnFalseWhenElementWasNotRegistered() {
        stubDeploymentChainId();
        ElementInfo elementInfo = elementInfo(UNKNOWN_ELEMENT_ID);

        boolean result = helper.registered(deploymentInfo, elementInfo);

        assertFalse(result);
    }

    @Test
    void shouldMarkElementAsRegistered() {
        stubDeploymentChainId();
        ElementInfo elementInfo = elementInfo(FIRST_ELEMENT_ID);

        helper.markRegistered(deploymentInfo, elementInfo);

        assertTrue(helper.registered(deploymentInfo));
        assertTrue(helper.registered(deploymentInfo, elementInfo));
    }

    @Test
    void shouldKeepPreviouslyRegisteredElementsWhenAnotherElementIsRegistered() {
        stubDeploymentChainId();
        ElementInfo firstElement = elementInfo(FIRST_ELEMENT_ID);
        ElementInfo secondElement = elementInfo(SECOND_ELEMENT_ID);

        helper.markRegistered(deploymentInfo, firstElement);
        helper.markRegistered(deploymentInfo, secondElement);

        assertTrue(helper.registered(deploymentInfo));
        assertTrue(helper.registered(deploymentInfo, firstElement));
        assertTrue(helper.registered(deploymentInfo, secondElement));
    }

    @Test
    void shouldReturnFalseForElementFromSameDeploymentWhenDifferentElementWasRegistered() {
        stubDeploymentChainId();
        ElementInfo registeredElement = elementInfo(FIRST_ELEMENT_ID);
        ElementInfo unknownElement = elementInfo(UNKNOWN_ELEMENT_ID);

        helper.markRegistered(deploymentInfo, registeredElement);

        assertFalse(helper.registered(deploymentInfo, unknownElement));
    }

    @Test
    void shouldMarkDeploymentAsUnregistered() {
        stubDeploymentChainId();
        ElementInfo elementInfo = elementInfo(FIRST_ELEMENT_ID);

        helper.markRegistered(deploymentInfo, elementInfo);
        helper.markUnregistered(deploymentInfo);

        assertFalse(helper.registered(deploymentInfo));
        assertFalse(helper.registered(deploymentInfo, elementInfo));
    }

    private void stubDeploymentChainId() {
        when(deploymentInfo.getChain()).thenReturn(chainInfo);
        when(chainInfo.getId()).thenReturn(SdsSchedulerJobsRegistrationHelperTest.CHAIN_ID);
    }

    private static ElementInfo elementInfo(String id) {
        ElementInfo elementInfo = mock(ElementInfo.class);
        when(elementInfo.getId()).thenReturn(id);
        return elementInfo;
    }
}
