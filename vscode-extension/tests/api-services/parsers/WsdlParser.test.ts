// Unit cases for `WsdlParser.parse` on the two documents the SOAP import fixture is not: one whose
// root element belongs to no WSDL namespace, and one written in WSDL 2.0.
//
// The guard and the version it must not catch belong together. `parse` dispatches on the root
// element's namespace, and a root in neither WSDL namespace used to collect nothing and parse into
// an empty result, which the import wrote out as a specification with no operation in it
// (`tests/web/import/failures.test.ts` pins what the user is told now). The refusal is one
// namespace comparison away from refusing WSDL 2.0 as well, and no fixture in the module is written
// in 2.0, so the second case is what keeps the guard from growing into one.
import { WsdlParser } from "../../../src/web/api-services/parsers/soap/WsdlParser";

function parse(content: string) {
  return new WsdlParser().parse([{ uri: "test.wsdl", content }], "test.wsdl");
}

describe("WsdlParser", () => {
  it("refuses a root element in no WSDL namespace", () => {
    expect(() => parse("<notDefinitions><oops/></notDefinitions>\n")).toThrow(
      "Unsupported WSDL: root element <notDefinitions> is in no WSDL namespace",
    );
  });

  it("parses a WSDL 2.0 description, which names its root element differently", () => {
    const parsed = parse(`<?xml version="1.0" encoding="UTF-8"?>
<wsdl:description xmlns:wsdl="http://www.w3.org/ns/wsdl"
                  xmlns:tns="urn:test"
                  targetNamespace="urn:test">
  <wsdl:interface name="OrderInterface">
    <wsdl:operation name="getOrder"/>
  </wsdl:interface>
  <wsdl:binding name="OrderBinding" interface="tns:OrderInterface">
    <wsdl:operation ref="tns:getOrder"/>
  </wsdl:binding>
  <wsdl:service name="OrderService" interface="tns:OrderInterface">
    <wsdl:endpoint name="default"
                   binding="tns:OrderBinding"
                   address="http://orders.qip.test/soap"/>
  </wsdl:service>
</wsdl:description>`);

    expect(parsed.version).toBe("2.0");
    expect(parsed.targetNamespace).toBe("urn:test");
    // The operation comes from the binding, as in 1.1, but by `ref` rather than by `name`.
    expect(parsed.operations).toEqual(["getOrder"]);
    expect(parsed.endpoints).toEqual([
      {
        serviceName: "OrderService",
        endpointName: "default",
        address: "http://orders.qip.test/soap",
      },
    ]);
  });
});
