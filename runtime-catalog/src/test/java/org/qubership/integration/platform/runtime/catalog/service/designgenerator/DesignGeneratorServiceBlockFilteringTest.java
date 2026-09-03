package org.qubership.integration.platform.runtime.catalog.service.designgenerator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.library.components.LibraryElementsService;
import org.qubership.integration.platform.library.model.ElementDescriptor;
import org.qubership.integration.platform.library.model.ElementType;
import org.qubership.integration.platform.library.model.chaindesign.ContainerChildrenParameters;
import org.qubership.integration.platform.library.model.chaindesign.DiagramOperationType;
import org.qubership.integration.platform.library.model.chaindesign.ElementContainerDesignParameters;
import org.qubership.integration.platform.library.model.chaindesign.ElementDesignParameters;
import org.qubership.integration.platform.library.model.chaindesign.ElementDiagramOperation;
import org.qubership.integration.platform.runtime.catalog.model.designgenerator.DiagramLangType;
import org.qubership.integration.platform.runtime.catalog.model.designgenerator.DiagramMode;
import org.qubership.integration.platform.runtime.catalog.model.designgenerator.ElementsSequenceDiagram;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Dependency;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ContainerChainElement;
import org.qubership.integration.platform.runtime.catalog.service.DependencyService;
import org.qubership.integration.platform.runtime.catalog.service.ElementService;
import org.qubership.integration.platform.runtime.catalog.service.designgenerator.processors.LoopContainerDesignProcessor;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ChainFinderService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DesignGeneratorServiceBlockFilteringTest {

    private static final String CHAIN_ID = "chain-1";
    private static final String TRIGGER_TYPE = "http-trigger";
    private static final String CONDITION_TYPE = "condition";
    private static final String IF_TYPE = "if";
    private static final String LOOP_TYPE = "loop-2";
    // Simple mode hides scripts, so a block holding only one ends up empty
    private static final String HIDDEN_TYPE = "script";

    private final Map<String, ElementDescriptor> descriptors = new HashMap<>();

    private ElementService elementService;
    private DependencyService dependencyService;
    private DesignGeneratorService service;

    @BeforeEach
    void setUp() {
        descriptors.clear();
        descriptors.put(TRIGGER_TYPE, triggerDescriptor());
        descriptors.put(CONDITION_TYPE, conditionDescriptor());
        descriptors.put(IF_TYPE, containerDescriptor());
        descriptors.put(LOOP_TYPE, containerDescriptor());
        descriptors.put(HIDDEN_TYPE, hiddenElementDescriptor());

        elementService = mock(ElementService.class);
        dependencyService = mock(DependencyService.class);

        LibraryElementsService libraryService = mock(LibraryElementsService.class);
        when(libraryService.lookupElementDescriptor(anyString()))
                .thenAnswer(invocation ->
                        Optional.ofNullable(descriptors.get(invocation.getArgument(0, String.class))));
        when(libraryService.getElementDescriptorOrDefault(anyString()))
                .thenAnswer(invocation ->
                        descriptors.getOrDefault(invocation.getArgument(0, String.class), new ElementDescriptor()));

        ChainFinderService chainFinderService = mock(ChainFinderService.class);
        when(chainFinderService.findById(anyString()))
                .thenReturn(Chain.builder().id(CHAIN_ID).name("Test chain").build());

        service = new DesignGeneratorService(elementService, dependencyService, libraryService,
                chainFinderService, List.of(new LoopContainerDesignProcessor()));
    }

    @Test
    void shouldKeepConditionBlockInFullModeWhenItHoldsAnInteraction() {
        givenChainWithContainer(condition());

        String source = generate(DiagramMode.FULL);

        assertTrue(source.contains("alt "), source);
        assertTrue(source.contains("Hidden in simple mode"), source);
    }

    @Test
    void shouldDropConditionBlockInSimpleModeWhenItsOnlyChildIsHidden() {
        givenChainWithContainer(condition());

        String source = generate(DiagramMode.SIMPLE);

        assertFalse(source.contains("alt "), source);
        // the only block left is the group around the trigger
        assertEquals(1, countOccurrences(source, "end;"), source);
    }

    @Test
    void shouldKeepLoopBlockInFullModeWhenItHoldsAnInteraction() {
        givenChainWithContainer(loop());

        String source = generate(DiagramMode.FULL);

        assertTrue(source.contains("loop"), source);
        assertTrue(source.contains("Hidden in simple mode"), source);
    }

    @Test
    void shouldDropLoopBlockInSimpleModeWhenItsOnlyChildIsHidden() {
        givenChainWithContainer(loop());

        String source = generate(DiagramMode.SIMPLE);

        assertFalse(source.contains("loop"), source);
    }

    private static int countOccurrences(String source, String token) {
        return source.split(java.util.regex.Pattern.quote(token), -1).length - 1;
    }

    private String generate(DiagramMode mode) {
        Map<DiagramMode, ElementsSequenceDiagram> diagrams =
                service.generateChainSequenceDiagram(CHAIN_ID, List.of(mode));
        return diagrams.get(mode).getDiagramSources().get(DiagramLangType.MERMAID);
    }

    private void givenChainWithContainer(ContainerChainElement container) {
        ChainElement trigger = element("trigger-1", "Trigger", TRIGGER_TYPE);

        Dependency dependency = Dependency.of(trigger, container);
        trigger.getOutputDependencies().add(dependency);
        container.getInputDependencies().add(dependency);

        when(elementService.findAllByChainId(CHAIN_ID)).thenReturn(List.of(trigger, container));
        when(dependencyService.findAllByElementsIDs(anyList())).thenReturn(List.of(dependency));
    }

    /**
     * A condition whose only branch holds a single element hidden in simple mode.
     */
    private ContainerChainElement condition() {
        ContainerChainElement ifBranch = container("if-1", "If", IF_TYPE);
        ifBranch.addChildElement(element("hidden-1", "Hidden in simple mode", HIDDEN_TYPE));

        ContainerChainElement condition = container("condition-1", "Condition", CONDITION_TYPE);
        condition.addChildElement(ifBranch);
        return condition;
    }

    private ContainerChainElement loop() {
        ContainerChainElement loop = container("loop-1", "Loop", LOOP_TYPE);
        loop.addChildElement(element("hidden-1", "Hidden in simple mode", HIDDEN_TYPE));
        return loop;
    }

    private static ChainElement element(String id, String name, String type) {
        return ChainElement.builder().id(id).name(name).type(type).build();
    }

    private static ContainerChainElement container(String id, String name, String type) {
        return ContainerChainElement.builder().id(id).name(name).type(type).build();
    }

    private static ElementDescriptor triggerDescriptor() {
        ElementDesignParameters designParameters = new ElementDesignParameters();
        designParameters.setExternalParticipantId("External caller");
        designParameters.setExternalParticipantName("External caller");
        designParameters.setRequestLineTitle("Request");
        designParameters.setDirectionToChain(true);
        designParameters.setResponseAfterRequest(true);

        ElementDescriptor descriptor = new ElementDescriptor();
        descriptor.setType(ElementType.TRIGGER);
        descriptor.setDesignParameters(designParameters);
        return descriptor;
    }

    /**
     * Mirrors the condition element of the library: one branch group wrapped in an alt block.
     */
    private static ElementDescriptor conditionDescriptor() {
        ElementDiagramOperation start = new ElementDiagramOperation();
        start.setType(DiagramOperationType.START_ALT);
        start.setArgs(new String[]{"##{ELEMENT_NAME_REF}"});

        ContainerChildrenParameters children = new ContainerChildrenParameters();
        children.setName(IF_TYPE);
        children.setPrimaryOperation(start);

        ElementDiagramOperation end = new ElementDiagramOperation();
        end.setType(DiagramOperationType.END);

        ElementContainerDesignParameters designParameters = new ElementContainerDesignParameters();
        designParameters.setChildren(List.of(children));
        designParameters.setEndOperations(List.of(end));

        ElementDescriptor descriptor = containerDescriptor();
        descriptor.setDesignContainerParameters(designParameters);
        return descriptor;
    }

    private static ElementDescriptor containerDescriptor() {
        ElementDescriptor descriptor = new ElementDescriptor();
        descriptor.setType(ElementType.MODULE);
        descriptor.setContainer(true);
        return descriptor;
    }

    private static ElementDescriptor hiddenElementDescriptor() {
        ElementDesignParameters designParameters = new ElementDesignParameters();
        designParameters.setExternalParticipantId("##{ELEMENT_CHAIN_SELF_REF}");
        designParameters.setRequestLineTitle("##{ELEMENT_NAME_REF}");
        designParameters.setDirectionToChain(true);
        designParameters.setHasResponse(false);

        ElementDescriptor descriptor = new ElementDescriptor();
        descriptor.setType(ElementType.MODULE);
        descriptor.setDesignParameters(designParameters);
        return descriptor;
    }
}
