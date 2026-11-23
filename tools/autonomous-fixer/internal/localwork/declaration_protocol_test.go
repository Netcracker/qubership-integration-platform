package localwork

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func declarationProtocolFixture(t *testing.T) (*Driver, workcycle.Invocation, string, string, workcycle.Response) {
	t.Helper()
	cfg := fixture(t)
	binary, _ := os.Executable()
	script := "#!/bin/sh\nif [ \"$1\" = '--version' ]; then echo 'fixture runtime'; exit 0; fi\nexec '" + strings.ReplaceAll(binary, "'", "'\\''") + "' -test.run=TestDeclarationCorrectionRuntimeHelper\n"
	if err := os.WriteFile(cfg.RuntimeBinary, []byte(script), 0700); err != nil {
		t.Fatal(err)
	}
	cfg.ProductCheckBinary = cfg.RuntimeBinary
	d, err := Prepare(context.Background(), cfg)
	if err != nil {
		t.Fatal(err)
	}
	step := filepath.Join(cfg.OutputDirectory, "step-0001-reproduce")
	evidence := filepath.Join(d.evidence, filepath.Base(step))
	for _, dir := range []string{step, evidence} {
		if err := os.Mkdir(dir, 0700); err != nil {
			t.Fatal(err)
		}
	}
	proof := productCapture(t, evidence, "negative")
	catalog, err := BuildObservationCatalog(evidence)
	if err != nil || len(catalog.Observations) != 1 {
		t.Fatal(catalog, err)
	}
	d.Config.Environment["QIP_DECLARATION_OBSERVATION"] = catalog.Observations[0].ID
	snap, err := d.Snapshot(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	inv := workcycle.Invocation{Request: d.Request, Stage: workcycle.ReproduceStage, Candidate: snap, Contract: &workcycle.Contract{Scenarios: []workcycle.Scenario{{ID: "literal-classic", CriterionID: "literal", Consumer: "classic", Behavior: "preserve literal", Procedure: "run actual request", Baseline: "fail"}}}}
	response := workcycle.Response{CandidateID: snap.ID, EnvironmentID: "same-env", DataID: "data-A", Evidence: []string{proof}, Scenarios: []workcycle.Result{{ID: "literal-classic", Outcome: "pass", Evidence: []string{proof}, ProductTests: []string{"mistyped test"}}}}
	if err := writeJSON(filepath.Join(step, "response.json"), response); err != nil {
		t.Fatal(err)
	}
	return d, inv, step, evidence, response
}

func TestDeclarationCorrectionRuntimeHelper(t *testing.T) {
	if os.Getenv("QIP_WORK_TEST_HELPER") != "1" {
		return
	}
	prompt, _ := io.ReadAll(os.Stdin)
	if !strings.Contains(string(prompt), "Declaration binding correction") {
		runDeclarationEngineeringStage(t, string(prompt))
		os.Exit(21)
	}
	if strings.Contains(string(prompt), "Project conventions.") || strings.Contains(string(prompt), "Task attachment directory") {
		os.Exit(22)
	}
	observationIDs := []string{os.Getenv("QIP_DECLARATION_OBSERVATION")}
	var metadata struct {
		Catalog ObservationCatalog `json:"catalog"`
		Checks  []workcycle.Result `json:"checks"`
		Allowed map[string]bool    `json:"allowed_bindings"`
	}
	start := strings.Index(string(prompt), "\n\n{")
	if start < 0 || json.Unmarshal(prompt[start+2:], &metadata) != nil {
		os.Exit(23)
	}
	if observationIDs[0] == "" {
		observationIDs = nil
		for _, observation := range metadata.Catalog.Observations {
			observationIDs = append(observationIDs, observation.ID)
		}
	}
	if len(observationIDs) == 0 {
		os.Exit(23)
	}
	bindings := []any{map[string]any{"kind": "scenario", "id": "literal-classic", "observation_ids": observationIDs}}
	for _, check := range metadata.Checks {
		if !metadata.Allowed["check:"+check.ID] {
			continue
		}
		bindings = append(bindings, map[string]any{"kind": "check", "id": check.ID, "observation_ids": observationIDs})
	}
	patch := map[string]any{"bindings": bindings, "metadata_references": []any{}}
	mode := os.Getenv("QIP_DECLARATION_MODE")
	switch mode {
	case "unknown":
		patch["bindings"] = []any{map[string]any{"kind": "scenario", "id": "literal-classic", "observation_ids": []string{"invented"}}}
	case "drop":
		patch["bindings"] = []any{map[string]any{"kind": "scenario", "id": "another", "observation_ids": []string{os.Getenv("QIP_DECLARATION_OBSERVATION")}}}
	case "outcome":
		patch["outcome"] = "pass"
	case "results":
		patch["scenarios"] = []any{}
	case "empty":
		patch["bindings"] = []any{}
	case "hijack":
		patch["bindings"] = append(bindings, map[string]any{"kind": "check", "id": "verified-failure", "observation_ids": []string{os.Getenv("QIP_DECLARATION_PASS_OBSERVATION")}})
	case "metadata":
		patch["metadata_references"] = []any{map[string]string{"before": os.Getenv("QIP_DECLARATION_ORIGINAL_PROOF"), "after": os.Getenv("QIP_DECLARATION_OTHER_PROOF")}}
	case "missing_array":
		delete(patch, "metadata_references")
	case "null_array":
		patch["metadata_references"] = nil
	case "source":
		_ = os.WriteFile(os.Getenv("QIP_DECLARATION_SOURCE"), []byte("mutated source"), 0600)
	case "declaration":
		_ = os.WriteFile(os.Getenv("QIP_DECLARATION_ORIGINAL"), []byte("changed declaration"), 0600)
	case "proof":
		_ = os.WriteFile(os.Getenv("QIP_DECLARATION_PROOF"), []byte("mutated proof"), 0600)
	case "tool":
		fmt.Println(`{"type":"item.completed","item":{"type":"command_execution","command":"echo forbidden"}}`)
	}
	text, _ := json.Marshal(patch)
	_ = json.NewEncoder(os.Stdout).Encode(map[string]any{"type": "item.completed", "item": map[string]string{"type": "agent_message", "text": string(text)}})
	fmt.Println(`{"type":"turn.completed","usage":{"input_tokens":3,"output_tokens":4}}`)
	os.Exit(0)
}

func TestDeclarationCorrectionUsesExistingMeasurementOnce(t *testing.T) {
	d, inv, step, evidence, response := declarationProtocolFixture(t)
	original, _ := os.ReadFile(filepath.Join(step, "response.json"))
	corrected, err := d.resolveDeclarationBindings(context.Background(), inv, response, evidence, step)
	if err != nil {
		t.Fatal(err)
	}
	if corrected.Scenarios[0].Outcome != "fail" || len(corrected.Scenarios[0].ObservationIDs) != 1 {
		t.Fatal(corrected)
	}
	if err := d.validateProductResults(corrected, evidence, inv.Stage); err != nil {
		t.Fatal(err)
	}
	saved, _ := os.ReadFile(filepath.Join(step, "response.json"))
	if string(saved) != string(original) {
		t.Fatal("original declaration overwritten")
	}
	for _, name := range []string{"profile.json", "process.json", "runtime-events.json", "response.json", "response-schema.json", "input-inventory.json", "output-inventory.json"} {
		if _, err := os.Stat(filepath.Join(step, "declaration-correction", name)); err != nil {
			t.Fatal(name, err)
		}
	}
	runs, _ := filepath.Glob(filepath.Join(step, "declaration-correction*"))
	if len(runs) != 1 {
		t.Fatal(runs)
	}
	command, err := os.ReadFile(filepath.Join(step, "declaration-correction", "command.json"))
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(command), "danger-full-access") || !strings.Contains(string(command), "read-only") || !strings.Contains(string(command), "shell_tool") || !strings.Contains(string(command), "--output-schema") || !strings.Contains(string(command), filepath.Join(step, "declaration-correction", "metadata")) {
		t.Fatal(string(command))
	}
	var recordedCommand struct{ Args []string }
	if err := json.Unmarshal(command, &recordedCommand); err != nil {
		t.Fatal(err)
	}
	for _, feature := range []string{"shell_tool", "unified_exec", "multi_agent", "multi_agent_v2"} {
		disabled := false
		for i := 1; i < len(recordedCommand.Args); i++ {
			if recordedCommand.Args[i] == feature && recordedCommand.Args[i-1] == "--disable" {
				disabled = true
			}
		}
		if !disabled {
			t.Fatal("metadata correction capability remains enabled", feature)
		}
	}
	if d.step != 0 {
		t.Fatal("correction incremented engineering stage")
	}
}

func TestDeclarationCorrectionRejectsUnboundedChanges(t *testing.T) {
	for _, mode := range []string{"unknown", "drop", "outcome", "results", "empty", "missing_array", "null_array", "source", "proof", "declaration", "tool"} {
		t.Run(mode, func(t *testing.T) {
			d, inv, step, evidence, response := declarationProtocolFixture(t)
			d.Config.Environment["QIP_DECLARATION_MODE"] = mode
			d.Config.Environment["QIP_DECLARATION_SOURCE"] = filepath.Join(d.Config.Workspace, "engine/code.txt")
			d.Config.Environment["QIP_DECLARATION_PROOF"] = response.Evidence[0]
			d.Config.Environment["QIP_DECLARATION_ORIGINAL"] = filepath.Join(step, "response.json")
			if _, err := d.resolveDeclarationBindings(context.Background(), inv, response, evidence, step); err == nil {
				t.Fatal("invalid correction accepted")
			}
			calls, _ := filepath.Glob(filepath.Join(step, "declaration-correction*"))
			if len(calls) != 1 {
				t.Fatal(calls)
			}
		})
	}
}

func TestDeclarationCorrectionDoesNotGuessMissingMeasurement(t *testing.T) {
	d, inv, step, evidence, response := declarationProtocolFixture(t)
	if err := os.Remove(filepath.Join(filepath.Dir(response.Evidence[0]), "captured-test-report.json")); err != nil {
		t.Fatal(err)
	}
	if _, err := d.resolveDeclarationBindings(context.Background(), inv, response, evidence, step); err == nil {
		t.Fatal("missing measurement accepted")
	}
	if _, err := os.Stat(filepath.Join(step, "declaration-correction")); !os.IsNotExist(err) {
		t.Fatal("missing proof prompted correction", err)
	}
}

func TestDriverCorrectionAdvancesWithoutRepeatingMeasuredStage(t *testing.T) {
	cfg := fixture(t)
	binary, _ := os.Executable()
	script := "#!/bin/sh\nif [ \"$1\" = '--version' ]; then echo 'fixture runtime'; exit 0; fi\nexec '" + strings.ReplaceAll(binary, "'", "'\\''") + "' -test.run=TestDeclarationCorrectionRuntimeHelper\n"
	if err := os.WriteFile(cfg.RuntimeBinary, []byte(script), 0700); err != nil {
		t.Fatal(err)
	}
	cfg.ProductCheckBinary = cfg.RuntimeBinary
	cfg.Environment["QIP_DECLARATION_PRODUCT_TRACE"] = filepath.Join(filepath.Dir(cfg.Workspace), "product-trace")
	report, err := Execute(context.Background(), cfg)
	if err != nil || report.Status != "verified" {
		t.Fatal(report.Status, err)
	}
	trace, err := os.ReadFile(cfg.Environment["QIP_DECLARATION_PRODUCT_TRACE"])
	if err != nil || string(trace) != "reproduce\nverify\n" {
		t.Fatal(string(trace), err)
	}
	steps, _ := filepath.Glob(filepath.Join(cfg.OutputDirectory, "step-*"))
	if len(steps) != 5 {
		t.Fatal(steps)
	}
	corrections, _ := filepath.Glob(filepath.Join(cfg.OutputDirectory, "step-*", "declaration-correction"))
	if len(corrections) != 1 {
		t.Fatal(corrections)
	}
	if report.Observations[1].Declaration.Scenarios[0].Outcome != "fail" {
		t.Fatal("baseline pass claim overrode measured failure")
	}
	data, err := os.ReadFile(filepath.Join(cfg.OutputDirectory, "evidence", "step-0002-reproduce", "controller-response-resolution.json"))
	if err != nil {
		t.Fatal(err)
	}
	var resolution struct {
		OriginalBoundSHA256 string             `json:"original_bound_sha256"`
		EffectiveResponse   workcycle.Response `json:"effective_response"`
		ArchiveHashes       map[string]string  `json:"archive_hashes"`
	}
	if json.Unmarshal(data, &resolution) != nil || resolution.OriginalBoundSHA256 == "" || len(resolution.ArchiveHashes) == 0 || resolution.EffectiveResponse.Scenarios[0].Outcome != "fail" {
		t.Fatal(string(data))
	}
}

func runDeclarationEngineeringStage(t *testing.T, prompt string) {
	start := strings.Index(prompt, "Controller input:\n") + len("Controller input:\n")
	end := strings.Index(prompt, "\n\nBase commit for source comparison:")
	if end < 0 {
		end = strings.Index(prompt, "\n\nSource base commit:")
	}
	var inv workcycle.Invocation
	if end < start || json.Unmarshal([]byte(prompt[start:end]), &inv) != nil {
		os.Exit(24)
	}
	root := filepath.Dir(filepath.Dir(os.Getenv("HOME")))
	evidence := filepath.Join(root, "evidence", filepath.Base(filepath.Dir(os.Getenv("HOME"))))
	log := filepath.Join(evidence, "result.log")
	if os.WriteFile(log, []byte("measured "+string(inv.Stage)), 0600) != nil {
		os.Exit(25)
	}
	response := workcycle.Response{CandidateID: inv.Candidate.ID, Evidence: []string{log}, EnvironmentID: inv.Request.EnvironmentID}
	switch inv.Stage {
	case workcycle.ContractStage:
		response.Contract = &workcycle.Contract{Scenarios: []workcycle.Scenario{{ID: "literal-classic", CriterionID: "literal", Consumer: "classic", Behavior: "preserve literal", Procedure: "run actual request", Baseline: "fail"}}, Checks: []workcycle.Check{{ID: "module", Scope: "engine"}}}
	case workcycle.ReproduceStage, workcycle.VerifyStage:
		kind, outcome, selector, data := "negative", "pass", "mistyped test", "data-A"
		if inv.Stage == workcycle.VerifyStage {
			kind, outcome, selector, data = "pass", "measured", "defect", "data-B"
			if os.Getenv("QIP_DECLARATION_VERIFY_FAIL") == "1" {
				kind, outcome, selector = "fail", "pass", "mistyped test"
				data = filepath.Base(evidence)
			}
		}
		proof := productCapture(t, evidence, kind)
		trace, err := os.OpenFile(os.Getenv("QIP_DECLARATION_PRODUCT_TRACE"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0600)
		if err != nil {
			os.Exit(26)
		}
		fmt.Fprintln(trace, inv.Stage)
		trace.Close()
		response.DataID = data
		if inv.Stage == workcycle.VerifyStage {
			if selected := os.Getenv("QIP_DECLARATION_VERIFY_DATA"); selected != "" {
				response.DataID = selected
			}
			if os.Getenv("QIP_DECLARATION_VERIFY_BLOCKED") == "1" {
				response.Question = &workcycle.Question{Kind: "verification-blocked", Text: "Restore the candidate preview.", Needed: "Rerun with fresh product data.", Evidence: []string{proof}}
			}
		}
		response.Scenarios = []workcycle.Result{{ID: "literal-classic", Outcome: outcome, Evidence: []string{proof}, ProductTests: []string{selector}}}
		if inv.Stage == workcycle.ReproduceStage && os.Getenv("QIP_DECLARATION_AMBIGUOUS") == "1" {
			anotherRoot := filepath.Join(evidence, "second")
			if os.Mkdir(anotherRoot, 0700) != nil {
				os.Exit(28)
			}
			second := productCapture(t, anotherRoot, "negative")
			response.Scenarios[0].Evidence = append(response.Scenarios[0].Evidence, second)
			response.Scenarios[0].ProductTests = []string{"defect"}
			response.Checks = []workcycle.Result{{ID: "baseline-browser-gate", Outcome: "pass", Evidence: []string{proof, second}, ProductTests: []string{"defect"}}}
		}

		if inv.Stage == workcycle.VerifyStage {
			response.Checks = []workcycle.Result{{ID: "module", Outcome: "measured", Evidence: []string{proof}, ProductTests: []string{"defect"}}}
		}
	case workcycle.ImplementStage:
		if os.WriteFile("engine/code.txt", []byte("fixed candidate"), 0600) != nil {
			os.Exit(27)
		}
	case workcycle.ReviewStage:
	}
	text, _ := json.Marshal(response)
	_ = json.NewEncoder(os.Stdout).Encode(map[string]any{"type": "item.completed", "item": map[string]string{"type": "agent_message", "text": string(text)}})
	fmt.Println(`{"type":"turn.completed"}`)
	os.Exit(0)
}

func TestCorrectedFailedVerificationReachesEngineeringRepair(t *testing.T) {
	cfg := fixture(t)
	binary, _ := os.Executable()
	script := "#!/bin/sh\nif [ \"$1\" = '--version' ]; then echo 'fixture runtime'; exit 0; fi\nexec '" + strings.ReplaceAll(binary, "'", "'\\''") + "' -test.run=TestDeclarationCorrectionRuntimeHelper\n"
	if err := os.WriteFile(cfg.RuntimeBinary, []byte(script), 0700); err != nil {
		t.Fatal(err)
	}
	cfg.ProductCheckBinary = cfg.RuntimeBinary
	cfg.Environment["QIP_DECLARATION_PRODUCT_TRACE"] = filepath.Join(filepath.Dir(cfg.Workspace), "product-trace")
	cfg.Environment["QIP_DECLARATION_VERIFY_FAIL"] = "1"
	report, err := Execute(context.Background(), cfg)
	if err != nil || report.Status == "verified" {
		t.Fatal(report.Status, err)
	}
	firstVerify := -1
	for i, observation := range report.Observations {
		if observation.Stage == workcycle.VerifyStage {
			firstVerify = i
			break
		}
	}
	if firstVerify < 0 || len(report.Observations) <= firstVerify+1 || report.Observations[firstVerify+1].Stage != workcycle.ImplementStage {
		t.Fatal("measured verification failure did not request implementation repair", report.Status)
	}
	if report.Observations[firstVerify].Declaration.Scenarios[0].Outcome != "fail" {
		t.Fatal("declared pass masked measured failure")
	}
}

func TestDriverAmbiguousCapturesRecoverWithoutRepeatingBaseline(t *testing.T) {
	cfg := fixture(t)
	binary, _ := os.Executable()
	script := "#!/bin/sh\nif [ \"$1\" = '--version' ]; then echo 'fixture runtime'; exit 0; fi\nexec '" + strings.ReplaceAll(binary, "'", "'\\''") + "' -test.run=TestDeclarationCorrectionRuntimeHelper\n"
	if err := os.WriteFile(cfg.RuntimeBinary, []byte(script), 0700); err != nil {
		t.Fatal(err)
	}
	cfg.ProductCheckBinary = cfg.RuntimeBinary
	cfg.Environment["QIP_DECLARATION_PRODUCT_TRACE"] = filepath.Join(filepath.Dir(cfg.Workspace), "product-trace")
	cfg.Environment["QIP_DECLARATION_AMBIGUOUS"] = "1"
	report, err := Execute(context.Background(), cfg)
	if err != nil || report.Status != "verified" {
		t.Fatal(report.Status, err)
	}
	baseline := report.Observations[1].Declaration
	if len(baseline.Scenarios[0].ObservationIDs) != 2 || baseline.Scenarios[0].Outcome != "fail" || baseline.Checks[0].Outcome != "fail" {
		t.Fatal(baseline)
	}
	trace, _ := os.ReadFile(cfg.Environment["QIP_DECLARATION_PRODUCT_TRACE"])
	if string(trace) != "reproduce\nverify\n" {
		t.Fatal("baseline was rerun", string(trace))
	}
	correction, _ := filepath.Glob(filepath.Join(cfg.OutputDirectory, "step-*", "declaration-correction"))
	if len(correction) != 1 {
		t.Fatal(correction)
	}
}

func TestDriverCorrectionExhaustionPreservesRejectedCheckpoint(t *testing.T) {
	cfg := fixture(t)
	binary, _ := os.Executable()
	script := "#!/bin/sh\nif [ \"$1\" = '--version' ]; then echo 'fixture runtime'; exit 0; fi\nexec '" + strings.ReplaceAll(binary, "'", "'\\''") + "' -test.run=TestDeclarationCorrectionRuntimeHelper\n"
	if err := os.WriteFile(cfg.RuntimeBinary, []byte(script), 0700); err != nil {
		t.Fatal(err)
	}
	cfg.ProductCheckBinary = cfg.RuntimeBinary
	cfg.Environment["QIP_DECLARATION_PRODUCT_TRACE"] = filepath.Join(filepath.Dir(cfg.Workspace), "product-trace")
	cfg.Environment["QIP_DECLARATION_MODE"] = "unknown"
	report, err := Execute(context.Background(), cfg)
	if err == nil || report.Status != "rejected" {
		t.Fatal(report.Status, err)
	}
	trace, _ := os.ReadFile(cfg.Environment["QIP_DECLARATION_PRODUCT_TRACE"])
	if string(trace) != "reproduce\n" {
		t.Fatal("exhaustion repeated measured work", string(trace))
	}
	steps, _ := filepath.Glob(filepath.Join(cfg.OutputDirectory, "step-*"))
	if len(steps) != 2 {
		t.Fatal(steps)
	}
	for _, name := range []string{"report.json", "step-0002-reproduce/response.json", "step-0002-reproduce/declaration-correction/diagnostic.json", "step-0002-reproduce/declaration-correction/response.json", "step-0002-reproduce/declaration-correction/process.json"} {
		if _, err := os.Stat(filepath.Join(cfg.OutputDirectory, name)); err != nil {
			t.Fatal(name, err)
		}
	}
}

func TestProductPrefixCorrectionRemainsDeterministic(t *testing.T) {
	d, inv, step, evidence, response := declarationProtocolFixture(t)
	wrong := filepath.Join("/mistyped", filepath.Base(filepath.Dir(evidence)), filepath.Base(evidence), "capture", "result.json")
	response.Evidence = []string{wrong}
	response.Scenarios[0].Evidence = []string{wrong}
	response.Scenarios[0].ProductTests = []string{"defect"}
	corrected, err := d.resolveDeclarationBindings(context.Background(), inv, response, evidence, step)
	if err != nil || corrected.Scenarios[0].Outcome != "fail" {
		t.Fatal(corrected, err)
	}
	if _, err := os.Stat(filepath.Join(step, "declaration-correction")); !os.IsNotExist(err) {
		t.Fatal("deterministic prefix correction added a model call", err)
	}
	if _, err := os.Stat(filepath.Join(step, "evidence-corrections.json")); err != nil {
		t.Fatal(err)
	}
}

func TestDeclarationCorrectionWithoutMeasurementsStopsBeforeNativeCall(t *testing.T) {
	d, inv, step, evidence, response := declarationProtocolFixture(t)
	if err := os.RemoveAll(filepath.Join(evidence, "capture")); err != nil {
		t.Fatal(err)
	}
	response.Scenarios[0].Evidence = nil
	response.Scenarios[0].ObservationIDs = []string{"unknown"}
	response.Scenarios[0].ProductTests = nil
	if _, err := d.resolveDeclarationBindings(context.Background(), inv, response, evidence, step); err == nil {
		t.Fatal("empty observation catalog accepted")
	}
	if _, err := os.Stat(filepath.Join(step, "declaration-correction")); !os.IsNotExist(err) {
		t.Fatal("empty observation catalog prompted a native call", err)
	}
}

func TestDeclarationCorrectionSchemaOffersOnlyExistingIDs(t *testing.T) {
	d, _, _, evidence, response := declarationProtocolFixture(t)
	catalog, err := BuildObservationCatalog(evidence)
	if err != nil {
		t.Fatal(err)
	}
	inventory, err := stageEvidenceInventory(evidence)
	if err != nil {
		t.Fatal(err)
	}
	schema := declarationCorrectionSchema(response, catalog, inventory, map[string]bool{"scenario:literal-classic": true})
	if schema["additionalProperties"] != false {
		t.Fatal(schema)
	}
	properties := schema["properties"].(map[string]any)
	bindings := properties["bindings"].(map[string]any)
	if bindings["minItems"] != 0 || bindings["maxItems"] != 1 {
		t.Fatal("correction schema does not bound its choices and allow refusal", bindings)
	}
	binding := properties["bindings"].(map[string]any)["items"].(map[string]any)["anyOf"].([]any)[0].(map[string]any)
	fields := binding["properties"].(map[string]any)
	if fields["id"].(map[string]any)["enum"].([]string)[0] != "literal-classic" {
		t.Fatal(fields)
	}
	if fields["observation_ids"].(map[string]any)["items"].(map[string]any)["enum"].([]string)[0] != catalog.Observations[0].ID {
		t.Fatal(fields)
	}
	response.Evidence = nil
	schema = declarationCorrectionSchema(response, catalog, inventory, map[string]bool{"scenario:literal-classic": true})
	if schema["properties"].(map[string]any)["metadata_references"].(map[string]any)["maxItems"] != 0 {
		t.Fatal(schema)
	}
	if d.step != 0 {
		t.Fatal(d.step)
	}
}

func TestDeclarationCorrectionCannotHijackResolvedFailureOrIntactMetadata(t *testing.T) {
	for _, mode := range []string{"hijack", "metadata"} {
		t.Run(mode, func(t *testing.T) {
			d, inv, step, evidence, response := declarationProtocolFixture(t)
			passRoot := filepath.Join(evidence, "passing")
			if err := os.Mkdir(passRoot, 0700); err != nil {
				t.Fatal(err)
			}
			passingProof := productCapture(t, passRoot, "pass")
			response.Checks = []workcycle.Result{{ID: "verified-failure", Outcome: "fail", Evidence: response.Scenarios[0].Evidence, ProductTests: []string{"defect"}}}
			catalog, err := BuildObservationCatalog(evidence)
			if err != nil {
				t.Fatal(err)
			}
			for _, observation := range catalog.Observations {
				if observation.Outcome == "pass" {
					d.Config.Environment["QIP_DECLARATION_PASS_OBSERVATION"] = observation.ID
				}
			}
			d.Config.Environment["QIP_DECLARATION_MODE"] = mode
			d.Config.Environment["QIP_DECLARATION_ORIGINAL_PROOF"] = response.Evidence[0]
			d.Config.Environment["QIP_DECLARATION_OTHER_PROOF"] = passingProof
			if _, err := d.resolveDeclarationBindings(context.Background(), inv, response, evidence, step); err == nil {
				t.Fatal("correction replaced a resolved failure or existing metadata proof")
			}
			schema, err := os.ReadFile(filepath.Join(step, "declaration-correction", "response-schema.json"))
			if err != nil {
				t.Fatal(err)
			}
			if strings.Contains(string(schema), "verified-failure") {
				t.Fatal("resolved result offered as a correction target", string(schema))
			}
		})
	}
}

func TestDeclarationCorrectionDoesNotInvokeNativeForCompatibilityOnlyBinding(t *testing.T) {
	d, inv, step, evidence, response := declarationProtocolFixture(t)
	response.Scenarios[0].ProductTests = []string{"defect"}
	response.Findings = []workcycle.Finding{{Compatibility: &workcycle.CompatibilityRegression{BaselineResult: workcycle.Result{ID: "compatibility", Outcome: "pass", Evidence: response.Scenarios[0].Evidence, ProductTests: []string{"unknown"}}}}}
	if _, err := d.resolveDeclarationBindings(context.Background(), inv, response, evidence, step); err == nil {
		t.Fatal("unsupported compatibility-only binding accepted")
	}
	if _, err := os.Stat(filepath.Join(step, "declaration-correction")); !os.IsNotExist(err) {
		t.Fatal("unsupported target invoked correction", err)
	}
}

func TestDeclarationCorrectionStopsBeforeNativeForOtherMissingTypedProof(t *testing.T) {
	d, inv, step, evidence, response := declarationProtocolFixture(t)
	response.Checks = []workcycle.Result{{ID: "manual-proof", Outcome: "pass", Evidence: []string{filepath.Join(evidence, "missing-measurement.txt")}}}
	if _, err := d.resolveDeclarationBindings(context.Background(), inv, response, evidence, step); err == nil {
		t.Fatal("missing typed measurement prompted correction")
	}
	if _, err := os.Stat(filepath.Join(step, "declaration-correction")); !os.IsNotExist(err) {
		t.Fatal("missing typed proof invoked native runtime", err)
	}
}

func TestDeclarationCorrectionAcceptsClientDiagnosticWithoutTools(t *testing.T) {
	stream := `{"type":"thread.started","thread_id":"fixture"}
{"type":"item.completed","item":{"id":"item_0","type":"error","message":"Code Mode is unavailable because code-mode host is disabled. Code mode will fail closed; enable features.code_mode_host and install codex-code-mode-host."}}
{"type":"item.completed","item":{"id":"item_1","type":"agent_message","text":"{}"}}
{"type":"turn.completed"}
`
	if err := validateDeclarationOnlyEvents(strings.NewReader(stream)); err != nil {
		t.Fatal(err)
	}
	for _, kind := range []string{"command_execution", "mcp_tool_call", "web_search", "collab_tool_call"} {
		changed := strings.Replace(stream, `"type":"error"`, `"type":"`+kind+`"`, 1)
		if err := validateDeclarationOnlyEvents(strings.NewReader(changed)); err == nil {
			t.Fatalf("accepted tool event %s", kind)
		}
	}
}
