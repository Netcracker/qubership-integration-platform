package experimentruntime

import (
	"strings"
	"testing"
)

// Fixtures follow the Codex non-interactive documentation and OpenCode 2.2.5's
// embedded run renderer. They have not been validated against a live model run.
func TestCodexEvents(t *testing.T) {
	input := `{"type":"thread.started","thread_id":"example"}
{"type":"turn.started"}
{"type":"item.completed","item":{"id":"item_3","type":"agent_message","text":"Done."}}
{"type":"turn.completed","usage":{"input_tokens":24763,"cached_input_tokens":24448,"output_tokens":122,"reasoning_output_tokens":0}}
`
	r, err := ParseEvents(strings.NewReader(input), "codex")
	if err != nil || r.Outcome != "runtime_completed_unverified" || r.Usage == nil || *r.Usage.Input != 24763 {
		t.Fatalf("%+v %v", r, err)
	}
	for _, suffix := range []string{"{broken}\n", `{"type":"new.event"}` + "\n"} {
		r, err = ParseEvents(strings.NewReader(input+suffix), "codex")
		if err != nil || r.Outcome != "unverified" {
			t.Fatalf("%+v %v", r, err)
		}
	}
	r, err = ParseEvents(strings.NewReader(`{"type":"turn.completed"}`), "codex")
	if err != nil || r.Outcome != "unverified" || r.Usage != nil {
		t.Fatalf("%+v %v", r, err)
	}
}

func TestOpenCodeStepIsNotExecutionTerminal(t *testing.T) {
	input := `{"type":"text","timestamp":123,"sessionID":"example","part":{"type":"text","text":"Done."}}
{"type":"step_finish","timestamp":124,"sessionID":"example","part":{"type":"step-finish","reason":"stop","tokens":{"input":10,"output":2}}}
`
	r, err := ParseEvents(strings.NewReader(input), "opencode")
	if err != nil || r.TerminalObserved || r.Outcome != "unverified" || len(r.StepUsage) != 1 || r.Usage != nil {
		t.Fatalf("%+v %v", r, err)
	}
	if r.CompactionObservability != "unavailable_from_cli_events" || r.ContextOccupancyObservability != "unavailable_from_cli_events" {
		t.Fatal("step token usage must not imply measured context occupancy or a compaction count")
	}
	if r.CompactionCount != nil || r.PeakContextTokens != nil {
		t.Fatal("missing measurements must remain null")
	}
	r, err = ParseEvents(strings.NewReader(input+`{"type":"error","timestamp":125,"sessionID":"example","error":{"type":"aborted","message":"interrupted"}}`), "opencode")
	if err != nil || !r.HasError || r.Outcome != "runtime_error" {
		t.Fatalf("%+v %v", r, err)
	}
}

func TestOpenCodeBackgroundWaitDoesNotCompleteTask(t *testing.T) {
	r, err := ParseEvents(strings.NewReader("{\"type\":\"background_wait\",\"sessionID\":\"example\",\"timestamp\":123}\n{\"type\":\"retry\",\"sessionID\":\"example\",\"timestamp\":124}\n"), "opencode")
	if err != nil || len(r.UnknownTypes) != 0 || r.TerminalObserved || r.FinalObserved || r.HasError || r.Outcome != "unverified" {
		t.Fatalf("%+v %v", r, err)
	}
}

func TestCodexReconnectRecoveryRequiresFinalCompletion(t *testing.T) {
	reconnect := "{\"type\":\"error\",\"message\":\"Reconnecting... 2/5 (request timed out)\"}\n"
	final := "{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":\"{}\"}}\n"
	complete := "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":12,\"output_tokens\":3}}\n"
	cases := map[string]struct {
		input    string
		accepted bool
	}{
		"recovered recorded timeout": {reconnect + final + complete, true},
		"no completion":              {reconnect + final, false},
		"no final message":           {reconnect + complete, false},
		"terminal failure":           {reconnect + final + "{\"type\":\"turn.failed\"}\n", false},
		"unrelated error":            {"{\"type\":\"error\",\"message\":\"authentication failed\"}\n" + final + complete, false},
		"malformed stream":           {reconnect + "{broken}\n" + final + complete, false},
		"stale completion":           {complete + reconnect + final, false},
		"retry after completion":     {final + complete + reconnect, false},
		"stale final":                {final + reconnect + complete, false},
		"new unfinished turn":        {final + complete + "{\"type\":\"turn.started\"}\n", false},
	}
	for name, tc := range cases {
		t.Run(name, func(t *testing.T) {
			r, err := ParseEvents(strings.NewReader(tc.input), "codex")
			if err != nil {
				t.Fatal(err)
			}
			if (r.Outcome == "runtime_completed_unverified") != tc.accepted {
				t.Fatalf("unexpected completion: %+v", r)
			}
		})
	}
}
