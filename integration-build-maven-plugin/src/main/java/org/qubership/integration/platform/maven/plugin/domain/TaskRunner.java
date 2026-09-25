package org.qubership.integration.platform.maven.plugin.domain;

import org.qubership.integration.platform.maven.plugin.domain.configuration.ApplicationConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.function.BiConsumer;

public class TaskRunner {
    public <T> void execute(BiConsumer<ApplicationContext, TaskContext<T>> task, TaskContext<T> taskContext) {
        try (AnnotationConfigApplicationContext context =
                 new AnnotationConfigApplicationContext(ApplicationConfiguration.class)) {
            task.accept(context, taskContext);
        }
    }
}
