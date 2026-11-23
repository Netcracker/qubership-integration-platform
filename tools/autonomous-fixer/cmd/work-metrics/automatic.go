package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"reflect"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/doctriage"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/localwork"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

type routingProof struct {
	Origin        string            `json:"origin"`
	TaskID        string            `json:"task_id"`
	SourceSHA     string            `json:"source_sha"`
	ContextSHA256 string            `json:"context_sha256"`
	Packet        doctriage.Packet  `json:"packet"`
	Report        doctriage.Report  `json:"report"`
	Routing       doctriage.Routing `json:"routing"`
}

func fileDigest(path string) (string, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	sum := sha256.Sum256(data)
	return hex.EncodeToString(sum[:]), nil
}

func readRoutingProof(root string, cfg localwork.Config, report workcycle.Report) (routingProof, error) {
	var proof routingProof
	entry := report.Request.DocumentationEntry
	proofPath := filepath.Join(root, "evidence", "documentation-routing.json")
	if cfg.TaskKind != "documentation" || cfg.DocumentationTriage == nil || cfg.TaskID == "" || report.Request.TaskID != cfg.TaskID || report.Request.Kind != "documentation" || entry == nil || len(entry.Evidence) != 1 {
		return proof, fmt.Errorf("routing evidence does not belong to this documentation task")
	}
	var manifest map[string]string
	if err := readJSON(filepath.Join(root, "controller-evidence-sha256.json"), &manifest); err != nil {
		return proof, err
	}
	hash, err := fileDigest(proofPath)
	if err != nil {
		return proof, err
	}
	if manifest[proofPath] != hash {
		return proof, fmt.Errorf("routing evidence does not match the controller hash")
	}
	if entry.Evidence[0] != proofPath {
		if err := verifyResumeRouting(root, cfg, report, proofPath, hash); err != nil {
			return proof, err
		}
	}
	if err := readJSON(proofPath, &proof); err != nil {
		return proof, err
	}
	var routing doctriage.Routing
	if err := readJSON(filepath.Join(root, "routing.json"), &routing); err != nil {
		return proof, err
	}
	var context struct {
		SHA256 string `json:"sha256"`
	}
	if err := readJSON(filepath.Join(root, "context.json"), &context); err != nil {
		return proof, err
	}
	if proof.Origin != "jev-controller-routing" || proof.TaskID != cfg.TaskID || proof.SourceSHA == "" || proof.Packet.DocsSHA != proof.SourceSHA || proof.ContextSHA256 == "" || context.SHA256 != proof.ContextSHA256 || !reflect.DeepEqual(proof.Routing, routing) || routing.Route != entry.Route {
		return proof, fmt.Errorf("routing proof differs from the task record")
	}
	packetBytes, err := json.Marshal(proof.Packet)
	if err != nil {
		return proof, err
	}
	sum := sha256.Sum256(packetBytes)
	if proof.Report.PacketSHA256 != hex.EncodeToString(sum[:]) || proof.Report.PacketID != proof.Packet.ID || proof.Report.Response == nil && proof.Report.Error == "" {
		return proof, fmt.Errorf("routing proof has no matching classifier report")
	}
	if proof.Report.Mode == "preflight" {
		if err := doctriage.ValidateReport(proof.Packet, proof.Report); err != nil {
			return proof, err
		}
	}
	return proof, nil
}

func verifyAutomaticNoChange(root string, cfg localwork.Config, report workcycle.Report) error {
	proof, err := readRoutingProof(root, cfg, report)
	if err != nil {
		return err
	}
	entry := report.Request.DocumentationEntry
	if cfg.ResumeFrom != "" || report.Status != "docs-no-change" || report.Error != "" || report.Question != nil || len(report.Observations) != 0 || report.Candidate.ID == "" || len(report.Candidate.ChangedPaths) != 0 {
		return fmt.Errorf("report is not a completed automatic documentation decision")
	}
	if entry.Route != "docs-no-change" || entry.Decision == nil || entry.Decision.Decision != "no_change" || entry.Decision.Reason == "" || entry.Decision.Summary == "" || entry.Decision.IssueComment == "" || len(entry.Decision.Claims) == 0 || !reflect.DeepEqual(entry.Decision, report.Documentation) {
		return fmt.Errorf("report has no complete controller no-change decision")
	}
	if proof.Routing.Recommendation.Action != "no_change" || proof.Routing.Recommendation.Reason != entry.Decision.Reason || proof.Routing.Recommendation.Attribution != "unchanged" || proof.Report.Response == nil || proof.Report.Error != "" {
		return fmt.Errorf("routing proof has no successful no-change classification")
	}
	var receipt struct {
		ContextSHA256 string            `json:"context_sha256"`
		ReportSHA256  string            `json:"report_sha256"`
		CandidateID   string            `json:"candidate_id"`
		Evidence      map[string]string `json:"evidence_sha256"`
	}
	if err := readJSON(filepath.Join(root, "receipt.json"), &receipt); err != nil {
		return err
	}
	hash, err := fileDigest(filepath.Join(root, "report.json"))
	if err != nil {
		return err
	}
	if receipt.ReportSHA256 != hash || receipt.CandidateID != report.Candidate.ID || receipt.ContextSHA256 != proof.ContextSHA256 {
		return fmt.Errorf("completion receipt does not match the report")
	}
	proofPath := entry.Evidence[0]
	hash, err = fileDigest(proofPath)
	if err != nil {
		return err
	}
	if receipt.Evidence[proofPath] != hash {
		return fmt.Errorf("completion receipt does not match routing evidence")
	}
	return nil
}
