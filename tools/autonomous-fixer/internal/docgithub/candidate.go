package docgithub

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"sort"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/doccycle"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/docpacket"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/doctriage"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/jev"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/localwork"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcontext"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

type CandidateFile struct {
	Path    string `json:"path"`
	Content []byte `json:"content,omitempty"`
	Deleted bool   `json:"deleted,omitempty"`
}
type Candidate struct {
	Report workcycle.Report `json:"report"`
	Files  []CandidateFile  `json:"files,omitempty"`
	Digest string           `json:"digest"`
	jev    *jev.Response
}

func bytesHash(b []byte) string { h := sha256.Sum256(b); return hex.EncodeToString(h[:]) }
func readJSON(path string, target any) error {
	b, e := os.ReadFile(path)
	if e != nil {
		return e
	}
	return json.Unmarshal(b, target)
}
func gitRead(ctx context.Context, dir string, args ...string) ([]byte, error) {
	c := exec.CommandContext(ctx, "git", append([]string{"-c", "core.hooksPath=/dev/null", "-c", "core.fsmonitor=false"}, args...)...)
	c.Dir = dir
	c.Env = []string{"PATH=" + os.Getenv("PATH"), "HOME=/nonexistent", "GIT_CONFIG_NOSYSTEM=1", "GIT_CONFIG_GLOBAL=/dev/null", "GIT_TERMINAL_PROMPT=0"}
	b, e := c.CombinedOutput()
	if e != nil {
		return nil, fmt.Errorf("read candidate Git state: %w", e)
	}
	return b, nil
}
func within(root, name string) bool {
	rel, e := filepath.Rel(root, name)
	return e == nil && rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator))
}
func regularFile(root, name string) ([]byte, error) {
	info, err := os.Lstat(root)
	if err != nil {
		return nil, err
	}
	if !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
		return nil, fmt.Errorf("publication artifact root is not a directory")
	}
	if !within(root, name) {
		return nil, fmt.Errorf("candidate path escapes its root")
	}
	rel, _ := filepath.Rel(root, name)
	cur := root
	for _, part := range strings.Split(rel, string(filepath.Separator)) {
		cur = filepath.Join(cur, part)
		i, e := os.Lstat(cur)
		if e != nil {
			return nil, e
		}
		if i.Mode()&os.ModeSymlink != 0 {
			return nil, fmt.Errorf("symlink in publication artifact")
		}
	}
	i, e := os.Stat(name)
	if e != nil {
		return nil, e
	}
	if !i.Mode().IsRegular() {
		return nil, fmt.Errorf("publication artifact is not a file")
	}
	return os.ReadFile(name)
}
func validatePublicationPlan(plan Plan) error {
	if err := plan.Validate(); err != nil {
		return err
	}
	digest, err := hex.DecodeString(plan.PacketSHA256)
	if err != nil || len(digest) != sha256.Size {
		return fmt.Errorf("publication plan has no bound preparation packet")
	}
	return nil
}

func ValidateCandidate(ctx context.Context, plan Plan, runDir, sourcePath string) (Candidate, error) {
	var c Candidate
	if e := validatePublicationPlan(plan); e != nil {
		return c, e
	}
	var cfg localwork.Config
	var contextPacket workcontext.Packet
	if e := readJSON(filepath.Join(runDir, "config.json"), &cfg); e != nil {
		return c, e
	}
	if sourcePath == "" {
		sourcePath = cfg.Workspace
	}
	taskDirectory := cfg.TaskDirectory
	if cfg.TaskDirectory == filepath.Join(filepath.Dir(cfg.OutputDirectory), "task") {
		taskDirectory = filepath.Join(filepath.Dir(runDir), "task")
	}
	if cfg.TaskKind != "documentation" || cfg.TaskID != fmt.Sprintf("%s#%d:documentation", plan.Repository, plan.IssueNumber) || cfg.DocumentationTriage == nil {
		return c, fmt.Errorf("work task does not match publication plan")
	}
	if e := readJSON(filepath.Join(runDir, "report.json"), &c.Report); e != nil {
		return c, e
	}
	if c.Report.Request.TaskID != cfg.TaskID || c.Report.Request.Kind != "documentation" || c.Report.Error != "" || c.Report.Question != nil || (c.Report.Status != "docs-no-change" && c.Report.Status != "docs-ready-for-pr") {
		return c, fmt.Errorf("work did not complete this documentation task")
	}
	author := cfg.Workflow == string(doccycle.AuthorStage)
	if author != (c.Report.Workflow == string(doccycle.AuthorStage)) {
		return c, fmt.Errorf("documentation workflow differs from the work configuration")
	}
	if author && (!cfg.Hosted || cfg.ResumeFrom != "" || cfg.ReviewProfile != nil || len(c.Report.ReviewDecisions) != 0 ||
		!reflect.DeepEqual(cfg.RequiredChecks, doccycle.PublicationChecks()) || !reflect.DeepEqual(c.Report.Request.RequiredChecks, cfg.RequiredChecks)) {
		return c, fmt.Errorf("hosted documentation publication requires a fresh author run with the configured checks")
	}
	if e := readJSON(filepath.Join(runDir, "context.json"), &contextPacket); e != nil {
		return c, e
	}
	if e := workcontext.Verify(sourcePath, taskDirectory, contextPacket); e != nil {
		return c, e
	}
	if c.Report.Request.Context != contextPacket.Text {
		return c, fmt.Errorf("report context changed")
	}
	var receipt struct {
		ContextSHA256 string            `json:"context_sha256"`
		ReportSHA256  string            `json:"report_sha256"`
		CandidateID   string            `json:"candidate_id"`
		Evidence      map[string]string `json:"evidence_sha256"`
		Prior         map[string]string `json:"prior_evidence_sha256"`
	}
	if e := readJSON(filepath.Join(runDir, "receipt.json"), &receipt); e != nil {
		return c, e
	}
	reportBytes, e := os.ReadFile(filepath.Join(runDir, "report.json"))
	if e != nil {
		return c, e
	}
	if receipt.ReportSHA256 != bytesHash(reportBytes) || receipt.ContextSHA256 != contextPacket.SHA256 || receipt.CandidateID != c.Report.Candidate.ID {
		return c, fmt.Errorf("completion receipt does not match work report")
	}
	if author && len(receipt.Prior) != 0 {
		return c, fmt.Errorf("hosted documentation publication requires evidence from the author run")
	}
	hashes := map[string]string{}
	for _, group := range []struct {
		root   string
		hashes map[string]string
	}{{filepath.Join(cfg.OutputDirectory, "evidence"), receipt.Evidence}, {filepath.Join(filepath.Dir(cfg.ResumeFrom), "evidence"), receipt.Prior}} {
		for name, want := range group.hashes {
			actual := name
			actualRoot := group.root
			if within(cfg.OutputDirectory, name) {
				rel, _ := filepath.Rel(cfg.OutputDirectory, name)
				actual = filepath.Join(runDir, rel)
				actualRoot = filepath.Join(runDir, "evidence")
			}
			b, e := regularFile(actualRoot, actual)
			if e != nil || len(b) == 0 || bytesHash(b) != want {
				return c, fmt.Errorf("evidence changed or disappeared: %s", name)
			}
			hashes[name] = want
		}
	}
	evidence := func(refs []string) bool {
		if len(refs) == 0 {
			return false
		}
		for _, ref := range refs {
			if hashes[ref] == "" {
				return false
			}
		}
		return true
	}
	freshEvidence := func(root string) func([]string) bool {
		return func(refs []string) bool {
			if !evidence(refs) {
				return false
			}
			for _, ref := range refs {
				if !within(root, ref) {
					return false
				}
			}
			return true
		}
	}
	var proof struct {
		TaskID        string            `json:"task_id"`
		SourceSHA     string            `json:"source_sha"`
		ContextSHA256 string            `json:"context_sha256"`
		Packet        doctriage.Packet  `json:"packet"`
		Report        doctriage.Report  `json:"report"`
		Routing       doctriage.Routing `json:"routing"`
	}
	proofPath := filepath.Join(runDir, "evidence", "documentation-routing.json")
	proofBytes, err := regularFile(filepath.Join(runDir, "evidence"), proofPath)
	if err != nil || hashes[filepath.Join(cfg.OutputDirectory, "evidence", "documentation-routing.json")] != bytesHash(proofBytes) {
		return c, fmt.Errorf("routing proof is not bound to completion receipt")
	}
	if e := json.Unmarshal(proofBytes, &proof); e != nil {
		return c, e
	}
	taskAttachment := func(name string) string {
		if !filepath.IsAbs(name) {
			return filepath.Join(taskDirectory, name)
		}
		if taskDirectory != cfg.TaskDirectory && within(cfg.TaskDirectory, name) {
			rel, _ := filepath.Rel(cfg.TaskDirectory, name)
			return filepath.Join(taskDirectory, rel)
		}
		return name
	}
	readAttachment := func(name string, target any) error {
		actual := taskAttachment(name)
		rel, err := filepath.Rel(taskDirectory, actual)
		if err != nil {
			return err
		}
		selected := false
		for _, name := range cfg.Context.Attachments {
			selected = selected || name == rel
		}
		if !selected {
			return fmt.Errorf("triage input is not a selected task attachment")
		}
		for _, file := range contextPacket.Files {
			if file.Root == "task" && file.Path == rel {
				data, err := regularFile(taskDirectory, actual)
				if err != nil {
					return err
				}
				if bytesHash(data) != file.SHA256 {
					return fmt.Errorf("triage attachment differs from context")
				}
				return json.Unmarshal(data, target)
			}
		}
		return fmt.Errorf("triage attachment is absent from the verified context")
	}
	var classifierReport doctriage.Report
	if e := readAttachment(cfg.DocumentationTriage.ReportPath, &classifierReport); e != nil {
		return c, e
	}
	if Digest(classifierReport) != Digest(proof.Report) {
		return c, fmt.Errorf("routing proof differs from the context-bound classifier report")
	}
	var packet doctriage.Packet
	if e := readAttachment(cfg.DocumentationTriage.PacketPath, &packet); e != nil {
		return c, e
	}
	if Digest(packet) != plan.PacketSHA256 || Digest(packet) != Digest(proof.Packet) || proof.TaskID != cfg.TaskID || proof.SourceSHA != plan.BaseSHA || proof.ContextSHA256 != contextPacket.SHA256 || packet.Repository != plan.Repository || packet.DocsSHA != plan.BaseSHA {
		return c, fmt.Errorf("classification evidence does not match publication plan")
	}
	if e := doctriage.ValidateReport(packet, proof.Report); e != nil {
		return c, e
	}
	c.jev = proof.Report.Response
	routing, e := doctriage.SelectRoute(packet, proof.Report, proof.Routing.Policy)
	if e != nil {
		return c, e
	}
	entry := c.Report.Request.DocumentationEntry
	if entry == nil || entry.Route != routing.Route || !evidence(entry.Evidence) {
		return c, fmt.Errorf("work entry differs from validated classification")
	}
	var issue docpacket.Issue
	if e = json.Unmarshal(packet.Issue, &issue); e != nil {
		return c, e
	}
	sortPR := func(prs []docpacket.PR) []docpacket.PR {
		p := append([]docpacket.PR{}, prs...)
		sort.Slice(p, func(i, j int) bool { return p[i].Number < p[j].Number })
		return p
	}
	if issue.Number != plan.IssueNumber || issue.Title != plan.IssueTitle || Digest(issue.Body) != plan.IssueBodyHash || !reflect.DeepEqual(sortPR(issue.PRs), sortPR(plan.ImplementingPRs)) {
		return c, fmt.Errorf("implementing changes differ from publication plan")
	}
	head, e := gitRead(ctx, sourcePath, "rev-parse", "HEAD")
	if e != nil || strings.TrimSpace(string(head)) != plan.BaseSHA {
		return c, fmt.Errorf("candidate checkout has another base")
	}
	snapshot, e := localwork.SnapshotWorkspace(ctx, sourcePath, plan.BaseSHA)
	if e != nil {
		return c, e
	}
	if snapshot.ID != c.Report.Candidate.ID || !samePaths(snapshot.ChangedPaths, c.Report.Candidate.ChangedPaths) {
		return c, fmt.Errorf("actual candidate differs from verified snapshot")
	}
	if author && entry.Route != "docs-no-change" {
		root := filepath.Join(cfg.OutputDirectory, "evidence", fmt.Sprintf("step-0001-%s", doccycle.AuthorStage))
		if e := doccycle.ValidateAuthorCompletion(c.Report, freshEvidence(root)); e != nil {
			return c, e
		}
	}
	doc := c.Report.Documentation
	if doc == nil || doccycle.ValidateDocumentation(doc) != nil || len(doc.Claims) == 0 {
		return c, fmt.Errorf("missing documentation conclusion")
	}
	for _, claims := range [][]workcycle.DocClaim{doc.Claims, doc.SeparateFindings} {
		for _, cl := range claims {
			if !evidence(cl.SourceEvidence) || !evidence(cl.DocumentationEvidence) {
				return c, fmt.Errorf("claim evidence is not bound to receipt")
			}
		}
	}
	if c.Report.Status == "docs-no-change" {
		if doc.Decision != "no_change" || doc.IssueComment == "" || len(snapshot.ChangedPaths) > 0 {
			return c, fmt.Errorf("no-change completion contains changes")
		}
		if entry.Route == "docs-no-change" {
			if cfg.ResumeFrom != "" || len(c.Report.Observations) != 0 || entry.Decision == nil || !reflect.DeepEqual(entry.Decision, doc) || routing.Recommendation.Action != "no_change" || routing.Recommendation.Reason != doc.Reason || routing.Recommendation.Attribution != "unchanged" {
				return c, fmt.Errorf("automatic no-change report differs from the controller decision")
			}
		} else if !author {
			if entry.Decision != nil || len(c.Report.Observations) != 1 || (entry.Route != "docs-assess" && entry.Route != "docs-write") {
				return c, fmt.Errorf("no-change completion requires its native observation")
			}
			observation := c.Report.Observations[0]
			declaration := observation.Declaration
			if string(observation.Stage) != entry.Route || observation.Before.ID != snapshot.ID || observation.After.ID != snapshot.ID || declaration.CandidateID != snapshot.ID || len(observation.Before.ChangedPaths) != 0 || len(observation.After.ChangedPaths) != 0 || declaration.Question != nil || len(declaration.Findings) != 0 || !reflect.DeepEqual(declaration.Documentation, doc) {
				return c, fmt.Errorf("native no-change observation does not match the unchanged candidate and conclusion")
			}
			evidenceRoot := filepath.Join(cfg.OutputDirectory, "evidence", "step-0001-"+entry.Route)
			if cfg.ResumeFrom != "" {
				if cfg.ResumeMode != "assessment" || entry.Route != "docs-assess" {
					return c, fmt.Errorf("no-change resume is not an assessment replay")
				}
				evidenceRoot = filepath.Join(filepath.Dir(cfg.ResumeFrom), "evidence", "step-0001-docs-assess")
			}
			fresh := freshEvidence(evidenceRoot)
			if !fresh(declaration.Evidence) {
				return c, fmt.Errorf("native no-change evidence was not produced by its stage")
			}
			for _, claims := range [][]workcycle.DocClaim{doc.Claims, doc.SeparateFindings} {
				for _, claim := range claims {
					if !fresh(claim.SourceEvidence) || !fresh(claim.DocumentationEvidence) {
						return c, fmt.Errorf("native no-change claim has no fresh stage evidence")
					}
				}
			}
		}
		c.Digest = Digest(c.Report)
		return c, nil
	}
	if doc.Decision != "needed" || doc.PRTitle == "" || doc.PRBody == "" || len(snapshot.ChangedPaths) == 0 {
		return c, fmt.Errorf("documentation PR lacks a complete change")
	}
	review, verify := false, false
	for _, o := range c.Report.Observations {
		if o.Stage == "docs-write" {
			review = false
			verify = false
		}
		if o.Stage == "docs-review" && o.Before.ID == snapshot.ID && o.After.ID == snapshot.ID && o.Declaration.CandidateID == snapshot.ID && len(o.Declaration.Findings) == 0 && o.Declaration.Question == nil && evidence(o.Declaration.Evidence) {
			review = true
		}
		if o.Stage == "docs-verify" && review && o.Before.ID == snapshot.ID && o.After.ID == snapshot.ID && o.Declaration.CandidateID == snapshot.ID && len(o.Declaration.Findings) == 0 && o.Declaration.Question == nil && evidence(o.Declaration.Evidence) {
			required := map[string]bool{}
			for _, check := range c.Report.Request.RequiredChecks {
				if check.ID == "" || check.Scope == "" {
					return c, fmt.Errorf("incomplete required check")
				}
				if _, duplicate := required[check.ID]; duplicate {
					return c, fmt.Errorf("duplicate required check")
				}
				required[check.ID] = false
			}
			for _, check := range o.Declaration.Checks {
				if _, ok := required[check.ID]; !ok || required[check.ID] || check.Outcome != "pass" || !evidence(check.Evidence) {
					return c, fmt.Errorf("invalid final check")
				}
				required[check.ID] = true
			}
			verify = len(required) > 0
			for _, pass := range required {
				verify = verify && pass
			}
		}
	}
	if !author && (!review || !verify) {
		return c, fmt.Errorf("candidate lacks matching review and verification")
	}
	for _, name := range snapshot.ChangedPaths {
		if !strings.HasPrefix(name, "help/docs/") || filepath.Clean(name) != name || strings.ContainsAny(name, "\\\x00") {
			return c, fmt.Errorf("out-of-scope candidate path")
		}
		p := filepath.Join(sourcePath, name)
		b, e := regularFile(sourcePath, p)
		if os.IsNotExist(e) {
			c.Files = append(c.Files, CandidateFile{Path: name, Deleted: true})
			continue
		}
		if e != nil {
			return c, e
		}
		saved, e := regularFile(filepath.Join(runDir, "candidate-files"), filepath.Join(runDir, "candidate-files", name))
		if e != nil || !reflect.DeepEqual(b, saved) {
			return c, fmt.Errorf("candidate copy differs for %s", name)
		}
		c.Files = append(c.Files, CandidateFile{Path: name, Content: b})
	}
	c.Digest = Digest(struct {
		Candidate string
		Files     []CandidateFile
	}{snapshot.ID, c.Files})
	return c, nil
}
func samePaths(a, b []string) bool {
	a = append([]string{}, a...)
	b = append([]string{}, b...)
	sort.Strings(a)
	sort.Strings(b)
	return reflect.DeepEqual(a, b)
}
