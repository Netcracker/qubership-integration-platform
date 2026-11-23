package fixer

import "testing"

func TestStateValidationRejectsBrokenReferences(t *testing.T) {
	original, _ := initial(t)
	cases := map[string]func(*State){
		"effect execution":                 func(s *State) { s.Effects[0].ExecutionID = "missing" },
		"future generation":                func(s *State) { s.Executions[0].Generation++ },
		"dispatch provenance":              func(s *State) { s.Effects[0].Payload.TrustedSHA = s.Contract.BaseSHA },
		"missing requirements":             func(s *State) { s.Contract.RequirementsSHA256 = "" },
		"invalid requirements hash":        func(s *State) { s.Contract.RequirementsSHA256 = "invalid" },
		"two requirements representations": func(s *State) { s.Contract.Requirements = "Legacy requirements" },
		"duplicate receipt":                func(s *State) { s.Receipts = append(s.Receipts, s.Receipts[0]) },
		"unknown stop reason": func(s *State) {
			question(s, "q", "unknown", "Choose behavior.", "An allowed user answers.", "decision")
		},
		"orphan stop episode": func(s *State) {
			s.StopEpisodes = append(s.StopEpisodes, StopEpisode{ID: "stop", DecisionID: "missing", Reason: "invalid", Status: "open"})
		},
	}
	for name, mutate := range cases {
		t.Run(name, func(t *testing.T) {
			s := cloneState(original)
			mutate(s)
			if ValidateState(s) == nil {
				t.Fatal("invalid state accepted")
			}
		})
	}
}
