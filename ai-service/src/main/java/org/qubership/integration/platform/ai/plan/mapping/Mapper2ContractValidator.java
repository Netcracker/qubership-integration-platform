package org.qubership.integration.platform.ai.plan.mapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.ArrayType;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.Attribute;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.AttributeReference;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.Constant;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.ConstantReference;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.DataType;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.ElementReference;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.MappingAction;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.MessageSchema;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.ObjectType;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.Transformation;
import org.qubership.integration.platform.ai.plan.mapping.envelope.MappingEnvelope;

/**
 * Checks mapper-2 captures against the AtlasMap interpreter mapping contract. Does not call
 * AtlasMap or apply a sample body.
 */
public final class Mapper2ContractValidator {

  private static final String PREFIX = "Mapping contract:";
  private static final Set<String> TRANSFORMATION_NAMES =
      Set.of(
          "defaultValue",
          "formatDateTime",
          "conditional",
          "dictionary",
          "expression",
          "replaceAll",
          "trim");

  private Mapper2ContractValidator() {}

  public static void validate(MappingEnvelope envelope, MappingDescriptionDocument captured) {
    Objects.requireNonNull(envelope, "envelope");
    Objects.requireNonNull(captured, "captured");
    for (MappingAction action : captured.actions()) {
      validateAction(envelope, captured, action);
    }
  }

  private static void validateAction(
      MappingEnvelope envelope, MappingDescriptionDocument captured, MappingAction action) {
    if (action.sources().isEmpty() || action.target() == null) {
      throw contract("cannot define input or output fields");
    }
    List<Boolean> sourceArrays = new ArrayList<>();
    for (ElementReference source : action.sources()) {
      sourceArrays.add(resolveSourceIsArray(envelope, captured, source));
    }
    boolean targetIsArray = resolveAttributeIsArray(envelope, envelope.target(), action.target());
    validateSupportedMappings(sourceArrays, targetIsArray, action.transformation() == null);
    validateTransformation(action.transformation());
  }

  private static boolean resolveSourceIsArray(
      MappingEnvelope envelope, MappingDescriptionDocument captured, ElementReference source) {
    return switch (source) {
      case ConstantReference constantReference -> {
        if (!hasConstant(captured, constantReference.constantId())) {
          throw contract("cannot find constant '" + constantReference.constantId() + "'");
        }
        yield false;
      }
      case AttributeReference attributeReference ->
          resolveAttributeIsArray(envelope, envelope.source(), attributeReference);
    };
  }

  private static boolean resolveAttributeIsArray(
      MappingEnvelope envelope, MessageSchema schema, AttributeReference reference) {
    List<String> path = reference.path();
    if (path.isEmpty()) {
      throw contract("cannot define input or output fields");
    }
    for (String id : path) {
      if (!envelope.idToPath().containsKey(id)) {
        throw contract("cannot find attribute '" + id + "' in the envelope");
      }
    }
    String lastId = path.getLast();
    Attribute attribute = findAttribute(schema, lastId);
    if (attribute == null) {
      throw contract("cannot find attribute '" + lastId + "' in the envelope");
    }
    return isArray(attribute.type());
  }

  private static void validateSupportedMappings(
      List<Boolean> sourceArrays, boolean targetIsArray, boolean noTransformation) {
    boolean anyArray = sourceArrays.contains(true);
    boolean anyPrimitive = sourceArrays.contains(false);
    if (anyArray && anyPrimitive && targetIsArray && noTransformation) {
      throw contract("cannot combine an array and a primitive field into an array");
    }
    if (sourceArrays.size() > 1 && anyArray && targetIsArray && noTransformation) {
      throw contract("cannot combine several arrays");
    }
    if (sourceArrays.size() > 1 && noTransformation && !targetIsArray) {
      throw contract("transformation is required to aggregate multiple fields");
    }
  }

  private static void validateTransformation(Transformation transformation) {
    if (transformation == null) {
      return;
    }
    String name = transformation.name();
    if (name == null || !TRANSFORMATION_NAMES.contains(name)) {
      throw contract("unknown transformation '" + name + "'");
    }
    List<String> parameters = transformation.parameters();
    if ("trim".equals(name) && !parameters.isEmpty()) {
      throw contract("trim has no parameters");
    }
    if ("formatDateTime".equals(name) && parameters.size() != 2) {
      throw contract("formatDateTime requires two parameters");
    }
    if ("defaultValue".equals(name) && parameters.size() != 1) {
      throw contract("defaultValue requires one parameter");
    }
  }

  private static boolean hasConstant(MappingDescriptionDocument captured, String constantId) {
    for (Constant constant : captured.constants()) {
      if (constantId.equals(constant.id())) {
        return true;
      }
    }
    return false;
  }

  private static Attribute findAttribute(MessageSchema schema, String id) {
    Attribute found = findInAttributes(schema.headers(), id);
    if (found != null) {
      return found;
    }
    found = findInAttributes(schema.properties(), id);
    if (found != null) {
      return found;
    }
    return findInType(schema.body(), id);
  }

  private static Attribute findInAttributes(List<Attribute> attributes, String id) {
    for (Attribute attribute : attributes) {
      if (id.equals(attribute.id())) {
        return attribute;
      }
      Attribute nested = findInType(attribute.type(), id);
      if (nested != null) {
        return nested;
      }
    }
    return null;
  }

  private static Attribute findInType(DataType type, String id) {
    if (type instanceof ObjectType objectType) {
      return findInAttributes(objectType.schema().attributes(), id);
    }
    if (type instanceof ArrayType arrayType) {
      return findInType(arrayType.itemType(), id);
    }
    return null;
  }

  private static boolean isArray(DataType type) {
    return type != null && "array".equals(type.name());
  }

  private static IllegalArgumentException contract(String detail) {
    return new IllegalArgumentException(PREFIX + " " + detail);
  }
}
