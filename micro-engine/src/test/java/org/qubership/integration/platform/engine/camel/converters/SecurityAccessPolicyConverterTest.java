package org.qubership.integration.platform.engine.camel.converters;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.TypeConversionException;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.security.QipSecurityAccessPolicy;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class SecurityAccessPolicyConverterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldConvertJsonStringToSecurityAccessPolicy() throws Exception {
        SecurityAccessPolicyConverter converter = new SecurityAccessPolicyConverter(objectMapper);
        QipSecurityAccessPolicy expectedPolicy = mock(QipSecurityAccessPolicy.class);

        try (MockedStatic<QipSecurityAccessPolicy> policyMock = mockStatic(QipSecurityAccessPolicy.class)) {
            policyMock.when(() -> QipSecurityAccessPolicy.fromStrings(List.of("role_admin", "scope_read")))
                    .thenReturn(expectedPolicy);

            QipSecurityAccessPolicy result = converter.convert("[\"role_admin\",\"scope_read\"]");

            assertSame(expectedPolicy, result);
        }
    }

    @Test
    void shouldConvertNonStringValueUsingStringValueOf() throws Exception {
        SecurityAccessPolicyConverter converter = new SecurityAccessPolicyConverter(objectMapper);
        QipSecurityAccessPolicy expectedPolicy = mock(QipSecurityAccessPolicy.class);

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
        SecurityAccessPolicyConverter converter = new SecurityAccessPolicyConverter(objectMapper);

        TypeConversionException exception = assertThrows(
                TypeConversionException.class,
                () -> converter.convert("not-a-json")
        );

        assertEquals(QipSecurityAccessPolicy.class, exception.getToType());
        assertInstanceOf(JsonProcessingException.class, exception.getCause());
    }
}
