/*
 * Copyright 2024-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.integration.platform.engine.service.debugger.masking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.NotSupportedException;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.qubership.integration.platform.engine.errorhandling.LoggingMaskingException;
import org.qubership.integration.platform.engine.model.SessionElementProperty;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class MaskingServiceTest {

    private MaskingService service(ObjectMapper mapper) {
        return new MaskingService(mapper);
    }

    @Test
    void shouldMaskJsonValuesAndArraysWhenJson() throws Exception {
        ObjectMapper mapper = ObjectMappers.getObjectMapper();
        MaskingService svc = service(mapper);

        String json = """
                {
                  "password": "123",
                  "user": "bob",
                  "attrs": {
                    "token": "abc",
                    "keep": "ok"
                  },
                  "scopes": ["read","write"],
                  "numbers": [1,2,3],
                  "nestedArray": {
                    "codes": ["x","y"],
                    "objs": [
                        {
                          "password": "p1"
                        },
                        {
                          "keep": "k"
                        }
                    ]
                  }
                }
                """;

        Set<String> fields = Set.of("password", "token", "codes");
        String masked = svc.maskFields(json, fields, MediaType.APPLICATION_JSON_TYPE);

        JsonNode root = mapper.readTree(masked);
        assertEquals(CamelConstants.MASKING_TEMPLATE, root.get("password").asText());
        assertEquals("bob", root.get("user").asText());
        assertEquals(CamelConstants.MASKING_TEMPLATE, root.get("attrs").get("token").asText());
        assertEquals("ok", root.get("attrs").get("keep").asText());

        assertEquals("read", root.get("scopes").get(0).asText());
        assertEquals(1, root.get("numbers").get(0).asInt());

        assertEquals(CamelConstants.MASKING_TEMPLATE, root.get("nestedArray").get("codes").get(0).asText());
        assertEquals(CamelConstants.MASKING_TEMPLATE, root.get("nestedArray").get("codes").get(1).asText());
        assertEquals(CamelConstants.MASKING_TEMPLATE, root.get("nestedArray").get("objs").get(0).get("password").asText());
        assertEquals("k", root.get("nestedArray").get("objs").get(1).get("keep").asText());
    }

    @Test
    void shouldTreatJsonPatchAsJson() throws Exception {
        ObjectMapper mapper = ObjectMappers.getObjectMapper();
        MaskingService svc = service(mapper);

        String json = "{\"token\":\"abc\",\"other\":\"v\"}";
        MediaType jsonPatch = MediaType.valueOf("application/json-patch+json");
        String masked = svc.maskFields(json, Set.of("token"), jsonPatch);

        JsonNode root = mapper.readTree(masked);
        assertEquals(CamelConstants.MASKING_TEMPLATE, root.get("token").asText());
        assertEquals("v", root.get("other").asText());
    }

    @Test
    void shouldWrapJsonErrorsWhenInvalidJson() {
        ObjectMapper mapper = ObjectMappers.getObjectMapper();
        MaskingService svc = service(mapper);

        assertThrows(LoggingMaskingException.class, () ->
                svc.maskFields("{bad}", Set.of("password"), MediaType.APPLICATION_JSON_TYPE)
        );
    }

    @Test
    void shouldMaskXmlElementsAndAttributesWhenXml() throws Exception {
        ObjectMapper mapper = ObjectMappers.getObjectMapper();
        MaskingService svc = service(mapper);

        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <root token="abc">
                  <user>bob</user>
                  <password>123</password>
                  <nested>
                    <keep attr="ok">val</keep>
                    <secret attr="hide">value</secret>
                  </nested>
                </root>
                """;

        String masked = svc.maskFields(xml, Set.of("password", "token", "secret", "attr"), MediaType.APPLICATION_XML_TYPE);

        assertTrue(masked.contains("<password>" + CamelConstants.MASKING_TEMPLATE + "</password>"));
        assertTrue(masked.contains("token=\"" + CamelConstants.MASKING_TEMPLATE + "\""));
        assertTrue(masked.contains(
                "<secret attr=\""
                        + CamelConstants.MASKING_TEMPLATE
                        + "\">"
                        + CamelConstants.MASKING_TEMPLATE
                        + "</secret>"
        ));
        assertTrue(masked.contains("attr=\"" + CamelConstants.MASKING_TEMPLATE + "\""));
        assertTrue(masked.contains("<user>bob</user>"));
    }

    @Test
    void shouldWrapXmlErrorsWhenInvalidXml() {
        ObjectMapper mapper = ObjectMappers.getObjectMapper();
        MaskingService svc = service(mapper);

        String brokenXml = "<root><password>123</password>";
        assertThrows(LoggingMaskingException.class, () ->
                svc.maskFields(brokenXml, Set.of("password"), MediaType.APPLICATION_XML_TYPE)
        );
    }

    @Test
    void shouldMaskFormUrlencodedWithDecodedKeyMatch() throws Exception {
        ObjectMapper mapper = ObjectMappers.getObjectMapper();
        MaskingService svc = service(mapper);

        String form = "password=abc&user=Bob&%74%6F%6B%65%6E=xyz";
        String masked = svc.maskFields(form, Set.of("password", "token"), MediaType.valueOf("application/x-www-form-urlencoded"));

        assertEquals("password=" + CamelConstants.MASKING_TEMPLATE + "&user=Bob&%74%6F%6B%65%6E=" + CamelConstants.MASKING_TEMPLATE, masked);
    }

    @Test
    void shouldWrapFormUrlencodedErrorsOnBadPercent() {
        ObjectMapper mapper = ObjectMappers.getObjectMapper();
        MaskingService svc = service(mapper);

        String form = "%ZZ=abc&user=Bob";
        assertThrows(LoggingMaskingException.class, () ->
                svc.maskFields(form, Set.of("user"), MediaType.valueOf("application/x-www-form-urlencoded"))
        );
    }

    @Test
    void shouldMaskMapValuesOnlyForExistingKeys() {
        ObjectMapper mapper = ObjectMappers.getObjectMapper();
        MaskingService svc = service(mapper);

        Map<String, String> map = new HashMap<>();
        map.put("password", "123");
        map.put("user", "bob");

        svc.maskFields(map, Set.of("password", "token"));

        assertEquals(CamelConstants.MASKING_TEMPLATE, map.get("password"));
        assertEquals("bob", map.get("user"));
        assertFalse(map.containsKey("token"));
    }

    @Test
    void shouldMaskPropertiesValuesViaSetValueOnlyForExistingKeys() {
        ObjectMapper mapper = ObjectMappers.getObjectMapper();
        MaskingService svc = service(mapper);

        SessionElementProperty p1 = Mockito.mock(SessionElementProperty.class);
        SessionElementProperty p2 = Mockito.mock(SessionElementProperty.class);

        Map<String, SessionElementProperty> props = new HashMap<>();
        props.put("secret", p1);
        props.put("keep", p2);

        svc.maskPropertiesFields(props, Set.of("secret", "missing"));

        verify(p1, times(1)).setValue(CamelConstants.MASKING_TEMPLATE);
        verify(p2, never()).setValue(any());
    }

    @Test
    void shouldThrowNotSupportedWhenUnknownContentType() {
        ObjectMapper mapper = ObjectMappers.getObjectMapper();
        MaskingService svc = service(mapper);

        assertThrows(NotSupportedException.class, () ->
                svc.maskFields("abc", Set.of("x"), MediaType.TEXT_PLAIN_TYPE)
        );
    }

    @Test
    void shouldThrowNotSupportedWhenContentTypeNull() {
        ObjectMapper mapper = ObjectMappers.getObjectMapper();
        MaskingService svc = service(mapper);

        assertThrows(NotSupportedException.class, () ->
                svc.maskFields("{}", Set.of("x"), null)
        );
    }
}
