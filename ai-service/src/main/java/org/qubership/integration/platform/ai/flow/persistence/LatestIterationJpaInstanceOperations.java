package org.qubership.integration.platform.ai.flow.persistence;

import io.quarkiverse.flow.persistence.jpa.JpaInstanceOperations;
import io.quarkiverse.flow.persistence.jpa.WorkflowInstanceEntity;
import io.quarkiverse.flow.persistence.jpa.WorkflowInstanceKey;
import io.quarkiverse.flow.persistence.jpa.WorkflowInstanceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.serverlessworkflow.impl.WorkflowDefinition;
import io.serverlessworkflow.impl.WorkflowDefinitionId;
import io.serverlessworkflow.impl.persistence.PersistenceWorkflowInfo;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import jakarta.transaction.Transactional;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Replaces Quarkus Flow 0.14.0 JPA restore so a looped create-chain instance can come back after
 * process restart instead of taking the service down.
 */
@Alternative
@Priority(Interceptor.Priority.APPLICATION + 10)
@ApplicationScoped
public class LatestIterationJpaInstanceOperations extends JpaInstanceOperations {

  private final WorkflowInstanceRepository instances;

  @Inject
  public LatestIterationJpaInstanceOperations(WorkflowInstanceRepository instances) {
    this.instances = Objects.requireNonNull(instances, "instances");
  }

  @Override
  public Stream<PersistenceWorkflowInfo> scanAll(
      String applicationId, WorkflowDefinition definition) {
    QuarkusTransaction.begin();
    WorkflowDefinitionId id = definition.id();
    return instances
        .stream(
            "select x from WorkflowInstanceEntity x where x.applicationId=?1 and x.workflowNamespace=?2 and x.workflowName=?3 and x.workflowVersion=?4",
            applicationId,
            id.namespace(),
            id.name(),
            id.version())
        .map(this::workflowInfo)
        .onClose(QuarkusTransaction::commit);
  }

  @Override
  @Transactional
  public Optional<PersistenceWorkflowInfo> readWorkflowInfo(
      WorkflowDefinition definition, String instanceId) {
    return instances
        .findByIdOptional(new WorkflowInstanceKey(instanceId, definition.application().id()))
        .map(this::workflowInfo);
  }

  private PersistenceWorkflowInfo workflowInfo(WorkflowInstanceEntity entity) {
    return new PersistenceWorkflowInfo(
        entity.getInstanceId(),
        entity.getStartedAt(),
        entity.getInput(),
        entity.getStatus(),
        LatestIterationTaskInfos.merge(entity.getTasks()));
  }
}
