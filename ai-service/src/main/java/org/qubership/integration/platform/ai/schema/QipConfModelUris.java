package org.qubership.integration.platform.ai.schema;

/** URI prefix and classpath mapping for embedded QIP YAML schemas under {@code qip-schemas/}. */
public final class QipConfModelUris {

  public static final String CONF_MODEL_PREFIX = "http://qubership.org/schemas/product/qip/";

  private static final String SCHEMA_SUFFIX = ".schema.yaml";

  private QipConfModelUris() {}

  /** Conf-model URI for an element type (matches {@code $id} in monorepo {@code schemas/} YAML). */
  public static String elementModelUri(String elementType) {
    return CONF_MODEL_PREFIX + "element/" + elementType + SCHEMA_SUFFIX;
  }

  /**
   * Maps a conf-model URI (without fragment) to a classpath resource path such as {@code
   * qip-schemas/element/service-call.schema.yaml}.
   */
  public static String classpathResourceForModelUri(String modelUriWithoutFragment) {
    if (modelUriWithoutFragment == null || modelUriWithoutFragment.isBlank()) {
      throw new SchemaNotFoundException("Empty schema URI");
    }
    if (!modelUriWithoutFragment.startsWith(CONF_MODEL_PREFIX)) {
      throw new SchemaNotFoundException(
          "Unsupported schema URI (expected conf-model prefix): " + modelUriWithoutFragment);
    }
    String tail = modelUriWithoutFragment.substring(CONF_MODEL_PREFIX.length());
    if (tail.isBlank()) {
      throw new SchemaNotFoundException("Invalid schema URI tail: " + modelUriWithoutFragment);
    }
    if (tail.endsWith(SCHEMA_SUFFIX)) {
      return "qip-schemas/" + tail;
    }
    return "qip-schemas/" + tail + SCHEMA_SUFFIX;
  }

  public static String stripFragment(String ref) {
    int hash = ref.indexOf('#');
    return hash >= 0 ? ref.substring(0, hash) : ref;
  }

  public static String fragmentPart(String ref) {
    int hash = ref.indexOf('#');
    return hash >= 0 ? ref.substring(hash) : "";
  }

  public static String elementSchemaClasspath(String elementType) {
    if (elementType == null || elementType.isBlank()) {
      throw new SchemaNotFoundException("elementType is required");
    }
    String t = elementType.trim();
    if (t.contains("..") || t.contains("/") || t.contains("\\")) {
      throw new SchemaNotFoundException("Invalid elementType: " + elementType);
    }
    return "qip-schemas/element/" + t + ".schema.yaml";
  }
}
