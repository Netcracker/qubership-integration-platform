package org.qubership.integration.platform.ai.compiler.capture;

import dev.langchain4j.exception.ToolArgumentsException;

/** Detects LangChain4j tool-argument deserialization failures in exception chains. */
public final class ToolArgumentsFailures {

  private ToolArgumentsFailures() {}

  public static boolean isToolArgumentsFailure(Throwable error) {
    Throwable current = error;
    while (current != null) {
      if (current instanceof ToolArgumentsException) {
        return true;
      }
      String message = current.getMessage();
      if (message != null && message.contains("ToolArgumentsException")) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  static String message(Throwable error) {
    Throwable current = error;
    while (current != null) {
      if (current instanceof ToolArgumentsException) {
        return current.getMessage();
      }
      current = current.getCause();
    }
    return error != null ? error.getMessage() : "invalid tool arguments";
  }
}
