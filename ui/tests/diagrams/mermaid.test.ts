import { describe, it, expect } from "@jest/globals";
import { exportAsMermaid } from "../../src/diagrams/mermaid.ts";
import {
  SequenceDiagram,
  Action,
  Participant,
} from "../../src/diagrams/model.ts";

function makeDiagram(
  overrides: Partial<SequenceDiagram> = {},
): SequenceDiagram {
  return {
    autonumber: true,
    chainParticipantId: "chain-1",
    participants: [],
    actions: [],
    ...overrides,
  };
}

function p(id: string, name?: string): Participant {
  return { id, name };
}

describe("exportAsMermaid", () => {
  it("should output sequenceDiagram header", () => {
    const result = exportAsMermaid(makeDiagram());
    expect(result).toContain("sequenceDiagram;");
  });

  it("should include autonumber when enabled", () => {
    const result = exportAsMermaid(makeDiagram({ autonumber: true }));
    expect(result).toContain("autonumber;");
  });

  it("should omit autonumber when disabled", () => {
    const result = exportAsMermaid(makeDiagram({ autonumber: false }));
    expect(result).not.toContain("autonumber;");
  });

  it("should export participants with names", () => {
    const result = exportAsMermaid(
      makeDiagram({
        participants: [p("p1", "Service A"), p("p2", "Service B")],
      }),
    );
    expect(result).toContain("participant p1 as Service A;");
    expect(result).toContain("participant p2 as Service B;");
  });

  it("should use id as name when name is not provided", () => {
    const result = exportAsMermaid(makeDiagram({ participants: [p("p1")] }));
    expect(result).toContain("participant p1 as p1;");
  });

  it("should escape special characters in participant names", () => {
    const result = exportAsMermaid(
      makeDiagram({
        participants: [p("p1", "Service: A <B>")],
      }),
    );
    // : < > should be escaped as #charCode;
    expect(result).toContain("#58;"); // :
    expect(result).toContain("#60;"); // <
    expect(result).toContain("#62;"); // >
  });

  it("should export a simple message action", () => {
    const actions: Action[] = [
      {
        type: "message",
        fromId: "A",
        toId: "B",
        arrowType: "arrow-solid",
        message: "hello",
      },
    ];
    const result = exportAsMermaid(makeDiagram({ actions }));
    expect(result).toContain("A ->> B : hello;");
  });

  it("should export dotted arrow message", () => {
    const actions: Action[] = [
      {
        type: "message",
        fromId: "A",
        toId: "B",
        arrowType: "arrow-dotted",
        message: "response",
      },
    ];
    const result = exportAsMermaid(makeDiagram({ actions }));
    expect(result).toContain("A -->> B : response;");
  });

  it("should export open-arrow-solid message", () => {
    const actions: Action[] = [
      {
        type: "message",
        fromId: "A",
        toId: "B",
        arrowType: "open-arrow-solid",
        message: "async",
      },
    ];
    const result = exportAsMermaid(makeDiagram({ actions }));
    expect(result).toContain("A -) B : async;");
  });

  it("should export open-arrow-dotted message", () => {
    const actions: Action[] = [
      {
        type: "message",
        fromId: "A",
        toId: "B",
        arrowType: "open-arrow-dotted",
        message: "async reply",
      },
    ];
    const result = exportAsMermaid(makeDiagram({ actions }));
    expect(result).toContain("A --) B : async reply;");
  });

  it("should export message without text", () => {
    const actions: Action[] = [
      {
        type: "message",
        fromId: "A",
        toId: "B",
        arrowType: "arrow-solid",
      },
    ];
    const result = exportAsMermaid(makeDiagram({ actions }));
    expect(result).toContain("A ->> B;");
  });

  it("should export activate/deactivate actions", () => {
    const actions: Action[] = [
      { type: "activate", participantId: "A" },
      { type: "deactivate", participantId: "A" },
    ];
    const result = exportAsMermaid(makeDiagram({ actions }));
    expect(result).toContain("activate A;");
    expect(result).toContain("deactivate A;");
  });

  it("should export loop action", () => {
    const actions: Action[] = [
      {
        type: "loop",
        label: "3 times",
        actions: [
          {
            type: "message",
            fromId: "A",
            toId: "B",
            arrowType: "arrow-solid",
            message: "ping",
          },
        ],
      },
    ];
    const result = exportAsMermaid(makeDiagram({ actions }));
    expect(result).toContain("loop 3 times;");
    expect(result).toContain("A ->> B : ping;");
    expect(result).toContain("end;");
  });

  it("should export alternatives action", () => {
    const actions: Action[] = [
      {
        type: "alternatives",
        branches: [
          {
            type: "branch",
            label: "condition A",
            actions: [
              {
                type: "message",
                fromId: "A",
                toId: "B",
                arrowType: "arrow-solid",
                message: "case A",
              },
            ],
          },
          {
            type: "branch",
            label: "condition B",
            actions: [
              {
                type: "message",
                fromId: "A",
                toId: "B",
                arrowType: "arrow-solid",
                message: "case B",
              },
            ],
          },
        ],
      },
    ];
    const result = exportAsMermaid(makeDiagram({ actions }));
    expect(result).toContain("alt condition A;");
    expect(result).toContain("else condition B;");
    expect(result).toContain("end;");
  });

  it("should export parallel action", () => {
    const actions: Action[] = [
      {
        type: "parallel",
        branches: [
          {
            type: "branch",
            label: "branch 1",
            actions: [
              {
                type: "message",
                fromId: "A",
                toId: "B",
                arrowType: "arrow-solid",
                message: "msg1",
              },
            ],
          },
          {
            type: "branch",
            label: "branch 2",
            actions: [
              {
                type: "message",
                fromId: "A",
                toId: "C",
                arrowType: "arrow-solid",
                message: "msg2",
              },
            ],
          },
        ],
      },
    ];
    const result = exportAsMermaid(makeDiagram({ actions }));
    expect(result).toContain("par branch 1;");
    expect(result).toContain("and branch 2;");
    expect(result).toContain("end;");
  });

  it("should export empty alternatives as nothing", () => {
    const actions: Action[] = [{ type: "alternatives", branches: [] }];
    const result = exportAsMermaid(makeDiagram({ actions }));
    expect(result).not.toContain("alt");
    expect(result).not.toContain("end;");
  });

  it("should export optional action", () => {
    const actions: Action[] = [
      {
        type: "optional",
        label: "if available",
        actions: [
          {
            type: "message",
            fromId: "A",
            toId: "B",
            arrowType: "arrow-solid",
            message: "optional msg",
          },
        ],
      },
    ];
    const result = exportAsMermaid(makeDiagram({ actions }));
    expect(result).toContain("opt if available;");
    expect(result).toContain("end;");
  });

  it("should export group action as rect with note", () => {
    const actions: Action[] = [
      {
        type: "group",
        label: "My Group",
        actions: [
          {
            type: "message",
            fromId: "A",
            toId: "B",
            arrowType: "arrow-solid",
            message: "grouped",
          },
        ],
      },
    ];
    const result = exportAsMermaid(
      makeDiagram({ chainParticipantId: "chain-1", actions }),
    );
    expect(result).toContain("rect rgb(250, 250, 250);");
    expect(result).toContain("note right of chain#45;1 : My Group;");
    expect(result).toContain("end;");
  });

  it("should escape newlines in messages as <br/>", () => {
    const actions: Action[] = [
      {
        type: "message",
        fromId: "A",
        toId: "B",
        arrowType: "arrow-solid",
        message: "line1\nline2",
      },
    ];
    const result = exportAsMermaid(makeDiagram({ actions }));
    expect(result).toContain("line1<br/>line2");
  });

  it("should escape comma and hash in messages", () => {
    const actions: Action[] = [
      {
        type: "message",
        fromId: "A",
        toId: "B",
        arrowType: "arrow-solid",
        message: "a,b#c",
      },
    ];
    const result = exportAsMermaid(makeDiagram({ actions }));
    expect(result).toContain("#44;"); // ,
    expect(result).toContain("#35;"); // #
  });

  it("should handle nested actions (loop inside alternatives)", () => {
    const actions: Action[] = [
      {
        type: "alternatives",
        branches: [
          {
            type: "branch",
            label: "case 1",
            actions: [
              {
                type: "loop",
                label: "repeat",
                actions: [
                  {
                    type: "message",
                    fromId: "A",
                    toId: "B",
                    arrowType: "arrow-solid",
                    message: "nested",
                  },
                ],
              },
            ],
          },
        ],
      },
    ];
    const result = exportAsMermaid(makeDiagram({ actions }));
    expect(result).toContain("alt case 1;");
    expect(result).toContain("loop repeat;");
    expect(result).toContain("A ->> B : nested;");
    // two end; — one for loop, one for alt
    const endCount = (result.match(/^end;$/gm) ?? []).length;
    expect(endCount).toBe(2);
  });

  it("should handle percent sign in text", () => {
    const result = exportAsMermaid(
      makeDiagram({
        participants: [p("p1", "50% done")],
      }),
    );
    expect(result).toContain("#37;"); // %
  });
});
