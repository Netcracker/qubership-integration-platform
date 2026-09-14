package org.qubership.integration.platform.engine.routes.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.inject.Instance;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.NamedNode;
import org.apache.camel.Processor;
import org.apache.camel.Route;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.spi.Policy;
import org.apache.camel.support.TypeConverterSupport;
import org.qubership.integration.platform.engine.camel.components.context.propagation.ContextOperationsWrapper;
import org.qubership.integration.platform.engine.camel.converters.SecurityAccessPolicyConverter;
import org.qubership.integration.platform.engine.camel.processors.checkpoint.ContextLoaderProcessor;
import org.qubership.integration.platform.engine.camel.processors.checkpoint.ContextSaverProcessor;
import org.qubership.integration.platform.engine.configuration.MapperConfiguration;
import org.qubership.integration.platform.engine.metadata.DeploymentInfo;
import org.qubership.integration.platform.engine.persistence.shared.entity.Checkpoint;
import org.qubership.integration.platform.engine.persistence.shared.entity.SessionInfo;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureInteraction;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;
import org.qubership.integration.platform.engine.security.QipSecurityAccessPolicy;
import org.qubership.integration.platform.engine.service.CheckpointSessionService;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CheckpointSnapshotFixtureProvider implements SnapshotFixtureProvider {
    private static final String PROVIDER_ID = "checkpoint";

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public boolean requiresNodeId() {
        return false;
    }

    @Override
    public boolean supportsExpectedState() {
        return true;
    }

    @Override
    public SnapshotFixture create(String deploymentId, List<SnapshotFixtureBinding> bindings) {
        SnapshotFixtureBinding binding = SnapshotFixtureValidation.requireSingleBinding(bindings, "Checkpoint");
        SnapshotFixtureValidation.requireNoNodeId(binding.definition(), "Checkpoint");
        binding.interactionsByInvocationId().values().forEach(interaction ->
                validateInteraction(binding.definition().getId(), interaction));
        return new CheckpointSnapshotFixture(deploymentId, binding);
    }

    private static void validateInteraction(
            String fixtureId,
            SnapshotFixtureInteraction interaction
    ) {
        if (interaction.getResponse() != null
                || interaction.getExpectedRequest() != null
                || interaction.getExpectedState() == null) {
            throw new IllegalArgumentException(
                    "Checkpoint fixture '" + fixtureId + "' supports only expectedState."
            );
        }
        parseState(fixtureId, interaction.getExpectedState());
    }

    private static CheckpointState parseState(String fixtureId, String value) {
        try {
            return CheckpointState.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Checkpoint fixture '" + fixtureId + "' defines unsupported expected state '" + value + "'.",
                    exception
            );
        }
    }

    private enum CheckpointState {
        EMPTY,
        SAVED,
        RESTORED
    }

    private static final class CheckpointSnapshotFixture implements SnapshotFixture {
        private final String deploymentId;
        private final SnapshotFixtureBinding binding;
        private final String fixtureId;
        private final Map<CheckpointKey, Checkpoint> checkpoints = new LinkedHashMap<>();
        private final Map<String, SessionInfo> sessions = new LinkedHashMap<>();
        private final AtomicReference<CheckpointState> state = new AtomicReference<>(CheckpointState.EMPTY);

        private CheckpointSnapshotFixture(
                String deploymentId,
                SnapshotFixtureBinding binding
        ) {
            this.deploymentId = deploymentId;
            this.binding = binding;
            this.fixtureId = binding.definition().getId();
        }

        @Override
        public String getDeploymentId() {
            return deploymentId;
        }

        @Override
        public void configure(CamelContext camelContext) {
            configure(camelContext, ((ModelCamelContext) camelContext).getRouteDefinitions());
        }

        @Override
        public void configure(CamelContext camelContext, List<RouteDefinition> routes) {
            SecurityAccessPolicyConverter securityPolicyConverter =
                    new SecurityAccessPolicyConverter(ObjectMappers.getObjectMapper());
            camelContext.getTypeConverterRegistry().addTypeConverter(
                    QipSecurityAccessPolicy.class,
                    String.class,
                    new TypeConverterSupport() {
                        @Override
                        public <T> T convertTo(Class<T> type, Exchange exchange, Object value) {
                            return type.cast(securityPolicyConverter.convert(value));
                        }
                    }
            );
            ObjectMapper checkpointMapper = new MapperConfiguration().checkpointMapper();
            CheckpointSessionService storage = checkpointStorage(chainId(camelContext, routes));
            SnapshotFixtureRouteScope.bind(camelContext, routes, deploymentId + ':' + fixtureId, Map.of(
                    "contextSaverProcessor", new ContextSaverProcessor(storage, checkpointMapper, contextOperations()),
                    "contextLoaderProcessor", new ContextLoaderProcessor(storage, checkpointMapper),
                    "rbacPolicy", noOpPolicy()
            ));
        }

        @Override
        public void verifyInvocation(SnapshotScenarioInvocation invocation) {
            SnapshotFixtureInteraction interaction = binding.interaction(invocation.getId());
            CheckpointState expectedState = parseState(fixtureId, interaction.getExpectedState());
            assertEquals(
                    expectedState,
                    state.get(),
                    () -> "Checkpoint fixture '" + fixtureId + "' invocation '" + invocation.getId()
                            + "' has an unexpected state."
            );
        }

        @Override
        public void verify() {
        }

        private CheckpointSessionService checkpointStorage(String chainId) {
            CheckpointSessionService storage = mock(CheckpointSessionService.class);
            doAnswer(invocation -> {
                Checkpoint checkpoint = invocation.getArgument(0);
                String sessionId = invocation.getArgument(1);
                SessionInfo session = sessions.computeIfAbsent(sessionId, id -> session(id, chainId));
                session.assignCheckpoint(checkpoint);
                checkpoints.put(new CheckpointKey(chainId, sessionId, checkpoint.getCheckpointElementId()), checkpoint);
                state.set(CheckpointState.SAVED);
                return null;
            }).when(storage).saveAndAssignCheckpoint(any(Checkpoint.class), any());
            when(storage.findCheckpointForRestore(any(), any(), any())).thenAnswer(invocation -> checkpoints.get(
                    new CheckpointKey(invocation.getArgument(1), invocation.getArgument(0), invocation.getArgument(2))
            ));
            when(storage.findOriginalSessionInfo(any())).thenAnswer(invocation -> {
                SessionInfo session = sessions.get(invocation.getArgument(0));
                while (session != null && session.getParentSession() != null) {
                    session = session.getParentSession();
                }
                return Optional.ofNullable(session);
            });
            doAnswer(invocation -> {
                String sessionId = invocation.getArgument(0);
                String parentId = invocation.getArgument(1);
                sessions.computeIfAbsent(sessionId, id -> session(id, chainId)).setParentSession(sessions.get(parentId));
                state.set(CheckpointState.RESTORED);
                return null;
            }).when(storage).updateSessionParent(any(), any());
            return storage;
        }

        private String chainId(CamelContext context, List<RouteDefinition> routes) {
            return routes.stream()
                    .map(RouteDefinition::getGroup)
                    .filter(Objects::nonNull)
                    .map(group -> context.getRegistry().lookupByNameAndType("DeploymentInfo-" + group, DeploymentInfo.class))
                    .filter(Objects::nonNull)
                    .map(metadata -> metadata.getChain().getId())
                    .findFirst()
                    .orElse(deploymentId);
        }

        private static SessionInfo session(String id, String chainId) {
            SessionInfo session = new SessionInfo();
            session.setId(id);
            session.setChainId(chainId);
            return session;
        }

        @SuppressWarnings("unchecked")
        private static Instance<ContextOperationsWrapper> contextOperations() {
            Instance<ContextOperationsWrapper> operations = mock(Instance.class);
            when(operations.stream()).thenAnswer(invocation -> Stream.empty());
            return operations;
        }

        private static Policy noOpPolicy() {
            return new Policy() {
                @Override
                public void beforeWrap(Route route, NamedNode definition) {
                }

                @Override
                public Processor wrap(Route route, Processor processor) {
                    return processor;
                }
            };
        }
    }

    private record CheckpointKey(String chainId, String sessionId, String elementId) {
    }
}
