package org.qubership.integration.platform.ai.flow;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class FlowExecutorContextTest {

  @Test
  void shouldSubmitWithApplicationClassLoaderAndRestoreCaller() {
    ClassLoader original = Thread.currentThread().getContextClassLoader();
    ClassLoader foreign = new ClassLoader(original) {};
    var submissionLoader = new AtomicReference<ClassLoader>();
    var result = new AtomicReference<String>();
    var executor = FlowExecutorContext.withApplicationClassLoader(command -> {
      submissionLoader.set(Thread.currentThread().getContextClassLoader());
      command.run();
    });
    try {
      Thread.currentThread().setContextClassLoader(foreign);
      executor.execute(() -> result.set("resumed"));

      assertAll(
          () -> assertSame(FlowExecutorContext.class.getClassLoader(), submissionLoader.get()),
          () -> assertSame(foreign, Thread.currentThread().getContextClassLoader()),
          () -> assertEquals("resumed", result.get()));
    } finally {
      Thread.currentThread().setContextClassLoader(original);
    }
  }

  @Test
  void shouldRestoreCallerClassLoaderWhenDelegateRejectsSubmission() {
    ClassLoader original = Thread.currentThread().getContextClassLoader();
    ClassLoader foreign = new ClassLoader(original) {};
    var executor = FlowExecutorContext.withApplicationClassLoader(command -> {
      throw new RejectedExecutionException("fixture rejection");
    });
    try {
      Thread.currentThread().setContextClassLoader(foreign);

      assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> {}));
      assertSame(foreign, Thread.currentThread().getContextClassLoader());
    } finally {
      Thread.currentThread().setContextClassLoader(original);
    }
  }

  @Test
  void shouldReadEachCallerContextWhenExecutorIsReused() {
    var tenant = new ThreadLocal<String>();
    List<String> submissions = new ArrayList<>();
    tenant.set("acquisition-tenant");
    var executor = FlowExecutorContext.withApplicationClassLoader(command -> {
      submissions.add(tenant.get());
      command.run();
    });
    try {
      tenant.set("first-tenant");
      executor.execute(() -> {});
      tenant.set("second-tenant");
      executor.execute(() -> {});

      assertEquals(List.of("first-tenant", "second-tenant"), submissions);
    } finally {
      tenant.remove();
    }
  }
}
