package main

import (
	"fmt"
	"os"
	"path/filepath"
	"reflect"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/localwork"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcontext"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func verifyResumeRouting(root string, cfg localwork.Config, report workcycle.Report, proofPath, proofHash string) error {
	if !filepath.IsAbs(cfg.ResumeFrom) || report.Request.DocumentationEntry.Route == "docs-no-change" {
		return fmt.Errorf("external routing evidence requires a documentation resume")
	}
	previousRoot := filepath.Dir(cfg.ResumeFrom)
	previousProof := filepath.Join(previousRoot, "evidence", "documentation-routing.json")
	if report.Request.DocumentationEntry.Evidence[0] != previousProof {
		return fmt.Errorf("routing evidence differs from the resume source")
	}
	var seed workcycle.Report
	if err := readJSON(cfg.ResumeFrom, &seed); err != nil {
		return err
	}
	var previousConfig localwork.Config
	if err := readJSON(filepath.Join(previousRoot, "config.json"), &previousConfig); err != nil {
		return err
	}
	if !reflect.DeepEqual(seed.Request, report.Request) || previousConfig.TaskID != cfg.TaskID || previousConfig.Workspace != cfg.Workspace || previousConfig.Profile != cfg.Profile || !reflect.DeepEqual(previousConfig.DocumentationTriage, cfg.DocumentationTriage) {
		return fmt.Errorf("resumed routing task or configuration differs from its source")
	}
	var currentContext, previousContext workcontext.Packet
	if err := readJSON(filepath.Join(root, "context.json"), &currentContext); err != nil {
		return err
	}
	if err := readJSON(filepath.Join(previousRoot, "context.json"), &previousContext); err != nil {
		return err
	}
	if !reflect.DeepEqual(currentContext, previousContext) {
		return fmt.Errorf("resumed routing context differs from its source")
	}
	var manifest map[string]string
	if err := readJSON(filepath.Join(previousRoot, "controller-evidence-sha256.json"), &manifest); err != nil {
		return err
	}
	actual, err := fileDigest(previousProof)
	if err != nil {
		return err
	}
	if manifest[previousProof] != proofHash || actual != proofHash {
		return fmt.Errorf("resumed routing proof differs from the verified source evidence")
	}
	var receipt struct {
		ReportSHA256  string            `json:"report_sha256"`
		Evidence      map[string]string `json:"evidence_sha256"`
		PriorEvidence map[string]string `json:"prior_evidence_sha256"`
	}
	err = readJSON(filepath.Join(root, "receipt.json"), &receipt)
	if os.IsNotExist(err) && report.Status != "docs-no-change" && report.Status != "docs-ready-for-pr" {
		return nil
	}
	if err != nil {
		return err
	}
	actual, err = fileDigest(filepath.Join(root, "report.json"))
	if err != nil {
		return err
	}
	if receipt.ReportSHA256 != actual || receipt.Evidence[proofPath] != proofHash || receipt.PriorEvidence[previousProof] != proofHash {
		return fmt.Errorf("resume receipt does not bind current and prior routing evidence")
	}
	return nil
}
