package org.qubership.integration.platform.engine.service.deployment.processing.actions.context.create;

import org.apache.camel.CamelContext;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The deployment each MCP tool name calls now, so that a call the server handed to a tool a
 * redeploy replaced can reach the deployment that replaced it.
 */
@Component
public class McpToolTargets {
    /** A deployment's Camel context and the endpoint its MCP trigger consumes. */
    public record Target(CamelContext context, String endpointUri) {
    }

    private final Map<String, Target> current = new ConcurrentHashMap<>();

    public void publish(String toolName, Target target) {
        current.put(toolName, target);
    }

    public Optional<Target> current(String toolName) {
        return Optional.ofNullable(current.get(toolName));
    }

    /** Forgets the tools of {@code context}, and leaves a tool a later deployment took over. */
    public void withdraw(CamelContext context) {
        current.values().removeIf(target -> target.context() == context);
    }
}
