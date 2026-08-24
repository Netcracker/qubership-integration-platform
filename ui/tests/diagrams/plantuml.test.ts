import { describe, it, expect } from "@jest/globals";
import { exportAsPlantUml } from "../../src/diagrams/plantuml.ts";
import {
  Action,
  ArrowType,
  Participant,
  SequenceDiagram,
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

function lines(source: string): string[] {
  return source.split("\n");
}

describe("exportAsPlantUml", () => {
  it("should wrap the diagram in start and end markers", () => {
    const result = exportAsPlantUml(makeDiagram());
    expect(result.startsWith("@startuml")).toBe(true);
    expect(result.endsWith("@enduml")).toBe(true);
  });

  it("should include autonumber when enabled", () => {
    expect(exportAsPlantUml(makeDiagram({ autonumber: true }))).toContain(
      "autonumber",
    );
  });

  it("should omit autonumber when disabled", () => {
    expect(exportAsPlantUml(makeDiagram({ autonumber: false }))).not.toContain(
      "autonumber",
    );
  });

  it("should export title, header and footer when provided", () => {
    const result = exportAsPlantUml(
      makeDiagram({ title: "Chain", header: "Head", footer: "Foot" }),
    );
    expect(result).toContain('title "Chain"');
    expect(result).toContain('header "Head"');
    expect(result).toContain('footer "Foot"');
  });

  it("should turn a participant id with spaces into a single token", () => {
    const result = exportAsPlantUml(
      makeDiagram({ participants: [p("Service: A B", "Service: A B")] }),
    );
    expect(result).toContain(
      'participant "Service: A B" as Service_58__32_A_32_B',
    );
  });

  it("should use the same identifier in declarations, messages and activations", () => {
    const id = "SFTP server: sftp://user@host:22/out";
    const result = exportAsPlantUml(
      makeDiagram({
        participants: [p(id)],
        actions: [
          { type: "activate", participantId: id },
          { type: "deactivate", participantId: id },
        ],
      }),
    );
    const declared = /^participant .* as (\S+)$/m.exec(result)?.[1];
    expect(declared).toBeDefined();
    expect(declared).toMatch(/^[0-9a-zA-Z_]+$/);
    expect(result).toContain(`activate ${declared}`);
    expect(result).toContain(`deactivate ${declared}`);
  });

  it("should fall back to the id when the participant has no name", () => {
    const result = exportAsPlantUml(makeDiagram({ participants: [p("p1")] }));
    expect(result).toContain('participant "p1" as p1');
  });

  const arrows: [ArrowType, string][] = [
    ["arrow-solid", "->"],
    ["arrow-dotted", "-->"],
    ["open-arrow-solid", "->>"],
    ["open-arrow-dotted", "-->>"],
  ];

  it.each(arrows)(
    "should export %s message with the %s arrow",
    (arrowType, arrow) => {
      const actions: Action[] = [
        {
          type: "message",
          fromId: "A",
          toId: "B",
          arrowType,
          message: "hello",
        },
      ];
      expect(exportAsPlantUml(makeDiagram({ actions }))).toContain(
        `A ${arrow} B : "hello"`,
      );
    },
  );

  it("should export message without text", () => {
    const actions: Action[] = [
      { type: "message", fromId: "A", toId: "B", arrowType: "arrow-solid" },
    ];
    const result = exportAsPlantUml(makeDiagram({ actions }));
    expect(result).toContain("A -> B");
    expect(result).not.toContain(" : ");
  });

  it("should escape quotes and newlines in messages", () => {
    const actions: Action[] = [
      {
        type: "message",
        fromId: "A",
        toId: "B",
        arrowType: "arrow-solid",
        message: 'say "hi"\nagain',
      },
    ];
    const result = exportAsPlantUml(makeDiagram({ actions }));
    expect(result).toContain("<U+0022>hi<U+0022>");
    expect(result).toContain("\\n");
  });

  it("should export loop, group and optional blocks", () => {
    const message: Action = {
      type: "message",
      fromId: "A",
      toId: "B",
      arrowType: "arrow-solid",
      message: "in block",
    };
    const actions: Action[] = [
      { type: "loop", label: "3 times", actions: [message] },
      { type: "group", label: "My Group", actions: [message] },
      { type: "optional", label: "if available", actions: [message] },
    ];
    const result = exportAsPlantUml(makeDiagram({ actions }));
    expect(result).toContain("loop 3 times");
    expect(result).toContain("group My Group");
    expect(result).toContain("opt if available");
    expect(lines(result).filter((line) => line === "end").length).toBe(3);
  });

  it("should export alternatives as alt and else branches", () => {
    const actions: Action[] = [
      {
        type: "alternatives",
        branches: [
          { type: "branch", label: "case A", actions: [] },
          { type: "branch", label: "case B", actions: [] },
        ],
      },
    ];
    const result = exportAsPlantUml(makeDiagram({ actions }));
    expect(result).toContain('alt "case A"');
    expect(result).toContain('else "case B"');
  });

  it("should export parallel branches as par", () => {
    const actions: Action[] = [
      {
        type: "parallel",
        branches: [
          { type: "branch", label: "first", actions: [] },
          { type: "branch", label: "second", actions: [] },
        ],
      },
    ];
    const result = exportAsPlantUml(makeDiagram({ actions }));
    expect(result).toContain('par "first"');
    expect(result).toContain('else "second"');
  });

  it("should skip a multi branch block without branches", () => {
    const actions: Action[] = [{ type: "alternatives", branches: [] }];
    const result = exportAsPlantUml(makeDiagram({ actions }));
    expect(result).not.toContain("alt");
    expect(lines(result).filter((line) => line === "end").length).toBe(0);
  });
});
