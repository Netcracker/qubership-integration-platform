package org.qubership.integration.platform.ai.integration.catalog.descriptor;

/**
 * A catalog element descriptor could not be loaded. Materialization must fail closed instead of
 * guessing from a hardcoded type list.
 */
public final class CatalogElementDescriptorException extends RuntimeException {

  private final String elementType;

  public CatalogElementDescriptorException(String elementType, String reason) {
    super(message(elementType, reason));
    this.elementType = elementType;
  }

  public CatalogElementDescriptorException(String elementType, String reason, Throwable cause) {
    super(message(elementType, reason), cause);
    this.elementType = elementType;
  }

  public String elementType() {
    return elementType;
  }

  private static String message(String elementType, String reason) {
    return "Cannot load catalog element descriptor for type '" + elementType + "': " + reason;
  }
}
