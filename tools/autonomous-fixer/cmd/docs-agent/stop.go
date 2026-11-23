package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/agentsignature"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/doccycle"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/docgithub"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

type workNotice struct {
	Reason    string                   `json:"reason"`
	Signature agentsignature.Signature `json:"signature"`
}

func documentationStopNotice(prepared, runDir string, report workcycle.Report) *workNotice {
	reason := workStopReason(report)
	if reason == "" && report.Status != "docs-no-change" && report.Status != "docs-ready-for-pr" {
		return nil
	}
	var plan docgithub.Plan
	if err := read(filepath.Join(prepared, "plan.json"), &plan); err != nil || plan.Validate() != nil ||
		report.Workflow != string(doccycle.AuthorStage) ||
		report.Request.TaskID != fmt.Sprintf("%s#%d:documentation", plan.Repository, plan.IssueNumber) {
		return nil
	}
	notice := &workNotice{Reason: reason}
	signature, err := agentsignature.ReadRun(runDir)
	if err != nil || signature.Validate() != nil {
		return notice
	}
	line := signature.Line()
	for _, secret := range controllerCredentials() {
		if strings.Contains(line, string(secret)) {
			return notice
		}
	}
	notice.Signature = signature
	return notice
}

func workStopReason(report workcycle.Report) string {
	switch report.Status {
	case "needs-human", "verification-blocked":
		return report.Status
	case "rejected":
		if strings.HasPrefix(report.Error, "decode stage result: ") {
			return "rejected-result-format"
		}
		return "rejected"
	default:
		return ""
	}
}

func reportPublicationFailure(err error) error {
	path := os.Getenv("GITHUB_OUTPUT")
	failure := docgithub.PublicationFailureOf(err)
	if path == "" || failure == "" {
		return err
	}
	output, openErr := os.OpenFile(path, os.O_WRONLY|os.O_APPEND, 0)
	if openErr != nil {
		return errors.Join(err, openErr)
	}
	_, writeErr := fmt.Fprintf(output, "publication_failure=%s\n", failure)
	return errors.Join(err, writeErr, output.Close())
}

func recordScheduleStop(ctx context.Context, prepared string, issue int, stop docgithub.ScheduleStop, signature string) error {
	if !filepath.IsAbs(prepared) || issue <= 0 {
		return fmt.Errorf("record-stop requires an absolute prepared directory and a positive issue number")
	}
	var plan docgithub.Plan
	if err := read(filepath.Join(prepared, "plan.json"), &plan); err != nil {
		return err
	}
	if issue != plan.IssueNumber {
		return fmt.Errorf("documentation stop issue differs from the prepared plan")
	}
	attempt, err := schedulerAttempt(plan)
	if err != nil {
		return err
	}
	stop.Current = attempt
	if err = json.Unmarshal([]byte(signature), &stop.Signature); err != nil {
		return fmt.Errorf("invalid documentation usage signature")
	}
	client, err := github(plan.Repository)
	if err != nil {
		return err
	}
	if err = validateActor(ctx, client, stop.Actor); err != nil {
		return err
	}
	updated, err := docgithub.RecordScheduleStop(ctx, client, plan, stop)
	if err != nil {
		return err
	}
	return json.NewEncoder(os.Stdout).Encode(map[string]bool{"updated": updated})
}
