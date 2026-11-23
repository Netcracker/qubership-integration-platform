package experimentruntime

import (
	"strings"
	"testing"
)

func TestFinalTextCodexUsesLastCompletedAssistantMessage(t *testing.T) {
	raw := `{"type":"item.completed","item":{"type":"agent_message","text":"earlier narration"}}
{"type":"item.started","item":{"type":"agent_message","text":"unfinished"}}
{"type":"item.completed","item":{"type":"command_execution","aggregated_output":"tool output"}}
{"type":"item.completed","item":{"type":"agent_message","text":"Final report\nwith evidence."}}
{"type":"turn.completed"}
`
	text, err := FinalText(strings.NewReader(raw), "codex")
	if err != nil || text != "Final report\nwith evidence." {
		t.Fatalf("%q %v", text, err)
	}
}

func TestFinalTextOpenCodePreservesLastMessageFragments(t *testing.T) {
	raw := `{"type":"text","part":{"type":"text","messageID":"narration","text":"I'll load the skill first."}}
{"type":"tool_use","part":{"type":"tool","messageID":"narration","state":{"output":"not a final report"}}}
{"type":"text","part":{"type":"text","messageID":"final","text":"Final "}}
{"type":"text","part":{"type":"text","messageID":"final","text":"report\n"}}
{"type":"step_finish","part":{"type":"step-finish","messageID":"final","reason":"stop"}}
`
	text, err := FinalText(strings.NewReader(raw), "opencode")
	if err != nil || text != "Final report\n" {
		t.Fatalf("%q %v", text, err)
	}
}

func TestFinalTextRejectsMissingOrAmbiguousText(t *testing.T) {
	for _, tc := range []struct{ runtime, raw string }{
		{"unknown", `{"type":"text"}`},
		{"codex", `{"type":"item.started","item":{"type":"agent_message","text":"unfinished"}}`},
		{"codex", `{"type":"item.completed","item":{"type":"command_execution","aggregated_output":"tool echo"}}`},
		{"opencode", `{"type":"text","part":{"type":"text","text":"missing message ID"}}`},
		{"opencode", `{"type":"new_schema","text":"unsupported schema"}`},
		{"opencode", `{broken`},
	} {
		if text, err := FinalText(strings.NewReader(tc.raw), tc.runtime); err == nil || text != "" {
			t.Fatalf("accepted %s: %q %v", tc.raw, text, err)
		}
	}
}
