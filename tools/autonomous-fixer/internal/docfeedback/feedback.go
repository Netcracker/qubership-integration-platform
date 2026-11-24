package docfeedback

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"reflect"
	"sort"
	"strings"
	"time"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/doctriage"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/jev"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcontext"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

const Version = "docs-feedback-v2"

type Reference struct {
	Path   string `json:"path"`
	SHA256 string `json:"sha256,omitempty"`
}
type Adjudication struct {
	Kind      string      `json:"kind"`
	Actor     string      `json:"actor"`
	ActorType string      `json:"actor_type"`
	Basis     string      `json:"basis"`
	Evidence  []Reference `json:"evidence"`
	Timestamp time.Time   `json:"timestamp"`
}
type Prediction struct {
	Mode             string                   `json:"mode"`
	RequestAttempted bool                     `json:"request_attempted"`
	PolicyVersion    string                   `json:"policy_version"`
	RequestedModel   string                   `json:"requested_model"`
	Response         *jev.Response            `json:"response,omitempty"`
	Error            string                   `json:"error,omitempty"`
	Recommendation   doctriage.Recommendation `json:"recommendation"`
}
type Outcome struct {
	Status           string   `json:"status"`
	Decision         string   `json:"decision"`
	Reason           string   `json:"reason"`
	Summary          string   `json:"summary"`
	Origin           string   `json:"origin"`
	Stages           []string `json:"stages"`
	SeparateFindings int      `json:"separate_findings"`
}
type Record struct {
	Repository            string               `json:"repository"`
	Issue                 int                  `json:"issue"`
	BaseSHA               string               `json:"base_sha"`
	ImplementationHeadSHA string               `json:"implementation_head_sha"`
	Version               string               `json:"version"`
	ID                    string               `json:"id"`
	CaseID                string               `json:"case_id"`
	ObservationID         string               `json:"observation_id"`
	Supersedes            string               `json:"supersedes,omitempty"`
	TaskID                string               `json:"task_id"`
	SourceSHA             string               `json:"source_sha"`
	DocsSHA               string               `json:"docs_sha"`
	PacketSHA256          string               `json:"packet_sha256"`
	RequestSHA256         string               `json:"request_sha256"`
	ContextSHA256         string               `json:"context_sha256"`
	CandidateID           string               `json:"candidate_id"`
	Artifacts             map[string]Reference `json:"artifacts"`
	Prediction            Prediction           `json:"prediction"`
	Routing               doctriage.Routing    `json:"routing"`
	Observed              Outcome              `json:"observed"`
	AssessmentStatus      string               `json:"assessment_status"`
	Adjudication          *Adjudication        `json:"adjudication,omitempty"`
}
type proof struct {
	Origin        string            `json:"origin"`
	TaskID        string            `json:"task_id"`
	SourceSHA     string            `json:"source_sha"`
	ContextSHA256 string            `json:"context_sha256"`
	Packet        doctriage.Packet  `json:"packet"`
	Report        doctriage.Report  `json:"report"`
	Routing       doctriage.Routing `json:"routing"`
}
type runConfig struct {
	TaskKind            string           `json:"task_kind"`
	TaskID              string           `json:"task_id"`
	OutputDirectory     string           `json:"output_directory"`
	ResumeFrom          string           `json:"resume_from"`
	DocumentationTriage *json.RawMessage `json:"documentation_triage"`
}

type receipt struct {
	ContextSHA256 string            `json:"context_sha256"`
	ReportSHA256  string            `json:"report_sha256"`
	CandidateID   string            `json:"candidate_id"`
	Evidence      map[string]string `json:"evidence_sha256"`
	PriorEvidence map[string]string `json:"prior_evidence_sha256,omitempty"`
}

func digest(data []byte) string { h := sha256.Sum256(data); return hex.EncodeToString(h[:]) }
func file(path string) ([]byte, error) {
	info, err := os.Lstat(path)
	if err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() {
		return nil, fmt.Errorf("not a regular evidence file: %s", path)
	}
	return os.ReadFile(path)
}
func read(path string, v any) (Reference, error) {
	b, e := file(path)
	if e != nil {
		return Reference{}, e
	}
	if e = json.Unmarshal(b, v); e != nil {
		return Reference{}, fmt.Errorf("decode %s: %w", path, e)
	}
	return Reference{path, digest(b)}, nil
}
func check(path, expected string) error {
	b, e := file(path)
	if e != nil {
		return e
	}
	if expected == "" || digest(b) != expected {
		return fmt.Errorf("evidence hash mismatch: %s", path)
	}
	return nil
}
func observationID(r Record) string {
	requestID := ""
	if r.Prediction.Response != nil {
		requestID = r.Prediction.Response.ID
	}
	return digest([]byte(r.CaseID + "\n" + requestID + "\n" + r.Artifacts["report.json"].SHA256))
}
func identify(r Record) Record {
	r.ID = ""
	b, _ := json.Marshal(r)
	r.ID = digest(b)
	return r
}
func clone[T any](v T) T { b, _ := json.Marshal(v); var out T; _ = json.Unmarshal(b, &out); return out }

func archivePath(recordedRoot, archiveRoot, reference string) (string, error) {
	if !filepath.IsAbs(reference) || filepath.Clean(reference) != reference {
		return "", fmt.Errorf("invalid recorded evidence path")
	}
	relative, e := filepath.Rel(recordedRoot, reference)
	if e != nil || relative == ".." || strings.HasPrefix(relative, ".."+string(filepath.Separator)) {
		return "", fmt.Errorf("evidence path is outside the recorded run: %s", reference)
	}
	actual := filepath.Join(archiveRoot, relative)
	resolved, e := filepath.EvalSymlinks(actual)
	if e != nil {
		return "", e
	}
	if resolved != actual {
		return "", fmt.Errorf("archive evidence must not use symlinks: %s", reference)
	}
	return actual, nil
}

func Import(directory string, judgment *Adjudication) (Record, error) {
	var r Record
	root, e := filepath.Abs(directory)
	if e != nil {
		return r, e
	}
	root, e = filepath.EvalSymlinks(root)
	if e != nil {
		return r, e
	}
	r.Artifacts = map[string]Reference{}
	load := func(name string, v any) error {
		path, e := archivePath(root, root, filepath.Join(root, name))
		if e != nil {
			return e
		}
		ref, e := read(path, v)
		if e == nil {
			r.Artifacts[name] = ref
		}
		return e
	}
	var cfg runConfig
	var result workcycle.Report
	var context workcontext.Packet
	var done receipt
	var p proof
	var route doctriage.Routing
	var manifest map[string]string
	for _, item := range []struct {
		name  string
		value any
	}{{"config.json", &cfg}, {"report.json", &result}, {"context.json", &context}, {"receipt.json", &done}, {"evidence/documentation-routing.json", &p}, {"routing.json", &route}, {"controller-evidence-sha256.json", &manifest}} {
		if e = load(item.name, item.value); e != nil {
			return r, e
		}
	}
	if cfg.TaskKind != "documentation" || cfg.DocumentationTriage == nil || result.Request.Kind != "documentation" || result.Request.TaskID != cfg.TaskID || cfg.TaskID == "" {
		return r, fmt.Errorf("feedback requires a routed documentation task")
	}
	if result.Status != "docs-no-change" && result.Status != "docs-ready-for-pr" || result.Error != "" || result.Question != nil || result.Documentation == nil {
		return r, fmt.Errorf("feedback import requires a completed documentation result and receipt")
	}
	if done.ReportSHA256 != r.Artifacts["report.json"].SHA256 || done.ContextSHA256 != context.SHA256 || done.CandidateID == "" || done.CandidateID != result.Candidate.ID {
		return r, fmt.Errorf("completion receipt differs from report or context")
	}
	contextIdentity := context
	contextIdentity.SHA256 = ""
	contextBytes, _ := json.Marshal(contextIdentity)
	if digest(contextBytes) != context.SHA256 || result.Request.Context != context.Text {
		return r, fmt.Errorf("stored context is inconsistent")
	}
	recordedRoot := cfg.OutputDirectory
	if recordedRoot == "" {
		recordedRoot = root
	}
	if !filepath.IsAbs(recordedRoot) || filepath.Clean(recordedRoot) != recordedRoot {
		return r, fmt.Errorf("recorded output directory must be absolute and normalized")
	}
	if root != recordedRoot && (cfg.ResumeFrom != "" || len(done.PriorEvidence) > 0) {
		return r, fmt.Errorf("relocated resume archives require an explicit prior-archive mapping")
	}
	proofPath := filepath.Join(recordedRoot, "evidence", "documentation-routing.json")
	proofHash := r.Artifacts["evidence/documentation-routing.json"].SHA256
	if manifest[proofPath] != proofHash || done.Evidence[proofPath] != proofHash {
		return r, fmt.Errorf("routing proof is absent from the verified completion evidence")
	}
	for reference, h := range done.Evidence {
		path, e := archivePath(recordedRoot, root, reference)
		if e != nil {
			return r, e
		}
		if e = check(path, h); e != nil {
			return r, e
		}
	}
	if len(done.PriorEvidence) > 0 {
		if !filepath.IsAbs(cfg.ResumeFrom) {
			return r, fmt.Errorf("prior evidence requires an explicit resume source")
		}
		previousRoot := filepath.Dir(cfg.ResumeFrom)
		for reference, h := range done.PriorEvidence {
			path, e := archivePath(previousRoot, previousRoot, reference)
			if e != nil {
				return r, e
			}
			if e = check(path, h); e != nil {
				return r, e
			}
		}
	}
	entry := result.Request.DocumentationEntry
	if entry == nil || len(entry.Evidence) != 1 || route.Route != entry.Route || p.Origin != "jev-controller-routing" || p.TaskID != cfg.TaskID || p.ContextSHA256 != context.SHA256 || p.SourceSHA == "" || p.Packet.DocsSHA != p.SourceSHA || !reflect.DeepEqual(p.Routing, route) {
		return r, fmt.Errorf("routing proof differs from task identity")
	}
	if entry.Evidence[0] != proofPath {
		if cfg.ResumeFrom == "" || done.PriorEvidence[entry.Evidence[0]] != proofHash {
			return r, fmt.Errorf("resumed routing proof is not retained in the receipt")
		}
	}
	var issue struct {
		Number int `json:"number"`
	}
	if e = json.Unmarshal(p.Packet.Issue, &issue); e != nil || issue.Number <= 0 || cfg.TaskID != fmt.Sprintf("%s#%d:documentation", p.Packet.Repository, issue.Number) {
		return r, fmt.Errorf("classifier packet belongs to another issue")
	}
	if e = doctriage.ValidateReport(p.Packet, p.Report); e != nil {
		return r, e
	}
	calculated, e := doctriage.ReplayRoute(p.Packet, p.Report, route.Policy, route.Version)
	if e != nil {
		return r, e
	}
	if !reflect.DeepEqual(calculated, route) {
		return r, fmt.Errorf("recorded route differs from classifier evidence")
	}
	r.Repository = p.Packet.Repository
	r.Issue = issue.Number
	r.BaseSHA = p.Packet.BaseSHA
	r.ImplementationHeadSHA = p.Packet.HeadSHA
	r.Version = Version
	r.TaskID = cfg.TaskID
	r.SourceSHA = p.SourceSHA
	r.DocsSHA = p.Packet.DocsSHA
	r.PacketSHA256 = p.Report.PacketSHA256
	r.RequestSHA256 = p.Report.InputSHA256
	r.ContextSHA256 = context.SHA256
	r.CandidateID = result.Candidate.ID
	r.CaseID = digest([]byte(r.TaskID + "\n" + r.PacketSHA256 + "\n" + r.RequestSHA256))
	r.Prediction = Prediction{Mode: p.Report.Mode, RequestAttempted: p.Report.RequestAttempted || p.Report.Response != nil || p.Report.Mode == "shadow" && p.Report.Error != "", PolicyVersion: p.Report.PolicyVersion, RequestedModel: p.Report.RequestedModel, Response: clone(p.Report.Response), Error: p.Report.Error, Recommendation: clone(route.Recommendation)}
	r.Routing = clone(route)
	origin := "agent"
	if len(result.Observations) == 0 {
		origin = "classifier"
	}
	r.Observed = Outcome{Status: result.Status, Decision: result.Documentation.Decision, Reason: result.Documentation.Reason, Summary: result.Documentation.Summary, Origin: origin, Stages: []string{}, SeparateFindings: len(result.Documentation.SeparateFindings)}
	for _, o := range result.Observations {
		r.Observed.Stages = append(r.Observed.Stages, string(o.Stage))
	}
	r.AssessmentStatus = "pending"
	r.ObservationID = observationID(r)
	r = identify(r)
	if judgment != nil {
		return Adjudicate(r, *judgment)
	}
	return r, nil
}

func validateJudgment(a Adjudication) error {
	if a.ActorType != "human" && a.ActorType != "model" {
		return fmt.Errorf("actor_type must be human or model")
	}
	if strings.TrimSpace(a.Actor) == "" || strings.TrimSpace(a.Basis) == "" || len(a.Evidence) == 0 || a.Timestamp.IsZero() {
		return fmt.Errorf("adjudication requires actor, basis, evidence, and timestamp")
	}
	switch a.Kind {
	case "missed_needed_change", "unnecessary_change", "context_gap", "editorial_disagreement", "confirmed_correct":
	default:
		return fmt.Errorf("unknown adjudication kind %q", a.Kind)
	}
	for _, ref := range a.Evidence {
		if filepath.IsAbs(ref.Path) {
			if err := check(ref.Path, ref.SHA256); err != nil {
				return err
			}
			continue
		}
		u, e := url.Parse(ref.Path)
		if e != nil || u.Scheme != "https" || u.Host == "" || u.User != nil || ref.SHA256 != "" {
			return fmt.Errorf("adjudication evidence requires an existing hashed file or an HTTPS reference")
		}
	}
	return nil
}
func Validate(r Record) error {
	if r.Version != Version || r.ID == "" || identify(r).ID != r.ID || r.CaseID != digest([]byte(r.TaskID+"\n"+r.PacketSHA256+"\n"+r.RequestSHA256)) || r.ObservationID == "" || r.Artifacts["report.json"].SHA256 == "" || r.ObservationID != observationID(r) {
		return fmt.Errorf("feedback identity or content hash is invalid")
	}
	if r.AssessmentStatus == "pending" && r.Adjudication == nil {
		return nil
	}
	if r.AssessmentStatus != "adjudicated" || r.Adjudication == nil {
		return fmt.Errorf("feedback assessment state is inconsistent")
	}
	// Imported evidence may move after export; identity validation does not reread it.
	a := r.Adjudication
	if a.Actor == "" || a.Basis == "" || a.Timestamp.IsZero() || len(a.Evidence) == 0 || a.ActorType != "human" && a.ActorType != "model" {
		return fmt.Errorf("incomplete archived adjudication")
	}
	switch a.Kind {
	case "missed_needed_change", "unnecessary_change", "context_gap", "editorial_disagreement", "confirmed_correct":
		return nil
	}
	return fmt.Errorf("unknown archived adjudication kind")
}
func Adjudicate(record Record, judgment Adjudication) (Record, error) {
	if e := Validate(record); e != nil {
		return Record{}, e
	}
	if e := validateJudgment(judgment); e != nil {
		return Record{}, e
	}
	raw := ""
	if record.Prediction.Response != nil {
		raw = record.Prediction.Response.Answers["documentation_action"].Choice
	}
	if judgment.Kind == "confirmed_correct" && raw == "" {
		return Record{}, fmt.Errorf("there is no classifier action to confirm")
	}
	if judgment.Kind == "missed_needed_change" && raw != "no_change" || judgment.Kind == "unnecessary_change" && raw != "needed" {
		return Record{}, fmt.Errorf("error label contradicts the recorded classifier action")
	}
	r := clone(record)
	r.Supersedes = record.ID
	r.AssessmentStatus = "adjudicated"
	r.Adjudication = &judgment
	return identify(r), nil
}
func Evidence(reference string) (Reference, error) {
	if filepath.IsAbs(reference) {
		b, e := file(reference)
		if e != nil {
			return Reference{}, e
		}
		return Reference{reference, digest(b)}, nil
	}
	ref := Reference{Path: reference}
	a := Adjudication{Kind: "context_gap", Actor: "validation", ActorType: "human", Basis: "reference", Evidence: []Reference{ref}, Timestamp: time.Unix(1, 0)}
	if e := validateJudgment(a); e != nil {
		return Reference{}, e
	}
	return ref, nil
}
func WriteNew(path string, r Record) error {
	if e := Validate(r); e != nil {
		return e
	}
	b, e := json.MarshalIndent(r, "", "  ")
	if e != nil {
		return e
	}
	f, e := os.OpenFile(path, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if e != nil {
		return e
	}
	defer f.Close()
	_, e = f.Write(append(b, '\n'))
	return e
}

type Group struct {
	RequestedModel         string         `json:"requested_model"`
	ServedModel            string         `json:"served_model"`
	ClassifierPolicy       string         `json:"classifier_policy"`
	RoutingVersion         string         `json:"routing_version"`
	RoutingPolicySHA256    string         `json:"routing_policy_sha256"`
	Reason                 string         `json:"reason"`
	Cases                  int            `json:"cases"`
	SkippedClassifications int            `json:"skipped_classifications"`
	Observations           int            `json:"observations"`
	Pending                int            `json:"pending"`
	ObservedOutcomes       map[string]int `json:"observed_outcomes"`
	HumanDeclaredJudgments map[string]int `json:"human_declared_judgments"`
	ModelDeclaredJudgments map[string]int `json:"model_declared_judgments"`
}
type Summary struct {
	DatasetCases int      `json:"dataset_cases"`
	Observations int      `json:"observations"`
	Version      string   `json:"version"`
	Groups       []Group  `json:"groups"`
	Notes        []string `json:"notes"`
}

func Summarize(records []Record) (Summary, error) {
	result := Summary{Version: Version, Groups: []Group{}, Notes: []string{"Agent outcomes are observations, not correctness labels.", "Repeated classifier requests and worker reports are distinct observations of a dataset case. Judgment and pending counts refer to observations.", "Adjudication actors are caller-declared; this local tool does not authenticate GitHub identities.", "Judgments describe classifier predictions; a routed investigation may have prevented an incorrect downstream action.", "Unassessed cases are excluded from judgment counts. No overall accuracy or savings estimate is inferred."}}
	byID := map[string]Record{}
	superseded := map[string]bool{}
	for _, r := range records {
		if e := Validate(r); e != nil {
			return result, e
		}
		byID[r.ID] = r
	}
	for _, r := range byID {
		if r.Supersedes != "" {
			if prev, ok := byID[r.Supersedes]; ok {
				if prev.CaseID != r.CaseID || prev.ObservationID != r.ObservationID {
					return result, fmt.Errorf("feedback supersedes another observation")
				}
				superseded[r.Supersedes] = true
			}
		}
	}
	cases := map[string]bool{}
	observations := map[string]bool{}
	groupCases := map[string]map[string]bool{}
	groups := map[string]*Group{}
	for id, r := range byID {
		if superseded[id] {
			continue
		}
		if observations[r.ObservationID] {
			return result, fmt.Errorf("multiple current feedback records for observation %s", r.ObservationID)
		}
		observations[r.ObservationID] = true
		cases[r.CaseID] = true
		b, _ := json.Marshal(r.Routing.Policy)
		ph := digest(b)
		served := ""
		if r.Prediction.Response != nil {
			served = r.Prediction.Response.Model
		}
		key := r.Prediction.RequestedModel + "\n" + served + "\n" + r.Prediction.PolicyVersion + "\n" + r.Routing.Version + "\n" + ph + "\n" + r.Prediction.Recommendation.Reason
		g := groups[key]
		if g == nil {
			g = &Group{RequestedModel: r.Prediction.RequestedModel, ServedModel: served, ClassifierPolicy: r.Prediction.PolicyVersion, RoutingVersion: r.Routing.Version, RoutingPolicySHA256: ph, Reason: r.Prediction.Recommendation.Reason, ObservedOutcomes: map[string]int{}, HumanDeclaredJudgments: map[string]int{}, ModelDeclaredJudgments: map[string]int{}}
			groups[key] = g
		}
		if groupCases[key] == nil {
			groupCases[key] = map[string]bool{}
		}
		if !groupCases[key][r.CaseID] {
			groupCases[key][r.CaseID] = true
			g.Cases++
		}
		g.Observations++
		if r.Prediction.Mode == "preflight" && !r.Prediction.RequestAttempted {
			g.SkippedClassifications++
		}
		g.ObservedOutcomes[r.Observed.Origin+":"+r.Observed.Status]++
		if r.Adjudication == nil {
			g.Pending++
		} else if r.Adjudication.ActorType == "human" {
			g.HumanDeclaredJudgments[r.Adjudication.Kind]++
		} else {
			g.ModelDeclaredJudgments[r.Adjudication.Kind]++
		}
	}
	result.DatasetCases = len(cases)
	result.Observations = len(observations)
	keys := []string{}
	for k := range groups {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	for _, k := range keys {
		result.Groups = append(result.Groups, *groups[k])
	}
	return result, nil
}
