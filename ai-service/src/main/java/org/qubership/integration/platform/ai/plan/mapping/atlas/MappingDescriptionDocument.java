package org.qubership.integration.platform.ai.plan.mapping.atlas;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;

/**
 * Mapper-2 mapping description. JSON names match runtime-catalog mapper types; this module does
 * not depend on runtime-catalog.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MappingDescriptionDocument(
    MessageSchema source,
    MessageSchema target,
    List<Constant> constants,
    List<MappingAction> actions) {

  public MappingDescriptionDocument {
    constants = constants == null ? List.of() : List.copyOf(constants);
    actions = actions == null ? List.of() : List.copyOf(actions);
  }

  public MappingDescriptionDocument withSource(MessageSchema source) {
    return new MappingDescriptionDocument(source, target, constants, actions);
  }

  public MappingDescriptionDocument withActions(List<MappingAction> actions) {
    return new MappingDescriptionDocument(source, target, constants, actions);
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record MessageSchema(List<Attribute> headers, List<Attribute> properties, DataType body) {

    public MessageSchema {
      headers = headers == null ? List.of() : List.copyOf(headers);
      properties = properties == null ? List.of() : List.copyOf(properties);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Attribute(
      String id, String name, DataType type, String defaultValue, Boolean required) {

    public Attribute(String id, String name, DataType type) {
      this(id, name, type, null, null);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ObjectSchema(String id, List<Attribute> attributes) {

    public ObjectSchema {
      attributes = attributes == null ? List.of() : List.copyOf(attributes);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonTypeInfo(
      use = JsonTypeInfo.Id.NAME,
      include = JsonTypeInfo.As.EXISTING_PROPERTY,
      property = "name",
      visible = true)
  @JsonSubTypes({
    @JsonSubTypes.Type(value = NullType.class, name = "null"),
    @JsonSubTypes.Type(value = StringType.class, name = "string"),
    @JsonSubTypes.Type(value = NumberType.class, name = "number"),
    @JsonSubTypes.Type(value = BooleanType.class, name = "boolean"),
    @JsonSubTypes.Type(value = ArrayType.class, name = "array"),
    @JsonSubTypes.Type(value = ObjectType.class, name = "object"),
    @JsonSubTypes.Type(value = ReferenceType.class, name = "reference")
  })
  public sealed interface DataType
      permits NullType, StringType, NumberType, BooleanType, ArrayType, ObjectType, ReferenceType {

    String name();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record NullType(String name) implements DataType {

    public NullType {
      name = "null";
    }

    public NullType() {
      this("null");
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record StringType(String name) implements DataType {

    public StringType {
      name = "string";
    }

    public StringType() {
      this("string");
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record NumberType(String name) implements DataType {

    public NumberType {
      name = "number";
    }

    public NumberType() {
      this("number");
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record BooleanType(String name) implements DataType {

    public BooleanType {
      name = "boolean";
    }

    public BooleanType() {
      this("boolean");
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ArrayType(String name, DataType itemType) implements DataType {

    public ArrayType {
      name = "array";
    }

    public ArrayType(DataType itemType) {
      this("array", itemType);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ObjectType(
      String name,
      ObjectSchema schema,
      @JsonInclude(JsonInclude.Include.NON_EMPTY) List<TypeDefinition> definitions)
      implements DataType {

    public ObjectType {
      name = "object";
      definitions = definitions == null ? List.of() : List.copyOf(definitions);
    }

    public ObjectType(ObjectSchema schema, List<TypeDefinition> definitions) {
      this("object", schema, definitions);
    }

    public ObjectType(ObjectSchema schema) {
      this(schema, List.of());
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ReferenceType(String name, String definitionId) implements DataType {

    public ReferenceType {
      name = "reference";
    }

    public ReferenceType(String definitionId) {
      this("reference", definitionId);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TypeDefinition(String id, String name, DataType type) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record MappingAction(
      String id,
      List<ElementReference> sources,
      AttributeReference target,
      Transformation transformation) {

    public MappingAction {
      sources = sources == null ? List.of() : List.copyOf(sources);
    }

    public MappingAction withTransformation(Transformation transformation) {
      return new MappingAction(id, sources, target, transformation);
    }

    public MappingAction withTransformation(String name, List<String> parameters) {
      return withTransformation(new Transformation(name, parameters));
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Transformation(String name, List<String> parameters) {

    public Transformation {
      parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonTypeInfo(
      use = JsonTypeInfo.Id.NAME,
      include = JsonTypeInfo.As.EXISTING_PROPERTY,
      property = "type",
      visible = true)
  @JsonSubTypes({
    @JsonSubTypes.Type(value = AttributeReference.class, name = "attribute"),
    @JsonSubTypes.Type(value = ConstantReference.class, name = "constant")
  })
  public sealed interface ElementReference permits AttributeReference, ConstantReference {

    String type();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AttributeReference(String type, String kind, List<String> path)
      implements ElementReference {

    public AttributeReference {
      type = type == null || type.isBlank() ? "attribute" : type;
      path = path == null ? List.of() : List.copyOf(path);
    }

    public AttributeReference(String kind, List<String> path) {
      this("attribute", kind, path);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ConstantReference(String type, String constantId) implements ElementReference {

    public ConstantReference {
      type = type == null || type.isBlank() ? "constant" : type;
    }

    public ConstantReference(String constantId) {
      this("constant", constantId);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Constant(String id, String name, DataType type, ValueSupplier valueSupplier) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonTypeInfo(
      use = JsonTypeInfo.Id.NAME,
      include = JsonTypeInfo.As.EXISTING_PROPERTY,
      property = "kind",
      visible = true)
  @JsonSubTypes({
    @JsonSubTypes.Type(value = GivenValue.class, name = "given"),
    @JsonSubTypes.Type(value = GeneratedValue.class, name = "generated")
  })
  public sealed interface ValueSupplier permits GivenValue, GeneratedValue {

    String kind();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record GivenValue(String kind, String value) implements ValueSupplier {

    public GivenValue {
      kind = "given";
    }

    public GivenValue(String value) {
      this("given", value);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record GeneratedValue(String kind, ValueGenerator generator) implements ValueSupplier {

    public GeneratedValue {
      kind = "generated";
    }

    public GeneratedValue(ValueGenerator generator) {
      this("generated", generator);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ValueGenerator(String name, List<String> parameters) {

    public ValueGenerator {
      parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }
  }
}
