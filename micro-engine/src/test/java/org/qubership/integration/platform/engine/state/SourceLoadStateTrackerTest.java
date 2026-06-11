package org.qubership.integration.platform.engine.state;

import org.apache.camel.CamelContext;
import org.apache.camel.k.SourceDefinition;
import org.apache.camel.k.listener.SourcesConfigurer;
import org.apache.camel.k.support.PropertiesSupport;
import org.apache.camel.spi.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.List;

import static org.apache.camel.k.listener.SourcesConfigurer.CAMEL_K_PREFIX;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class SourceLoadStateTrackerTest {

    @Mock
    private CamelContext camelContext;

    private SourceLoadStateTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new SourceLoadStateTracker();
        tracker.camelContext = camelContext;
    }

    @Test
    void shouldReturnUnknownStateWhenSourceWasNotTracked() {
        SourceLoadStateTracker.SourceLoadState state = tracker.getLoadState("unknown-source");

        assertEquals(SourceLoadStateTracker.SourceLoadStage.UNKNOWN, state.stage());
        assertNull(state.exception());
    }

    @Test
    void shouldTrackProcessingStateBySourceDefinitionIdWhenResourceLocationMatchesDefinition() {
        tracker.addSourceDefinitions(List.of(sourceDefinition("source-id", "classpath:routes/test.yaml")));

        tracker.onSourceProcessingStart(resource());

        SourceLoadStateTracker.SourceLoadState state = tracker.getLoadState("source-id");
        assertEquals(SourceLoadStateTracker.SourceLoadStage.PROCESSING, state.stage());
        assertNull(state.exception());
    }

    @Test
    void shouldTrackSuccessStateBySourceDefinitionIdWhenResourceLocationMatchesDefinition() {
        tracker.addSourceDefinitions(List.of(sourceDefinition("source-id", "classpath:routes/test.yaml")));

        tracker.onSourceLoaded(resource());

        SourceLoadStateTracker.SourceLoadState state = tracker.getLoadState("source-id");
        assertEquals(SourceLoadStateTracker.SourceLoadStage.SUCCESS, state.stage());
        assertNull(state.exception());
    }

    @Test
    void shouldTrackFailedStateBySourceDefinitionIdAndStoreExceptionWhenResourceLocationMatchesDefinition() {
        Exception exception = new RuntimeException("Route loading failed");
        tracker.addSourceDefinitions(List.of(sourceDefinition("source-id", "classpath:routes/test.yaml")));

        tracker.onSourceLoadFailed(resource(), exception);

        SourceLoadStateTracker.SourceLoadState state = tracker.getLoadState("source-id");
        assertEquals(SourceLoadStateTracker.SourceLoadStage.FAILED, state.stage());
        assertSame(exception, state.exception());
    }

    @Test
    void shouldTrackStateByResourceLocationWhenSourceDefinitionDoesNotMatch() {
        tracker.addSourceDefinitions(List.of(sourceDefinitionWithLocationOnly("classpath:routes/another.yaml")));

        tracker.onSourceLoaded(resource());

        SourceLoadStateTracker.SourceLoadState state = tracker.getLoadState("classpath:routes/test.yaml");
        assertEquals(SourceLoadStateTracker.SourceLoadStage.SUCCESS, state.stage());
        assertNull(state.exception());

        SourceLoadStateTracker.SourceLoadState unmatchedState = tracker.getLoadState("source-id");
        assertEquals(SourceLoadStateTracker.SourceLoadStage.UNKNOWN, unmatchedState.stage());
    }

    @Test
    void shouldOverwriteStateWhenSameSourceIsProcessedMoreThanOnce() {
        Resource resource = resource();
        tracker.addSourceDefinitions(List.of(sourceDefinition("source-id", "classpath:routes/test.yaml")));

        tracker.onSourceProcessingStart(resource);
        tracker.onSourceLoaded(resource);

        SourceLoadStateTracker.SourceLoadState state = tracker.getLoadState("source-id");
        assertEquals(SourceLoadStateTracker.SourceLoadStage.SUCCESS, state.stage());
        assertNull(state.exception());
    }

    @Test
    void shouldAddSourceDefinitionsToExistingCollection() {
        SourceDefinition firstSource = plainSourceDefinition();
        SourceDefinition secondSource = plainSourceDefinition();

        tracker.addSourceDefinitions(List.of(firstSource));
        tracker.addSourceDefinitions(List.of(secondSource));

        assertEquals(2, tracker.getSourceDefinitions().size());
        assertTrue(tracker.getSourceDefinitions().contains(firstSource));
        assertTrue(tracker.getSourceDefinitions().contains(secondSource));
    }

    @Test
    void shouldInitializeWithEmptySourceDefinitionsWhenCamelKSourcesAreNotConfigured() {
        try (MockedStatic<PropertiesSupport> propertiesSupport = mockStatic(PropertiesSupport.class)) {
            tracker.init();

            assertTrue(tracker.getSourceDefinitions().isEmpty());
            propertiesSupport.verify(() -> PropertiesSupport.bindProperties(
                eq(camelContext),
                any(SourcesConfigurer.class),
                any(),
                eq(CAMEL_K_PREFIX)
            ));
        }
    }

    private static SourceDefinition sourceDefinition(String id, String location) {
        SourceDefinition sourceDefinition = mock(SourceDefinition.class);
        when(sourceDefinition.getLocation()).thenReturn(location);
        when(sourceDefinition.getId()).thenReturn(id);
        return sourceDefinition;
    }

    private static SourceDefinition sourceDefinitionWithLocationOnly(String location) {
        SourceDefinition sourceDefinition = mock(SourceDefinition.class);
        when(sourceDefinition.getLocation()).thenReturn(location);
        return sourceDefinition;
    }

    private static SourceDefinition plainSourceDefinition() {
        return mock(SourceDefinition.class);
    }

    private static Resource resource() {
        Resource resource = mock(Resource.class);
        when(resource.getLocation()).thenReturn("classpath:routes/test.yaml");
        return resource;
    }
}
