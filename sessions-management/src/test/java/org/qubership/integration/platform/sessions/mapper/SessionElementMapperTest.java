package org.qubership.integration.platform.sessions.mapper;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.sessions.dto.Session;
import org.qubership.integration.platform.sessions.dto.SessionElement;
import org.qubership.integration.platform.sessions.dto.opensearch.SessionElementElastic;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class SessionElementMapperTest {

    @Test
    void importedElementsKeepExternalSessionIdOfTheirSession() {
        SessionElementMapper mapper = new SessionElementMapperImpl();
        mapper.setObjectMapper(new JsonMapper());
        SessionElement child = SessionElement.builder().elementId("child").build();
        SessionElement parent = SessionElement.builder().elementId("parent").children(List.of(child)).build();
        Session session = Session.builder()
                .id("session-id")
                .externalSessionCipId("external-session-id")
                .sessionElements(List.of(parent))
                .build();

        List<SessionElementElastic> elements = mapper.toElements(List.of(session));

        assertThat(elements)
                .extracting(SessionElementElastic::getId, SessionElementElastic::getExternalSessionId)
                .containsExactly(
                        tuple("parent", "external-session-id"),
                        tuple("child", "external-session-id"));
    }
}
