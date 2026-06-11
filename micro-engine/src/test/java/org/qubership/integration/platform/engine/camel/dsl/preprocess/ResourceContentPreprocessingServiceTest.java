package org.qubership.integration.platform.engine.camel.dsl.preprocess;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ResourceContentPreprocessingServiceTest {

    @Mock
    private ResourceContentPreprocessor firstPreprocessor;

    @Mock
    private ResourceContentPreprocessor secondPreprocessor;

    @Test
    void shouldApplyPreprocessorsSequentially() throws Exception {
        ResourceContentPreprocessingService service = new ResourceContentPreprocessingService(List.of(
            firstPreprocessor,
            secondPreprocessor
        ));

        when(firstPreprocessor.apply("original content")).thenReturn("after first preprocessor");
        when(secondPreprocessor.apply("after first preprocessor")).thenReturn("after second preprocessor");

        String result = service.preprocess("original content");

        assertEquals("after second preprocessor", result);

        InOrder inOrder = inOrder(firstPreprocessor, secondPreprocessor);
        inOrder.verify(firstPreprocessor).apply("original content");
        inOrder.verify(secondPreprocessor).apply("after first preprocessor");
    }

    @Test
    void shouldReturnOriginalContentWhenPreprocessorsAreEmpty() throws Exception {
        ResourceContentPreprocessingService service = new ResourceContentPreprocessingService(Collections.emptyList());

        String result = service.preprocess("original content");

        assertEquals("original content", result);
    }

    @Test
    void shouldReturnOriginalContentWhenPreprocessorDoesNotChangeContent() throws Exception {
        ResourceContentPreprocessingService service = new ResourceContentPreprocessingService(List.of(firstPreprocessor));

        when(firstPreprocessor.apply("original content")).thenReturn("original content");

        String result = service.preprocess("original content");

        assertEquals("original content", result);
        verify(firstPreprocessor).apply("original content");
    }

    @Test
    void shouldPropagateExceptionWhenPreprocessorFails() throws Exception {
        ResourceContentPreprocessingService service = new ResourceContentPreprocessingService(List.of(
            firstPreprocessor,
            secondPreprocessor
        ));
        Exception exception = new Exception("Preprocessing failed");

        when(firstPreprocessor.apply("original content")).thenThrow(exception);

        Exception result = assertThrows(Exception.class, () -> service.preprocess("original content"));

        assertSame(exception, result);
        verify(firstPreprocessor).apply("original content");
        verify(secondPreprocessor, never()).apply("original content");
    }
}
