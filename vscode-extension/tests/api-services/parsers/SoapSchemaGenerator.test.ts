// Unit cases for `SoapSchemaGenerator`, on the XSD constructs the golden import cannot reach.
//
// `tests/web/import/soap.test.ts` drives one WSDL fixture end to end and pins its output byte for
// byte, which took the generator to 80.05 % of statements and 61.13 % of branches. The arms left
// white are not dead code: each needs a WSDL shaped differently from that fixture, and a fixture
// per shape would mean a golden per shape, since the golden is the whole service folder the import
// writes. So these cases go straight onto the generator instead, each naming the branch it covers
// and why one fixture misses it.
//
// Every case parses a real WSDL with `WsdlParser` rather than hand-building a `WsdlParseResult`:
// the generator reads DOM elements out of it, and a hand-built store would pin the test's own idea
// of the parse instead of the parser's.
//
// These cases enter every function in the file. What is left falls in three groups, none of them
// chased:
//
//   - unreachable. `mapPrimitiveType`'s and `buildQualifiedNameKey`'s `!qName` guards, which every
//     call site passes a defined name or catches with a ternary first; the facet loop's
//     `value === null` skip, because `@xmldom/xmldom` answers `""` for a missing attribute and the
//     switch then drops the child through its default arm; and `generateDefinitionName`'s collision
//     counter, which needs two declarations of one name in one namespace that `XmlSchemaStore` has
//     already de-duplicated
//   - reachable only from a degenerate document: a `wsdl:part` with no name, element or type, a
//     `wsdl:definitions` with no `targetNamespace` that still prefixes its message references, and
//     a non-element child of an `xsd:sequence`
//   - reachable and not covered: an `xsd:element ref` inside an `xsd:choice`, an unprefixed type
//     reference inside a schema, and a `complexContent` extension whose base is not an object
import { SoapSchemaGenerator } from "../../../src/web/api-services/parsers/soap/SoapSchemaGenerator";
import { WsdlParser } from "../../../src/web/api-services/parsers/soap/WsdlParser";

const TNS = "urn:test";

function wsdl(body: string): string {
  return `<?xml version="1.0" encoding="UTF-8"?>
<wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                  xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                  xmlns:tns="${TNS}"
                  xmlns:other="urn:other"
                  targetNamespace="${TNS}">
${body}
</wsdl:definitions>`;
}

// One operation named `op`, whose input is the message the body declares. The binding is not
// decoration: `WsdlParser` takes the operation list from `wsdl:binding` alone, so a WSDL with a
// `portType` and no binding parses into an empty operation set and the generator sees nothing.
function operationBody(messageParts: string, types = ""): string {
  return `${types}
  <wsdl:message name="request">
${messageParts}
  </wsdl:message>
  <wsdl:portType name="OrderPort">
    <wsdl:operation name="op">
      <wsdl:input message="tns:request"/>
    </wsdl:operation>
  </wsdl:portType>
  <wsdl:binding name="OrderBinding" type="tns:OrderPort">
    <wsdl:operation name="op"/>
  </wsdl:binding>`;
}

function buildSchemas(body: string): Map<string, any> {
  const parsed = new WsdlParser().parse(
    [{ uri: "test.wsdl", content: wsdl(body) }],
    "test.wsdl",
  );
  return new SoapSchemaGenerator(parsed).buildOperationSchemas() as Map<
    string,
    any
  >;
}

function requestSchema(body: string): any {
  return buildSchemas(body).get("op")?.request?.["application/xml"];
}

describe("SoapSchemaGenerator", () => {
  // `buildSchemaForMessagePart`'s `part.type` and bare `part.name` arms. The fixture is
  // document-style throughout — every `wsdl:part` carries an `element` — so an RPC-style WSDL, a
  // whole WSDL style rather than an edge case, enters neither.
  it("builds an RPC-style message from part types and bare part names", () => {
    const schema = requestSchema(
      operationBody(`    <wsdl:part name="amount" type="xsd:decimal"/>
    <wsdl:part name="note"/>`),
    );

    expect(schema).toEqual({
      type: "object",
      properties: {
        amount: { type: "number" },
        note: { type: "string" },
      },
      required: ["amount", "note"],
    });
  });

  // `mapPrimitiveType`'s date, time, base64Binary, anyURI and default arms. The fixture names a
  // handful of XSD primitives, and covering the table from a WSDL would mean an element per
  // primitive in the committed golden.
  it("maps the XSD date, binary and URI primitives", () => {
    const schema = requestSchema(
      operationBody(`    <wsdl:part name="when" type="xsd:date"/>
    <wsdl:part name="at" type="xsd:time"/>
    <wsdl:part name="blob" type="xsd:base64Binary"/>
    <wsdl:part name="href" type="xsd:anyURI"/>
    <wsdl:part name="span" type="xsd:duration"/>`),
    );

    expect(schema.properties).toEqual({
      when: { type: "string", format: "date" },
      at: { type: "string", format: "time" },
      blob: { type: "string", contentEncoding: "base64" },
      href: { type: "string", format: "uri" },
      // No arm matches `duration`, so the default drops it to a bare string.
      span: { type: "string" },
    });
  });

  // `buildSimpleTypeSchema`'s facet arms other than `enumeration`. The fixture's one
  // `xsd:simpleType` carries an enumeration alone, and a real schema constrains lengths, patterns
  // and ranges.
  it("reads length, pattern and range facets off a restriction", () => {
    const schema = requestSchema(
      operationBody(
        `    <wsdl:part name="sku" type="tns:Sku"/>
    <wsdl:part name="qty" type="tns:Quantity"/>
    <wsdl:part name="ratio" type="tns:Ratio"/>`,
        `  <wsdl:types>
    <xsd:schema targetNamespace="${TNS}">
      <xsd:simpleType name="Sku">
        <xsd:restriction base="xsd:string">
          <!-- Not a facet, so the switch drops it through the default arm. -->
          <xsd:annotation><xsd:documentation>stock keeping unit</xsd:documentation></xsd:annotation>
          <xsd:pattern value="[A-Z]{3}-[0-9]+"/>
          <xsd:minLength value="7"/>
          <xsd:maxLength value="16"/>
        </xsd:restriction>
      </xsd:simpleType>
      <xsd:simpleType name="Quantity">
        <xsd:restriction base="xsd:int">
          <xsd:minInclusive value="1"/>
          <xsd:maxInclusive value="999"/>
        </xsd:restriction>
      </xsd:simpleType>
      <xsd:simpleType name="Ratio">
        <xsd:restriction base="xsd:double">
          <xsd:minExclusive value="0"/>
          <xsd:maxExclusive value="1"/>
        </xsd:restriction>
      </xsd:simpleType>
    </xsd:schema>
  </wsdl:types>`,
      ),
    );

    expect(schema.definitions).toEqual({
      Type_urn_test_Sku: {
        type: "string",
        pattern: "[A-Z]{3}-[0-9]+",
        minLength: 7,
        maxLength: 16,
      },
      Type_urn_test_Quantity: { type: "integer", minimum: 1, maximum: 999 },
      Type_urn_test_Ratio: {
        type: "number",
        exclusiveMinimum: 0,
        exclusiveMaximum: 1,
      },
    });
  });

  // `buildSimpleTypeSchema`'s `xsd:list` arm and its final fallback. The fixture declares neither a
  // list nor a union.
  it("turns an xsd:list into an array and leaves a union a string", () => {
    const schema = requestSchema(
      operationBody(
        `    <wsdl:part name="tags" type="tns:Tags"/>
    <wsdl:part name="either" type="tns:Either"/>`,
        `  <wsdl:types>
    <xsd:schema targetNamespace="${TNS}">
      <xsd:simpleType name="Tags">
        <xsd:list itemType="xsd:string"/>
      </xsd:simpleType>
      <xsd:simpleType name="Either">
        <xsd:union memberTypes="xsd:string xsd:int"/>
      </xsd:simpleType>
    </xsd:schema>
  </wsdl:types>`,
      ),
    );

    expect(schema.definitions).toEqual({
      Type_urn_test_Tags: { type: "array", items: { type: "string" } },
      // No arm handles a union, so the member types are dropped.
      Type_urn_test_Either: { type: "string" },
    });
  });

  // Four fallbacks the fixture skirts, because every type it declares carries a model group and
  // every element it declares carries a type: `buildComplexTypeSchema`'s trailing `{ type: object }`
  // for a type with no model group, `buildElementSchemaBody`'s inline `xsd:simpleType` arm and its
  // trailing string, and `buildAnonymousElementSchema`'s inline `xsd:complexType` arm and its
  // trailing string.
  it("falls back for a type with no model group and an element with no type", () => {
    const schema = requestSchema(
      operationBody(
        `    <wsdl:part name="opaque" type="tns:Opaque"/>
    <wsdl:part name="loose" element="tns:loose"/>
    <wsdl:part name="code" element="tns:code"/>
    <wsdl:part name="wrap" element="tns:wrap"/>`,
        `  <wsdl:types>
    <xsd:schema targetNamespace="${TNS}">
      <xsd:complexType name="Opaque">
        <xsd:attribute name="id" type="xsd:string"/>
      </xsd:complexType>
      <xsd:element name="loose"/>
      <xsd:element name="code">
        <xsd:simpleType>
          <xsd:restriction base="xsd:string">
            <xsd:enumeration value="A"/>
          </xsd:restriction>
        </xsd:simpleType>
      </xsd:element>
      <xsd:element name="wrap">
        <xsd:complexType>
          <xsd:sequence>
            <xsd:element name="inner">
              <xsd:complexType>
                <xsd:sequence>
                  <xsd:element name="leaf" type="xsd:string"/>
                </xsd:sequence>
              </xsd:complexType>
            </xsd:element>
            <xsd:element name="bare"/>
          </xsd:sequence>
        </xsd:complexType>
      </xsd:element>
    </xsd:schema>
  </wsdl:types>`,
      ),
    );

    expect(schema.definitions).toEqual({
      // Attributes are not read, so a type with no sequence, all, choice or content model comes out
      // an object with no properties.
      Type_urn_test_Opaque: { type: "object" },
      Element_urn_test_loose: { type: "string" },
      Element_urn_test_code: { type: "string", enum: ["A"] },
      Element_urn_test_wrap: {
        type: "object",
        properties: {
          inner: {
            type: "object",
            properties: { leaf: { type: "string" } },
            required: ["leaf"],
          },
          bare: { type: "string" },
        },
        required: ["inner", "bare"],
      },
    });
  });

  // The two `definitionRefs` hits, in `buildSchemaFromElement` and `buildSchemaForTypeReference`.
  // The fixture references each of its declarations once, so neither early return runs, and a
  // regression there mints a second definition under a `_1` suffix rather than reusing the first.
  it("reuses one definition for a type and an element referenced twice", () => {
    const schema = requestSchema(
      operationBody(
        `    <wsdl:part name="pair" element="tns:pair"/>`,
        `  <wsdl:types>
    <xsd:schema targetNamespace="${TNS}">
      <xsd:complexType name="Money">
        <xsd:sequence>
          <xsd:element name="amount" type="xsd:decimal"/>
        </xsd:sequence>
      </xsd:complexType>
      <xsd:element name="price" type="tns:Money"/>
      <xsd:element name="pair">
        <xsd:complexType>
          <xsd:sequence>
            <xsd:element name="net" type="tns:Money"/>
            <xsd:element name="gross" type="tns:Money"/>
            <xsd:element ref="tns:price"/>
            <xsd:element name="again" ref="tns:price"/>
          </xsd:sequence>
        </xsd:complexType>
      </xsd:element>
    </xsd:schema>
  </wsdl:types>`,
      ),
    );

    // A definition is assigned after its body is built, so the two it references come first.
    expect(Object.keys(schema.definitions)).toEqual([
      "Type_urn_test_Money",
      "Element_urn_test_price",
      "Element_urn_test_pair",
    ]);
    expect(schema.definitions.Element_urn_test_pair.properties).toEqual({
      net: { $ref: "#/definitions/Type_urn_test_Money" },
      gross: { $ref: "#/definitions/Type_urn_test_Money" },
      price: { $ref: "#/definitions/Element_urn_test_price" },
      again: { $ref: "#/definitions/Element_urn_test_price" },
    });
  });

  // `resolveMessage`'s search by name, and the skip for an operation with no schema at all. The
  // fixture prefixes every `message` attribute, so the direct lookup always hits; an unprefixed one
  // resolves against the default namespace, which `wsdl:definitions` does not declare here, and
  // misses both keys.
  it("finds a message referenced without a prefix and skips an empty operation", () => {
    const schemas = buildSchemas(`  <wsdl:message name="request">
    <wsdl:part name="x" type="xsd:string"/>
  </wsdl:message>
  <wsdl:portType name="OrderPort">
    <wsdl:operation name="op">
      <wsdl:input message="request"/>
    </wsdl:operation>
    <wsdl:operation name="silent"/>
  </wsdl:portType>
  <wsdl:binding name="OrderBinding" type="tns:OrderPort">
    <wsdl:operation name="op"/>
    <wsdl:operation name="silent"/>
  </wsdl:binding>`);

    expect(schemas.get("op")?.request?.["application/xml"].properties).toEqual({
      x: { type: "string" },
    });
    // With neither a request nor a response schema the operation contributes no entry.
    expect(schemas.has("silent")).toBe(false);
  });

  // `XmlSchemaStore`'s three searches by local name. The fixture resolves every reference through
  // the schema's own target namespace, so a reference through a prefix bound elsewhere — common in
  // a hand-written WSDL whose schema sections and definitions disagree — never reaches them. The
  // definition is named after the namespace the reference asked for, not the one the declaration
  // lives in.
  it("resolves a reference through an unmatched namespace by local name", () => {
    const schema = requestSchema(
      operationBody(
        `    <wsdl:part name="w" type="other:Widget"/>
    <wsdl:part name="c" type="other:Color"/>
    <wsdl:part name="ignored" element="other:top"/>`,
        `  <wsdl:types>
    <xsd:schema targetNamespace="${TNS}">
      <xsd:complexType name="Widget">
        <xsd:sequence>
          <xsd:element name="n" type="xsd:string"/>
        </xsd:sequence>
      </xsd:complexType>
      <xsd:simpleType name="Color">
        <xsd:restriction base="xsd:string">
          <xsd:enumeration value="red"/>
        </xsd:restriction>
      </xsd:simpleType>
      <xsd:element name="top" type="xsd:string"/>
    </xsd:schema>
  </wsdl:types>`,
      ),
    );

    expect(schema.properties).toEqual({
      w: { $ref: "#/definitions/Type_urn_other_Widget" },
      c: { $ref: "#/definitions/Type_urn_other_Color" },
      // A part that names an element is keyed by the element's name, not the part's.
      top: { $ref: "#/definitions/Element_urn_other_top" },
    });
    expect(schema.definitions.Type_urn_other_Widget).toEqual({
      type: "object",
      properties: { n: { type: "string" } },
      required: ["n"],
    });
    expect(schema.definitions.Type_urn_other_Color).toEqual({
      type: "string",
      enum: ["red"],
    });
  });

  // Both arms of `dereferenceDefinition`, the repair that keeps a `complexContent` extension from
  // dropping the fields of a named base type. The golden fixture extends one named complex type,
  // which takes the arm that finds the base in `definitions`; a base that is an XSD builtin is not
  // a `$ref` at all, and a type that extends itself names a definition whose body is still being
  // built, so the map does not hold it yet.
  //
  // Removing either arm fails neither case: `mergeObjectSchemas` returns `extensionSchema` for a
  // `$ref` base, for a non-object base and for `undefined` alike, so the fallback and its absence
  // produce the same bytes here. These two envelopes pin the output, and the mutation that fails
  // them is the swap of that fallback to `baseSchema || extensionSchema`.
  it("keeps the extension's own fields over a builtin base and over itself", () => {
    const schema = requestSchema(
      operationBody(
        `    <wsdl:part name="tagged" type="tns:Tagged"/>
    <wsdl:part name="node" type="tns:Node"/>`,
        `  <wsdl:types>
    <xsd:schema targetNamespace="${TNS}">
      <xsd:complexType name="Tagged">
        <xsd:complexContent>
          <xsd:extension base="xsd:string">
            <xsd:sequence>
              <xsd:element name="tag" type="xsd:string"/>
            </xsd:sequence>
          </xsd:extension>
        </xsd:complexContent>
      </xsd:complexType>
      <xsd:complexType name="Node">
        <xsd:complexContent>
          <xsd:extension base="tns:Node">
            <xsd:sequence>
              <xsd:element name="label" type="xsd:string"/>
            </xsd:sequence>
          </xsd:extension>
        </xsd:complexContent>
      </xsd:complexType>
    </xsd:schema>
  </wsdl:types>`,
      ),
    );

    expect(schema.definitions).toEqual({
      Type_urn_test_Tagged: {
        type: "object",
        properties: { tag: { type: "string" } },
        required: ["tag"],
      },
      Type_urn_test_Node: {
        type: "object",
        properties: { label: { type: "string" } },
        required: ["label"],
      },
    });
  });
});
