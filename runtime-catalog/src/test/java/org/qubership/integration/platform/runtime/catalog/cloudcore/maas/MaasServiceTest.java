package org.qubership.integration.platform.runtime.catalog.cloudcore.maas;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.configuration.MapperAutoConfiguration;
import org.qubership.integration.platform.runtime.catalog.service.variables.secrets.KubeSecretSerializer;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class MaasServiceTest {
    private final YAMLMapper mapper = new MapperAutoConfiguration().yamlMapper(mock(KubeSecretSerializer.class));
    private final MaasService service = new MaasService(null, null, null, mapper, null, null);

    @Test
    void kafkaManifestContainsOnlyTheClassifierEntries() {
        JsonNode classifier = mapper.readTree(service.getMaasDeclarativeFileKafka("topic"))
                .path("spec").path("classifier");

        assertEquals(mapper.readTree("{\"name\":\"topic\",\"namespace\":\"${ENV_NAMESPACE}\"}"), classifier);
    }

    @Test
    void rabbitManifestContainsOnlyTheClassifierEntries() {
        JsonNode classifier = mapper.readTree(service.getMaasDeclarativeFileRabbitMq("vhost", "exchange", "queue", "key"))
                .path("spec").path("classifier");

        assertEquals(mapper.readTree("{\"name\":\"vhost\",\"namespace\":\"${ENV_NAMESPACE}\"}"), classifier);
    }
}
