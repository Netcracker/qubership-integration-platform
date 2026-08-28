package org.qubership.integration.platform.ai.integration.catalog.model;

/**
 * One predicate of {@code POST /v1/systems/filter}. The catalog ANDs the predicates of a request.
 *
 * <p>The JSON field is {@code column}, not {@code feature}: runtime-catalog's {@code
 * FilterRequestDTO} renames it, and a request that says {@code feature} deserializes to a null
 * column and fails inside the filter builder.
 *
 * @param column runtime-catalog {@code FilterFeature} name, for example {@code NAME}, {@code
 *     PROTOCOL}, {@code URL} (an operation path), {@code SPECIFICATION_GROUP}
 * @param condition runtime-catalog {@code FilterCondition} name, for example {@code CONTAINS}.
 *     {@code PROTOCOL} accepts {@code IN} and {@code CONTAINS} only — {@code IS} matches nothing,
 *     and an unknown protocol value throws inside the catalog.
 * @param value the value to match
 */
public record CatalogSystemFilter(String column, String condition, String value) {

  public static CatalogSystemFilter contains(String column, String value) {
    return new CatalogSystemFilter(column, "CONTAINS", value);
  }

  public static CatalogSystemFilter in(String column, String value) {
    return new CatalogSystemFilter(column, "IN", value);
  }
}
