package org.qubership.integration.platform.ai.flow;

import io.quarkiverse.flow.recorders.WorkflowApplicationBuilderCustomizer;
import io.serverlessworkflow.impl.ExecutorServiceFactory;
import io.serverlessworkflow.impl.WorkflowApplication;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.threads.JBossExecutors;

/** Preserves the application class loader when Flow submits work from shared timer threads. */
@ApplicationScoped
public class FlowExecutorContext
    implements WorkflowApplicationBuilderCustomizer, ExecutorServiceFactory {

  private final ExecutorService executor;

  @Inject
  public FlowExecutorContext(ManagedExecutor managedExecutor) {
    executor = withApplicationClassLoader(managedExecutor);
  }

  @Override
  public void customize(WorkflowApplication.Builder builder) {
    builder.withExecutorFactory(this);
  }

  @Override
  public ExecutorService get() {
    return executor;
  }

  @Override
  public void close() {
    // Quarkus owns the managed executor.
  }

  static ExecutorService withApplicationClassLoader(Executor delegate) {
    Executor applicationContext = JBossExecutors.contextClassLoaderExecutor(
        Runnable::run, FlowExecutorContext.class.getClassLoader());
    return JBossExecutors.protectedExecutorService(
        command -> applicationContext.execute(() -> delegate.execute(command)));
  }
}
