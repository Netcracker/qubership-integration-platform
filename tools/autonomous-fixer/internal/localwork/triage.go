package localwork

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/doctriage"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func (d *Driver) triageAttachment(name string) ([]byte, error) {
	path := name
	if !filepath.IsAbs(path) {
		path = filepath.Join(d.Config.TaskDirectory, path)
	}
	path, err := filepath.EvalSymlinks(path)
	if err != nil {
		return nil, err
	}
	for _, file := range d.Packet.Files {
		if file.Root != "task" {
			continue
		}
		selected := false
		for _, attachment := range d.Config.Context.Attachments {
			selected = selected || attachment == file.Path
		}
		if !selected {
			continue
		}
		resolved, err := filepath.EvalSymlinks(filepath.Join(d.Config.TaskDirectory, file.Path))
		if err != nil {
			return nil, err
		}
		if resolved != path {
			continue
		}
		data, err := os.ReadFile(path)
		if err != nil {
			return nil, err
		}
		sum := sha256.Sum256(data)
		if hex.EncodeToString(sum[:]) != file.SHA256 {
			return nil, fmt.Errorf("documentation triage attachment changed after context preparation")
		}
		return data, nil
	}
	return nil, fmt.Errorf("documentation triage input must be a selected task attachment: %s", name)
}

func (d *Driver) prepareDocumentationTriage() error {
	cfg := d.Config.DocumentationTriage
	if cfg == nil {
		return nil
	}
	if d.Config.TaskKind != "documentation" {
		return fmt.Errorf("documentation triage requires a documentation task")
	}
	packetData, err := d.triageAttachment(cfg.PacketPath)
	if err != nil {
		return err
	}
	reportData, err := d.triageAttachment(cfg.ReportPath)
	if err != nil {
		return err
	}
	var packet doctriage.Packet
	var report doctriage.Report
	if err := Decode(bytes.NewReader(packetData), &packet); err != nil {
		return err
	}
	if err := Decode(bytes.NewReader(reportData), &report); err != nil {
		return err
	}
	var issue struct {
		Number int `json:"number"`
	}
	if err := json.Unmarshal(packet.Issue, &issue); err != nil || issue.Number <= 0 || packet.Repository == "" {
		return fmt.Errorf("documentation packet has no repository and issue identity")
	}
	if d.Config.TaskID != fmt.Sprintf("%s#%d:documentation", packet.Repository, issue.Number) {
		return fmt.Errorf("documentation packet does not match task identity")
	}
	if packet.DocsSHA != d.base {
		return fmt.Errorf("documentation packet snapshot differs from workspace HEAD")
	}
	if err := doctriage.ValidateReport(packet, report); err != nil {
		return err
	}
	policy := doctriage.DefaultRoutingPolicy()
	if cfg.Policy != nil {
		policy = *cfg.Policy
	}
	routing, err := doctriage.SelectRoute(packet, report, policy)
	if err != nil {
		return err
	}
	proof := filepath.Join(d.evidence, "documentation-routing.json")
	if err := writeJSON(proof, map[string]any{"origin": "jev-controller-routing", "task_id": d.Config.TaskID, "source_sha": d.base, "context_sha256": d.Packet.SHA256, "packet": packet, "report": report, "routing": routing}); err != nil {
		return err
	}
	proofData, err := os.ReadFile(proof)
	if err != nil {
		return err
	}
	hash := sha256.Sum256(proofData)
	d.triageProofHash = hex.EncodeToString(hash[:])
	d.artifacts = map[string]string{proof: d.triageProofHash}
	if err := writeJSON(filepath.Join(d.Config.OutputDirectory, "routing.json"), routing); err != nil {
		return err
	}
	entry := &workcycle.DocumentationEntry{Route: routing.Route, Evidence: []string{proof}}
	if routing.Route == "docs-no-change" {
		if routing.Recommendation.Action != "no_change" || routing.Recommendation.Attribution != "unchanged" {
			return fmt.Errorf("automatic no-change requires unchanged documentation attribution")
		}
		explanation := map[string]string{
			"already_covered":       "The current help already describes the resulting behavior.",
			"restored_behavior":     "The fix restores behavior already described in the current help.",
			"no_explanation_needed": "The change does not require an additional explanation for readers of the current help.",
			"internal_only":         "The merged changes affect internal implementation or developer tooling without changing the documented product workflow.",
			"outside_help_scope":    "The merged changes concern operations outside the scope of product help.",
		}[routing.Recommendation.Reason]
		if explanation == "" {
			return fmt.Errorf("automatic no-change has no supported explanation")
		}
		summary := fmt.Sprintf("Automated documentation triage selected no change for issue #%d. %s", issue.Number, explanation)
		comment := fmt.Sprintf("No help update is required for this issue. %s\n\nAutomated classification used the merged-change evidence and [help at %s](https://github.com/%s/tree/%s/help/docs).", explanation, packet.DocsSHA, packet.Repository, packet.DocsSHA)
		entry.Decision = &workcycle.DocumentationDecision{Decision: "no_change", Reason: routing.Recommendation.Reason, Summary: summary, IssueComment: comment, Claims: []workcycle.DocClaim{{ID: "classifier-doc-impact", Behavior: "Documentation impact of the selected implementing changes.", Attribution: "unchanged", NeedsChange: false, SourceEvidence: []string{proof}, DocumentationEvidence: []string{proof}, Action: summary}}}
	}
	d.Request.DocumentationEntry = entry
	return writeJSON(filepath.Join(d.Config.OutputDirectory, "controller-evidence-sha256.json"), d.artifacts)
}

func entryWithoutEvidence(entry *workcycle.DocumentationEntry) *workcycle.DocumentationEntry {
	if entry == nil {
		return nil
	}
	data, _ := json.Marshal(entry)
	var copy workcycle.DocumentationEntry
	_ = json.Unmarshal(data, &copy)
	copy.Evidence = nil
	if copy.Decision != nil {
		for _, claims := range [][]workcycle.DocClaim{copy.Decision.Claims, copy.Decision.SeparateFindings} {
			for i := range claims {
				claims[i].SourceEvidence = nil
				claims[i].DocumentationEvidence = nil
			}
		}
	}
	return &copy
}
