package org.qubership.integration.platform.engine.component.profile;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.opensearch.testcontainers.OpensearchContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.HashMap;
import java.util.Map;

public class OpenSearchTestResource implements QuarkusTestResourceLifecycleManager {
    private OpensearchContainer os;

    @Override
    public Map<String, String> start() {
        DockerImageName img = DockerImageName
                .parse("opensearchproject/opensearch:3.4.0")
                .asCompatibleSubstituteFor("opensearchproject/opensearch");

        os = (OpensearchContainer) new OpensearchContainer(img)
                .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
                .withEnv("DISABLE_SECURITY_PLUGIN", "true")
                .withEnv("discovery.type", "single-node")
                .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m");

        os.start();

        String host = os.getHost();
        Integer port = os.getMappedPort(9200);
        String protocol = "http";
        String urls = protocol + "://" + host + ":" + port;

        Map<String, String> m = new HashMap<>();
        m.put("opensearch.client.urls", urls);
        m.put("opensearch.client.host", host);
        m.put("opensearch.client.port", String.valueOf(port));
        m.put("opensearch.client.protocol", protocol);
        m.put("opensearch.client.user-name", "");
        m.put("opensearch.client.password", "");
        m.put("opensearch.client.prefix", "");
        return m;
    }

    @Override
    public void stop() {
        if (os != null) {
            os.stop();
        }
    }
}
