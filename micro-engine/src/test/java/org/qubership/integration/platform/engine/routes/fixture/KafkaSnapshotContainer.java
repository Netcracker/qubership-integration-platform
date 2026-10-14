package org.qubership.integration.platform.engine.routes.fixture;

import org.testcontainers.containers.ExecConfig;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.kafka.KafkaContainer;

import java.io.IOException;

class KafkaSnapshotContainer extends KafkaContainer {
    protected static final String STARTER_SCRIPT = "/tmp/testcontainers_start.sh";

    KafkaSnapshotContainer(String image) {
        super(image);
    }

    @Override
    public void copyFileToContainer(Transferable transferable, String containerPath) {
        if (!STARTER_SCRIPT.equals(containerPath)) {
            super.copyFileToContainer(transferable, containerPath);
            return;
        }

        String pendingScript = STARTER_SCRIPT + ".pending";
        try {
            // The container polls for this path, so publish it only after Docker closes the copied file.
            super.copyFileToContainer(transferable, pendingScript);
            // Docker copies as root; the image's appuser cannot rename a root-owned file in sticky /tmp.
            ExecResult result = execInContainer(ExecConfig.builder()
                    .user("0")
                    .command(new String[] {"mv", "--", pendingScript, STARTER_SCRIPT})
                    .build());
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("Cannot publish Kafka startup script: " + result.getStderr());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing Kafka startup script.", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot publish Kafka startup script.", exception);
        }
    }
}
