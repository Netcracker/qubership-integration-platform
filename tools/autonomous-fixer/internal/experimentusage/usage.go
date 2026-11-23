package experimentusage

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"math"
	"os"
	"path/filepath"
	"sort"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentcost"
)

type Report struct {
	Source            string                `json:"source"`
	Complete          bool                  `json:"complete"`
	Tokens            experimentcost.Usage  `json:"tokens"`
	Models            []ModelUsage          `json:"models"`
	RequestCount      int                   `json:"request_count"`
	SessionCount      int                   `json:"session_count"`
	Warnings          []string              `json:"warnings"`
	UnattributedUsage *experimentcost.Usage `json:"unattributed_usage"`
	Session           *SessionLink          `json:"session,omitempty"`
	nativeSessions    map[string][]nativeRequest
	nativeTotals      map[string]experimentcost.Usage
}

type nativeRequest struct {
	Identity string
	Model    string
	Usage    experimentcost.Usage
}
type ModelUsage struct {
	Model    string                 `json:"model"`
	Requests []experimentcost.Usage `json:"requests"`
	Tokens   experimentcost.Usage   `json:"tokens"`
}

func Read(runtime, expectedModel, executionDir string) (Report, error) {
	r, err := readNative(runtime, expectedModel, executionDir)
	if err != nil {
		return r, err
	}
	return continuedUsage(runtime, expectedModel, executionDir, r)
}

func readNative(runtime, expectedModel, executionDir string) (Report, error) {
	switch runtime {
	case "codex":
		return readCodex(expectedModel, executionDir)
	case "opencode":
		return readOpenCode(expectedModel, executionDir)
	default:
		return Report{}, fmt.Errorf("unsupported runtime %q", runtime)
	}
}
func report(source string) Report {
	return Report{Source: source, Complete: true, Models: []ModelUsage{}, Warnings: []string{}}
}
func (r *Report) warn(s string) { r.Complete = false; r.Warnings = append(r.Warnings, s) }
func ptr(n int64) *int64        { return &n }
func zero() experimentcost.Usage {
	return experimentcost.Usage{Input: ptr(0), CachedInput: ptr(0), CacheWrite: ptr(0), Output: ptr(0), Reasoning: ptr(0)}
}
func fields(u experimentcost.Usage) []*int64 {
	return []*int64{u.Input, u.CachedInput, u.CacheWrite, u.Output, u.Reasoning}
}
func fromFields(f []*int64) experimentcost.Usage {
	return experimentcost.Usage{Input: f[0], CachedInput: f[1], CacheWrite: f[2], Output: f[3], Reasoning: f[4]}
}
func sum(a, b experimentcost.Usage) experimentcost.Usage {
	x, y := fields(a), fields(b)
	z := make([]*int64, 5)
	for i := range x {
		if x[i] != nil && y[i] != nil && *x[i] >= 0 && *y[i] >= 0 && *x[i] <= math.MaxInt64-*y[i] {
			z[i] = ptr(*x[i] + *y[i])
		}
	}
	return fromFields(z)
}
func difference(a, b experimentcost.Usage) (experimentcost.Usage, bool) {
	x, y := fields(a), fields(b)
	z := make([]*int64, 5)
	valid := true
	for i := range x {
		if x[i] == nil || y[i] == nil || *x[i] < 0 || *y[i] < 0 || *x[i] < *y[i] {
			valid = false
		} else {
			z[i] = ptr(*x[i] - *y[i])
		}
	}
	return fromFields(z), valid
}
func equal(a, b experimentcost.Usage) bool {
	x, y := fields(a), fields(b)
	for i := range x {
		if (x[i] == nil) != (y[i] == nil) || (x[i] != nil && *x[i] != *y[i]) {
			return false
		}
	}
	return true
}
func nonzero(u experimentcost.Usage) bool {
	for _, v := range fields(u) {
		if v == nil || *v != 0 {
			return true
		}
	}
	return false
}
func (r *Report) addRequest(model string, u experimentcost.Usage) {
	r.RequestCount++
	if !validUsage(u) {
		r.warn("request has unknown or invalid token counts")
	}
	for i := range r.Models {
		if r.Models[i].Model == model {
			m := &r.Models[i]
			m.Requests = append(m.Requests, u)
			m.Tokens = sum(m.Tokens, u)
			return
		}
	}
	r.Models = append(r.Models, ModelUsage{Model: model, Requests: []experimentcost.Usage{u}, Tokens: sum(zero(), u)})
}
func (r *Report) finish() {
	if r.Complete && !validUsage(r.Tokens) {
		r.warn("aggregate token counts are unknown or invalid")
	}
	sort.Slice(r.Models, func(i, j int) bool { return r.Models[i].Model < r.Models[j].Model })
}
func jsonLines(path string, visit func(map[string]json.RawMessage) error) error {
	f, err := os.Open(path)
	if err != nil {
		return err
	}
	defer f.Close()
	reader := bufio.NewReader(f)
	for line := 1; ; line++ {
		raw, readErr := reader.ReadBytes('\n')
		if len(raw) > 0 {
			var event map[string]json.RawMessage
			if err := json.Unmarshal(raw, &event); err != nil {
				return fmt.Errorf("%s line %d: %w", path, line, err)
			}
			if err := visit(event); err != nil {
				return err
			}
		}
		if readErr == io.EOF {
			return nil
		}
		if readErr != nil {
			return readErr
		}
	}
}
func decode[T any](raw json.RawMessage) (T, error) {
	var value T
	err := json.Unmarshal(raw, &value)
	return value, err
}
func text(raw json.RawMessage) string { value, _ := decode[string](raw); return value }
func stdoutPath(dir string) string    { return filepath.Join(dir, "stdout.jsonl") }

func validUsage(u experimentcost.Usage) bool {
	for _, v := range fields(u) {
		if v == nil || *v < 0 {
			return false
		}
	}
	return *u.CachedInput <= *u.Input && *u.CacheWrite <= *u.Input-*u.CachedInput && *u.Reasoning <= *u.Output
}
