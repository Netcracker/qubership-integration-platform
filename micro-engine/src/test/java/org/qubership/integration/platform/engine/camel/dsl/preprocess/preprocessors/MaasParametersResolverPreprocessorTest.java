package org.qubership.integration.platform.engine.camel.dsl.preprocess.preprocessors;

import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.util.InjectUtil;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class MaasParametersResolverPreprocessorTest {

    @Mock
    private Instance<MaasParametersResolver> maasParametersResolverInstance;

    @Mock
    private MaasParametersResolver maasParametersResolver;

    @Test
    void shouldResolveMaasParametersWhenResolverIsAvailable() throws Exception {
        try (MockedStatic<InjectUtil> injectUtil = mockStatic(InjectUtil.class)) {
            injectUtil.when(() -> InjectUtil.injectOptional(maasParametersResolverInstance))
                .thenReturn(Optional.of(maasParametersResolver));
            when(maasParametersResolver.resolveMaasParameters("content with placeholders"))
                .thenReturn("resolved content");

            MaasParametersResolverPreprocessor preprocessor =
                new MaasParametersResolverPreprocessor(maasParametersResolverInstance);

            String result = preprocessor.apply("content with placeholders");

            assertEquals("resolved content", result);
            verify(maasParametersResolver).resolveMaasParameters("content with placeholders");
        }
    }

    @Test
    void shouldReturnOriginalContentWhenResolverIsMissing() throws Exception {
        try (MockedStatic<InjectUtil> injectUtil = mockStatic(InjectUtil.class)) {
            injectUtil.when(() -> InjectUtil.injectOptional(maasParametersResolverInstance))
                .thenReturn(Optional.empty());

            MaasParametersResolverPreprocessor preprocessor =
                new MaasParametersResolverPreprocessor(maasParametersResolverInstance);

            String result = preprocessor.apply("content without resolver");

            assertEquals("content without resolver", result);
            verifyNoInteractions(maasParametersResolver);
        }
    }

    @Test
    void shouldPropagateExceptionWhenResolverFails() throws Exception {
        RuntimeException exception = new RuntimeException("MaaS resolving failed");

        try (MockedStatic<InjectUtil> injectUtil = mockStatic(InjectUtil.class)) {
            injectUtil.when(() -> InjectUtil.injectOptional(maasParametersResolverInstance))
                .thenReturn(Optional.of(maasParametersResolver));
            when(maasParametersResolver.resolveMaasParameters("content with placeholders"))
                .thenThrow(exception);

            MaasParametersResolverPreprocessor preprocessor =
                new MaasParametersResolverPreprocessor(maasParametersResolverInstance);

            RuntimeException result = assertThrows(
                RuntimeException.class,
                () -> preprocessor.apply("content with placeholders")
            );

            assertSame(exception, result);
        }
    }
}
