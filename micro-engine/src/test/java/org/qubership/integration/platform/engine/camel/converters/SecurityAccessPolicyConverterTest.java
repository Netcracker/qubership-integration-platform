package org.qubership.integration.platform.engine.camel.converters;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.Mock;
import org.apache.camel.TypeConversionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.security.QipSecurityAccessPolicy;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mockStatic;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class SecurityAccessPolicyConverterTest {

    private SecurityAccessPolicyConverter converter;

    @Mock
    QipSecurityAccessPolicy expectedPolicy;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = ObjectMappers.getObjectMapper();
        converter = new SecurityAccessPolicyConverter(objectMapper);
    }

    @Test
    void shouldConvertJsonStringToSecurityAccessPolicy() {
        try (MockedStatic<QipSecurityAccessPolicy> policyMock = mockStatic(QipSecurityAccessPolicy.class)) {
            policyMock.when(() -> QipSecurityAccessPolicy.fromStrings(List.of("role_admin", "scope_read")))
                    .thenReturn(expectedPolicy);

            QipSecurityAccessPolicy result = converter.convert("[\"role_admin\",\"scope_read\"]");

            assertSame(expectedPolicy, result);
        }
    }

    @Test
    void shouldConvertNonStringValueUsingStringValueOf() {
        Object value = new Object() {
            @Override
            public String toString() {
                return "[\"role_user\"]";
            }
        };

        try (MockedStatic<QipSecurityAccessPolicy> policyMock = mockStatic(QipSecurityAccessPolicy.class)) {
            policyMock.when(() -> QipSecurityAccessPolicy.fromStrings(List.of("role_user")))
                    .thenReturn(expectedPolicy);

            QipSecurityAccessPolicy result = converter.convert(value);

            assertSame(expectedPolicy, result);
        }
    }

    @Test
    void shouldThrowTypeConversionExceptionWhenJsonIsInvalid() {
        TypeConversionException exception = assertThrows(
                TypeConversionException.class,
                () -> converter.convert("not-a-json")
        );

        assertEquals(QipSecurityAccessPolicy.class, exception.getToType());
        assertInstanceOf(JsonProcessingException.class, exception.getCause());
    }
}
