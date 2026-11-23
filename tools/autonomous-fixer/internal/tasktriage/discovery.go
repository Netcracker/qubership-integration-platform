package tasktriage

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"regexp"
	"sort"
	"strconv"
	"strings"
)

// Discover reads committed source references; it never executes repository code.
func Discover(ctx context.Context, source string, p Packet) (Packet, error) {
	run := func(args ...string) ([]byte, error) {
		c := exec.CommandContext(ctx, "git", append([]string{"-c", "core.hooksPath=/dev/null", "-c", "core.fsmonitor=false"}, args...)...)
		c.Dir = source
		c.Env = []string{"PATH=" + os.Getenv("PATH"), "HOME=/nonexistent", "GIT_CONFIG_NOSYSTEM=1", "GIT_CONFIG_GLOBAL=/dev/null", "GIT_TERMINAL_PROMPT=0"}
		return c.Output()
	}
	revision := "HEAD"
	if p.BaseSHA != "" {
		if !regexp.MustCompile(`^[a-fA-F0-9]{40}$`).MatchString(p.BaseSHA) {
			return p, fmt.Errorf("source base must be a commit SHA")
		}
		revision = p.BaseSHA
	}
	b, e := run("rev-parse", "--verify", revision+"^{commit}")
	if e != nil {
		return p, fmt.Errorf("read source revision: %w", e)
	}
	sha := strings.TrimSpace(string(b))
	p.BaseSHA = sha
	p.SourceAvailable = true
	evidence, ignored := searchEvidence(p.Task)
	p.IgnoredSearchLines = ignored
	terms := searchIdentifiers(evidence)
	p.SearchTerms = terms
	p.SourceHints = nil
	p.SearchLimited = false
	p.ConsumerHints = nil
	if len(terms) == 0 {
		return p, nil
	}
	args := []string{"grep", "-n", "-I", "-i", "-E", "-m", "8"}
	for _, term := range terms {
		args = append(args, "-e", `(^|[^[:alnum:]_])`+identifierExpression(term)+`([^[:alnum:]_]|$)`)
	}
	args = append(args, sha, "--")
	paths := []string{}
	for path := range Modules {
		paths = append(paths, path)
	}
	sort.Strings(paths)
	args = append(args, paths...)
	b, e = run(args...)
	if e != nil {
		if ee, ok := e.(*exec.ExitError); !ok || ee.ExitCode() != 1 {
			return p, fmt.Errorf("search source references: %w", e)
		}
	}
	type hit struct {
		path, line, text string
		score            int
	}
	hits := []hit{}
	for _, line := range strings.Split(strings.TrimSpace(string(b)), "\n") {
		fields := strings.SplitN(line, ":", 4)
		if len(fields) != 4 {
			continue
		}
		score := relevance(evidence, fields[1], fields[3])
		hits = append(hits, hit{fields[1], fields[2], fields[3], score})
	}
	sort.SliceStable(hits, func(i, j int) bool { return hits[i].score > hits[j].score })
	perModule := map[string]int{}
	selectedFiles := map[string]bool{}
	for _, h := range hits {
		module := strings.SplitN(h.path, "/", 2)[0]
		if perModule[module] >= 2 || selectedFiles[h.path] {
			p.SearchLimited = true
			continue
		}
		if h.score < -6 {
			continue
		}
		text := h.text
		sourceBytes, readErr := run("show", sha+":"+h.path)
		if readErr != nil {
			return p, fmt.Errorf("read source hint %s: %w", h.path, readErr)
		}
		lines := strings.Split(string(sourceBytes), "\n")
		number, _ := strconv.Atoi(h.line)
		start, end := max(0, number-3), min(len(lines), number+3)
		if start < end {
			text = strings.Join(lines[start:end], "\n")
		}
		text = prefixUTF8(text, 1200)
		p.SourceHints = append(p.SourceHints, h.path+":"+strconv.Itoa(start+1)+":"+text)
		perModule[module]++
		selectedFiles[h.path] = true
		if len(p.SourceHints) == 24 {
			p.SearchLimited = true
			break
		}
	}

	selectedPaths := []string{}
	for path := range selectedFiles {
		for _, term := range terms {
			filename := path[strings.LastIndex(path, "/")+1:]
			dot := strings.LastIndex(filename, ".")
			if dot >= 0 {
				filename = filename[:dot]
			}
			if strings.EqualFold(filename, term) || strings.Contains(evidence, path) {
				selectedPaths = append(selectedPaths, path)
				break
			}
		}
	}
	p.ConsumerHints = KnownConsumers(selectedPaths)
	return p, nil
}
