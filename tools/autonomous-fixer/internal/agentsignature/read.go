package agentsignature

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/doctriage"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentruntime"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentusage"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/jev"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/openrouterusage"
)

type runConfig struct {
	Hosted     bool                      `json:"hosted"`
	Profile    experimentruntime.Profile `json:"profile"`
	TaskID     string                    `json:"task_id"`
	TaskKind   string                    `json:"task_kind"`
	ResumeFrom string                    `json:"resume_from"`
}

type runSource struct {
	dir    string
	config runConfig
}

var stageName = regexp.MustCompile(`^step-[0-9]{4,}-[a-z-]+$`)
var helperName = regexp.MustCompile(`^helper-[0-9]{4,}$`)

func ReadRun(dir string) (Signature, error) {
	dir, err := filepath.Abs(dir)
	if err != nil {
		return Signature{}, err
	}
	var config runConfig
	if err := readFile(dir, "config.json", &config); err != nil {
		if os.IsNotExist(err) {
			return Signature{}, nil
		}
		return Signature{}, err
	}
	runs, complete := priorRuns(runSource{dir, config})
	if config.Hosted {
		return readHosted(dir, config)
	}
	return readLocal(runs, complete)
}

func readHosted(dir string, config runConfig) (Signature, error) {
	stages, err := stagePaths(dir)
	if err != nil {
		return Signature{}, err
	}
	var author Identity
	for _, stage := range stages {
		if isAuthor(stage) {
			id, err := ReadIdentity(stage, config.Profile.Runtime)
			if err != nil {
				return Signature{}, err
			}
			if id.Model == "" {
				if err := readFile(stage, IdentityFilename, &id); err != nil && !os.IsNotExist(err) {
					return Signature{}, err
				}
				id = cleanIdentity(id)
			}
			if id.Model != "" {
				author = id
			}
		}
	}
	var report openrouterusage.Report
	s := Signature{Model: author.Model, Effort: author.Effort}
	if err := readFile(dir, openrouterusage.Filename, &report); err == nil {
		s = signatureFromOpenRouter(report, config.Profile.Model, author.Model, author.Effort)
	} else if !os.IsNotExist(err) {
		return Signature{}, err
	}
	if config.TaskKind == "documentation" {
		response, attempted, err := readDocumentationJev(dir)
		if err != nil {
			return Signature{}, err
		}
		if attempted && response != nil && len(stages) == 0 {
			return FromJev(*response), nil
		}
		if attempted && s.Model != "" {
			s.Input, s.Cached = nil, nil
			if response == nil {
				s.Output, s.USD = nil, nil
			} else {
				decision := FromJev(*response)
				s.Output = add(s.Output, decision.Output)
				s.USD = addUSD(s.USD, decision.USD)
			}
		}
	}
	return s, nil
}

func readDocumentationJev(dir string) (*jev.Response, bool, error) {
	var route struct {
		Packet doctriage.Packet
		Report doctriage.Report
	}
	if err := readFile(dir, "evidence/documentation-routing.json", &route); err != nil {
		if os.IsNotExist(err) {
			return nil, false, nil
		}
		return nil, false, err
	}
	attempted := route.Report.RequestAttempted || route.Report.Response != nil || route.Report.Mode == "shadow" && route.Report.Error != ""
	if !attempted || route.Report.Response == nil || doctriage.ValidateReport(route.Packet, route.Report) != nil {
		return nil, attempted, nil
	}
	return route.Report.Response, true, nil
}

func readLocal(runs []runSource, complete bool) (Signature, error) {
	s := Signature{Input: new(int64), Cached: new(int64), Output: new(int64), USD: new(float64)}
	var native bool
	var jevAttempted bool
	var missingJev bool
	var responses []jev.Response
	sqlSessions := make(map[string]nativeSession)
	for _, run := range runs {
		stages, err := stagePaths(run.dir)
		if err != nil {
			return Signature{}, err
		}
		for _, stage := range stages {
			native = true
			profile := run.config.Profile
			if err := readFile(stage, "profile.json", &profile); err != nil && !os.IsNotExist(err) {
				return Signature{}, err
			}
			id, sessions, err := readStage(stage, profile.Runtime)
			if err != nil {
				return Signature{}, err
			}
			if isAuthor(stage) && id.Model != "" {
				s.Model, s.Effort = id.Model, id.Effort
			}
			if profile.Runtime == "opencode" && len(sessions) != 0 {
				for _, session := range sessions {
					previous := sqlSessions[session.ID]
					sqlSessions[session.ID] = session.cumulative(previous)
				}
				continue
			}
			s.USD = nil
			if profile.Runtime != "codex" {
				s.Input, s.Cached, s.Output = nil, nil, nil
				continue
			}
			usage, err := experimentusage.Read(profile.Runtime, "", stage)
			if err != nil {
				return Signature{}, err
			}
			if !usage.Complete {
				s.Input, s.Cached, s.Output = nil, nil, nil
				continue
			}
			s.Input = add(s.Input, subtract(usage.Tokens.Input, usage.Tokens.CachedInput))
			s.Cached = add(s.Cached, usage.Tokens.CachedInput)
			s.Output = add(s.Output, usage.Tokens.Output)
		}
		response, attempted, err := readDocumentationJev(run.dir)
		if err != nil {
			return Signature{}, err
		}
		if attempted {
			jevAttempted = true
			if response == nil {
				missingJev = true
			} else {
				responses = append(responses, *response)
			}
		}
	}
	var sqlIDs []string
	for id := range sqlSessions {
		sqlIDs = append(sqlIDs, id)
	}
	sort.Strings(sqlIDs)
	for _, id := range sqlIDs {
		row := sqlSessions[id]
		s.Input = add(s.Input, add(row.Input, row.Write))
		s.Cached = add(s.Cached, row.Cached)
		s.Output = add(s.Output, add(row.Output, row.Reasoning))
		s.USD = addUSD(s.USD, row.Cost)
	}
	if jevAttempted {
		decision := FromJev(responses...)
		s.Input, s.Cached = nil, nil
		s.Output = add(s.Output, decision.Output)
		s.USD = addUSD(s.USD, decision.USD)
		if !native {
			s.Model = decision.Model
		}
		if missingJev {
			s.Output, s.USD = nil, nil
		}
	}
	if !complete {
		s.Input, s.Cached, s.Output, s.USD = nil, nil, nil, nil
	}
	if s.Model == "" {
		return Signature{}, nil
	}
	return s, nil
}

func priorRuns(current runSource) ([]runSource, bool) {
	runs := []runSource{current}
	seen := map[string]bool{current.dir: true}
	for current.config.ResumeFrom != "" {
		report := current.config.ResumeFrom
		prior := filepath.Dir(report)
		if !filepath.IsAbs(report) || filepath.Base(report) != "report.json" ||
			filepath.Dir(prior) != filepath.Dir(current.dir) || seen[prior] {
			return runs, false
		}
		var config runConfig
		if _, err := checkedPath(prior, "report.json"); err != nil {
			return runs, false
		}
		if err := readFile(prior, "config.json", &config); err != nil || config.TaskID == "" || config.TaskID != current.config.TaskID {
			return runs, false
		}
		seen[prior] = true
		current = runSource{prior, config}
		runs = append([]runSource{current}, runs...)
	}
	return runs, true
}

func stagePaths(dir string) ([]string, error) {
	files, err := os.ReadDir(dir)
	if err != nil {
		return nil, err
	}
	var stages []string
	for _, entry := range files {
		if !stageName.MatchString(entry.Name()) {
			continue
		}
		step, err := checkedPath(dir, entry.Name())
		if err != nil || !entry.IsDir() {
			return nil, fmt.Errorf("invalid native stage %q: %v", entry.Name(), err)
		}
		stages = append(stages, step)
		children, err := os.ReadDir(step)
		if err != nil {
			return nil, err
		}
		for _, child := range children {
			if child.Name() != "declaration-correction" && !helperName.MatchString(child.Name()) {
				continue
			}
			path, err := checkedPath(step, child.Name())
			if err != nil || !child.IsDir() {
				return nil, fmt.Errorf("invalid nested native stage %q: %v", child.Name(), err)
			}
			stages = append(stages, path)
		}
	}
	return stages, nil
}

func isAuthor(dir string) bool {
	name := filepath.Base(dir)
	for _, suffix := range []string{"-author", "-implement", "-contract", "-docs-assess", "-docs-write"} {
		if strings.HasSuffix(name, suffix) {
			return true
		}
	}
	return false
}

func checkedPath(dir, rel string) (string, error) {
	if !filepath.IsLocal(rel) {
		return "", fmt.Errorf("invalid signature evidence path %q", rel)
	}
	path := dir
	for _, name := range append([]string{""}, strings.Split(rel, string(filepath.Separator))...) {
		path = filepath.Join(path, name)
		info, err := os.Lstat(path)
		if err != nil {
			return "", err
		}
		if info.Mode()&os.ModeSymlink != 0 || !info.IsDir() && !info.Mode().IsRegular() {
			return "", fmt.Errorf("unsafe signature evidence path %q", rel)
		}
	}
	return path, nil
}

func readFile(dir, name string, dst any) error {
	path, err := checkedPath(dir, name)
	if err != nil {
		return err
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	if err := json.Unmarshal(data, dst); err != nil {
		return fmt.Errorf("read signature evidence %s: %w", name, err)
	}
	return nil
}
