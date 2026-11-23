package main

import (
	"encoding/json"
	"math"
	"path/filepath"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentcost"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentusage"
)

func continuedTokenEvent(total, last int64) string {
	tokens := func(input int64) map[string]int64 {
		return map[string]int64{"input_tokens": input, "cached_input_tokens": input * 4 / 10, "cache_write_input_tokens": 0, "output_tokens": input / 10, "reasoning_output_tokens": input * 3 / 100}
	}
	raw, _ := json.Marshal(map[string]any{"type": "event_msg", "payload": map[string]any{"type": "token_count", "info": map[string]any{"total_token_usage": tokens(total), "last_token_usage": tokens(last)}}})
	return string(raw) + "\n"
}

func TestContinuedAuthorCostsCountNewRequestsAndPreserveKnownStages(t *testing.T) {
	for _, state := range []string{"complete", "missing-baseline", "disagreeing-prefix"} {
		t.Run(state, func(t *testing.T) {
			root := fixture(t)
			before := process(t, root, "step-0001-author", true, 0)
			after := process(t, root, "step-0003-author", true, 0)
			prefix := "{\"type\":\"session_meta\",\"payload\":{\"id\":\"author\"}}\n{\"type\":\"turn_context\",\"payload\":{\"model\":\"gpt-6.1-terra\"}}\n"
			first := prefix + continuedTokenEvent(100, 100) + continuedTokenEvent(200, 100)
			writeBytes(t, filepath.Join(before, "home/.codex/sessions/author.jsonl"), []byte(first))
			current := first + continuedTokenEvent(200, 100) + continuedTokenEvent(300, 100)
			baseline := before
			if state == "missing-baseline" {
				baseline = filepath.Join(root, "step-0002-author")
			}
			if state == "disagreeing-prefix" {
				current = prefix + continuedTokenEvent(110, 110) + continuedTokenEvent(210, 100) + continuedTokenEvent(310, 100)
			}
			writeBytes(t, filepath.Join(after, "home/.codex/sessions/author.jsonl"), []byte(current))
			writeJSON(t, filepath.Join(after, "session.json"), experimentusage.SessionLink{SessionID: "author", Continuation: true, PreviousArchive: baseline})
			m, err := collect(root, nil)
			if err != nil {
				t.Fatal(err)
			}
			wantRequests, wantCost, wantUnknown := 2, .000224, 1
			if state == "complete" {
				wantRequests, wantCost, wantUnknown = 3, .000336, 0
				if m.APIEquivalentUSD == nil || math.Abs(*m.APIEquivalentUSD-wantCost) > 1e-12 {
					t.Fatalf("complete cost=%+v", m)
				}
			} else if m.APIEquivalentUSD != nil || m.CombinedUSD != nil {
				t.Fatalf("unknown continuation became a completed cost: %+v", m)
			}
			if m.Counts.ObservedRequests != wantRequests || m.Counts.UnknownUsage != wantUnknown || math.Abs(m.KnownCostUSD-wantCost) > 1e-12 {
				t.Fatalf("counts=%+v known cost=%g", m.Counts, m.KnownCostUSD)
			}
			if state == "complete" {
				costs := m.Stages[1]["api_equivalent"].([]experimentcost.Estimate)
				if len(costs) != 1 || costs[0].TotalUSD == nil || math.Abs(*costs[0].TotalUSD-.000112) > 1e-12 {
					t.Fatalf("continued stage cost=%+v", costs)
				}
			}
		})
	}
}
