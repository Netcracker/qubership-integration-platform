package localwork

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/checkrunner"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func archivedCapture(t *testing.T, root, name string) string {
	t.Helper()
	path := productCapture(t, root, "negative")
	var record checkrunner.ProductRecord
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if err = json.Unmarshal(b, &record); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(filepath.Join("testdata", "archived-878-"+name+".json"))
	if err != nil {
		t.Fatal(err)
	}
	summary, err := checkrunner.ParseProductReport("playwright", data)
	if err != nil {
		t.Fatal(err)
	}
	record.Summary = &summary
	record.Process.StartedAt = summary.StartedAt
	record.Process.FinishedAt = summary.StartedAt.Add(1)
	record.Options.ExpectedFailedTests = nil
	for _, test := range summary.Tests {
		if test.Outcome == "fail" {
			record.Options.ExpectedFailedTests = append(record.Options.ExpectedFailedTests, test.FullName)
		}
	}
	record.ReportSHA256 = bytesDigest(data)
	record.OutputSHA256[record.ReportCopy] = record.ReportSHA256
	if err = os.WriteFile(record.ReportCopy, data, 0600); err != nil {
		t.Fatal(err)
	}
	if err = writeJSON(path, record); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestArchived878RepeatedTitleBindsToMeasuredFailure(t *testing.T) {
	root := t.TempDir()
	var paths []string
	for _, name := range []string{"toolbar", "search"} {
		dir := filepath.Join(root, name)
		if err := os.Mkdir(dir, 0700); err != nil {
			t.Fatal(err)
		}
		paths = append(paths, archivedCapture(t, dir, name))
	}
	catalog, err := BuildObservationCatalog(root)
	if err != nil {
		t.Fatal(err)
	}
	var ids []string
	for _, observation := range catalog.Observations {
		if strings.Contains(observation.FullName, "uses the compact Search control") {
			ids = append(ids, observation.ID)
		}
	}
	if len(ids) != 2 || ids[0] == ids[1] {
		t.Fatal("duplicate titles did not produce distinct observation IDs", ids)
	}
	response := workcycle.Response{CandidateID: "source", EnvironmentID: "environment", DataID: "dataset", Scenarios: []workcycle.Result{{ID: "c2-chains-search", Outcome: "measured", ObservationIDs: ids}}, Checks: []workcycle.Result{{ID: "browser-regression", Outcome: "pass", ObservationIDs: ids}}}
	d := &Driver{evidence: root}
	if err = d.bindProductObservations(&response, catalog); err != nil {
		t.Fatal(err)
	}
	if response.Scenarios[0].Outcome != "fail" || response.Checks[0].Outcome != "fail" {
		t.Fatal("actual baseline failures changed", response)
	}
	if response.CandidateID != "source" || response.EnvironmentID != "environment" || response.DataID != "dataset" || response.Scenarios[0].ID != "c2-chains-search" {
		t.Fatal("semantic identities changed", response)
	}
	if len(response.Evidence) != 4 {
		t.Fatal("machine proof aggregate missing", response.Evidence)
	}
	if err = d.validateProductResults(response, root, workcycle.ReproduceStage); err != nil {
		t.Fatal(err)
	}
}

func TestObservationBindingRejectsCorruptMeasurements(t *testing.T) {
	for _, kind := range []string{"missing", "tampered", "timeout", "rejected", "stale"} {
		t.Run(kind, func(t *testing.T) {
			root := t.TempDir()
			captureKind := "pass"
			if kind == "timeout" || kind == "rejected" {
				captureKind = kind
			}
			path := productCapture(t, root, captureKind)
			var record checkrunner.ProductRecord
			data, _ := os.ReadFile(path)
			json.Unmarshal(data, &record)
			observations, _ := checkrunner.ProductObservations(record)
			id := "product-observation-v1:unavailable"
			if len(observations) > 0 {
				id = observations[0].ID
			}
			switch kind {
			case "missing":
				os.Remove(record.ReportCopy)
			case "tampered":
				os.WriteFile(record.ReportCopy, []byte("{}"), 0600)
			case "stale":
				record.Process.StartedAt = record.Process.FinishedAt.Add(1)
				writeJSON(path, record)
			}
			catalog, err := BuildObservationCatalog(root)
			if err != nil {
				t.Fatal(err)
			}
			if len(catalog.Observations) != 0 || len(catalog.Diagnostics) != 1 {
				t.Fatal("invalid capture entered catalog", catalog)
			}
			response := workcycle.Response{Scenarios: []workcycle.Result{{ID: "scenario", Outcome: "measured", ObservationIDs: []string{id}, Evidence: []string{path}}}}
			d := &Driver{evidence: root}
			err = d.bindProductObservations(&response, catalog)
			var declaration *DeclarationBindingError
			if err == nil || errors.As(err, &declaration) {
				t.Fatal("broken measurement accepted or made repairable", err)
			}
		})
	}
}

func TestObservationBindingIsAtomicAndPreservesStaticChecks(t *testing.T) {
	root := t.TempDir()
	path := productCapture(t, root, "pass")
	catalog, err := BuildObservationCatalog(root)
	if err != nil {
		t.Fatal(err)
	}
	d := &Driver{evidence: root}
	response := workcycle.Response{Scenarios: []workcycle.Result{{ID: "scenario", Outcome: "measured", ObservationIDs: []string{catalog.Observations[0].ID}}}, Checks: []workcycle.Result{{ID: "static", Outcome: "pass", Evidence: []string{"static.log"}}, {ID: "unknown", Outcome: "measured", ObservationIDs: []string{"unknown"}}}}
	original, _ := json.Marshal(response)
	err = d.bindProductObservations(&response, catalog)
	var declaration *DeclarationBindingError
	if !errors.As(err, &declaration) {
		t.Fatal("unknown ID is not repairable", err)
	}
	after, _ := json.Marshal(response)
	if string(original) != string(after) {
		t.Fatal("partial binding mutated response")
	}
	response.Checks = response.Checks[:1]
	static := response.Checks[0]
	if err = d.bindProductObservations(&response, catalog); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(static, response.Checks[0]) {
		t.Fatal("static check changed")
	}
	if len(response.Scenarios[0].Evidence) != 2 || response.Scenarios[0].Evidence[0] != path {
		t.Fatal("capture evidence missing")
	}
}

func TestObservationCatalogDetectsChangesAfterBindingCatalog(t *testing.T) {
	root := t.TempDir()
	path := productCapture(t, root, "pass")
	catalog, err := BuildObservationCatalog(root)
	if err != nil {
		t.Fatal(err)
	}
	os.WriteFile(path, []byte("{}"), 0600)
	response := workcycle.Response{Scenarios: []workcycle.Result{{ID: "scenario", Outcome: "measured", ObservationIDs: []string{catalog.Observations[0].ID}}}}
	d := &Driver{evidence: root}
	if err = d.bindProductObservations(&response, catalog); err == nil {
		t.Fatal("changed record accepted")
	}
}

func TestRejectedCaptureRemainsDiagnosticUntilUsedAsTypedProof(t *testing.T) {
	root := t.TempDir()
	paths := map[string]string{}
	for _, kind := range []string{"pass", "rejected"} {
		dir := filepath.Join(root, kind)
		if err := os.Mkdir(dir, 0700); err != nil {
			t.Fatal(err)
		}
		paths[kind] = productCapture(t, dir, kind)
	}
	catalog, err := BuildObservationCatalog(root)
	if err != nil || len(catalog.Observations) != 1 || len(catalog.Diagnostics) != 1 {
		t.Fatal(catalog, err)
	}
	d := &Driver{evidence: root}
	response := workcycle.Response{Evidence: []string{paths["rejected"]}, Scenarios: []workcycle.Result{{ID: "scenario", Outcome: "measured", ObservationIDs: []string{catalog.Observations[0].ID}}}}
	if err = d.bindProductObservations(&response, catalog); err != nil {
		t.Fatal("diagnostic blocked valid measurement", err)
	}
	if err = d.verifyArtifacts(response, root); err != nil {
		t.Fatal(err)
	}
	if err = d.validateProductResults(response, root, workcycle.VerifyStage); err != nil {
		t.Fatal("rejected diagnostic treated as measurement", err)
	}
	response.Scenarios[0].Evidence = append(response.Scenarios[0].Evidence, paths["rejected"])
	if err = d.bindProductObservations(&response, catalog); err == nil {
		t.Fatal("typed rejected proof accepted")
	}
	if err = d.validateProductResults(response, root, workcycle.VerifyStage); err == nil {
		t.Fatal("typed rejected proof passed measurement validation")
	}
}

func TestTamperedIDSelectionFailsWithAnotherValidCaptureAvailable(t *testing.T) {
	root := t.TempDir()
	var selected string
	for _, name := range []string{"good", "tampered"} {
		dir := filepath.Join(root, name)
		if err := os.Mkdir(dir, 0700); err != nil {
			t.Fatal(err)
		}
		path := productCapture(t, dir, "pass")
		if name == "tampered" {
			var record checkrunner.ProductRecord
			data, _ := os.ReadFile(path)
			json.Unmarshal(data, &record)
			observations, err := checkrunner.ProductObservations(record)
			if err != nil {
				t.Fatal(err)
			}
			selected = observations[0].ID
			os.WriteFile(record.ReportCopy, []byte("{}"), 0600)
		}
	}
	catalog, err := BuildObservationCatalog(root)
	if err != nil || len(catalog.Observations) != 1 || len(catalog.Diagnostics) != 1 {
		t.Fatal(catalog, err)
	}
	response := workcycle.Response{Scenarios: []workcycle.Result{{ID: "scenario", Outcome: "measured", ObservationIDs: []string{selected}}}}
	d := &Driver{evidence: root}
	err = d.bindProductObservations(&response, catalog)
	var declaration *DeclarationBindingError
	if err == nil || errors.As(err, &declaration) {
		t.Fatal("corrupt selected proof became repairable", err)
	}
}

func TestIDSelectionKeepsUnselectedFailuresInReferencedCapture(t *testing.T) {
	root := t.TempDir()
	productCapture(t, root, "negative")
	path := filepath.Join(root, "capture", "result.json")
	addProductTest(t, path, "healthy peer", "pass")
	catalog, err := BuildObservationCatalog(root)
	if err != nil {
		t.Fatal(err)
	}
	var healthy string
	for _, observation := range catalog.Observations {
		if observation.Outcome == "pass" {
			healthy = observation.ID
		}
	}
	response := workcycle.Response{Scenarios: []workcycle.Result{{ID: "healthy", Outcome: "measured", ObservationIDs: []string{healthy}}}}
	d := &Driver{evidence: root}
	if err = d.bindProductObservations(&response, catalog); err != nil {
		t.Fatal(err)
	}
	if response.Scenarios[0].Outcome != "pass" {
		t.Fatal(response)
	}
	if err = d.validateProductResults(response, root, workcycle.VerifyStage); err == nil {
		t.Fatal("unselected actual failure disappeared")
	}
}

func TestFinalCatalogRequiresFailuresEvenWhenTheirCaptureIsOmitted(t *testing.T) {
	root := t.TempDir()
	for _, kind := range []string{"pass", "fail"} {
		dir := filepath.Join(root, kind)
		if err := os.Mkdir(dir, 0700); err != nil {
			t.Fatal(err)
		}
		productCapture(t, dir, kind)
	}
	catalog, err := BuildObservationCatalog(root)
	if err != nil {
		t.Fatal(err)
	}
	var passed, failed string
	for _, observation := range catalog.Observations {
		if observation.Outcome == "pass" {
			passed = observation.ID
		} else if observation.Outcome == "fail" {
			failed = observation.ID
		}
	}
	response := workcycle.Response{Scenarios: []workcycle.Result{{ID: "healthy", Outcome: "measured", ObservationIDs: []string{passed}}}}
	d := &Driver{evidence: root}
	if err = d.bindProductObservations(&response, catalog); err != nil {
		t.Fatal(err)
	}
	if err = d.verifyArtifacts(response, root); err != nil {
		t.Fatal(err)
	}
	if err = d.validateProductResults(response, root, workcycle.VerifyStage); err == nil {
		t.Fatal("entire failed capture omitted from final measurements")
	}
	for _, stage := range []workcycle.Stage{workcycle.ReproduceStage, workcycle.ImplementStage, workcycle.ReviewStage} {
		if err = d.validateProductResults(response, root, stage); err != nil {
			t.Fatal("final-only coverage leaked into", stage, err)
		}
	}
	response.Scenarios = append(response.Scenarios, workcycle.Result{ID: "defect", Outcome: "measured", ObservationIDs: []string{failed}})
	if err = d.bindProductObservations(&response, catalog); err != nil {
		t.Fatal(err)
	}
	if err = d.validateProductResults(response, root, workcycle.VerifyStage); err != nil {
		t.Fatal("accounted measured failure rejected", err)
	}
	if response.Scenarios[1].Outcome != "fail" {
		t.Fatal("actual failure changed", response.Scenarios[1])
	}
}
