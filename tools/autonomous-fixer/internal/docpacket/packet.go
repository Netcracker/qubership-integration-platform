package docpacket

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os/exec"
	pathpkg "path"
	"sort"
	"strings"
	"unicode/utf8"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/doctriage"
)

type PR struct {
	Number   int    `json:"number"`
	MergeSHA string `json:"merge_sha"`
	Target   string `json:"target"`
}
type PRHead struct {
	SHA         string
	CommitCount int
}
type Issue struct {
	Number int    `json:"number"`
	Title  string `json:"title"`
	Body   string `json:"body"`
	URL    string `json:"url"`
	PRs    []PR   `json:"prs"`
}
type Config struct {
	RepositoryPath, Repository, DocsRef string
	Issue                               Issue
	AutoRetrieve                        bool
	DocsPaths                           []string
	SourcePaths                         []string
	ResolvePRHead                       func(context.Context, PR) (PRHead, error)
	MaxSourceBytes                      int
	MaxDiffBytes, MaxDocBytes           int
}
type PRReport struct {
	Number     int    `json:"number"`
	MergeSHA   string `json:"merge_sha"`
	BaseSHA    string `json:"base_sha"`
	DiffBytes  int    `json:"diff_bytes"`
	DiffSHA256 string `json:"diff_sha256"`
}
type OmittedBody struct {
	PRNumber int    `json:"pr_number"`
	Path     string `json:"path"`
	Reason   string `json:"reason"`
	Bytes    int    `json:"bytes"`
	SHA256   string `json:"sha256"`
}
type DocMapping struct {
	ChangedPath string `json:"changed_path"`
	CurrentPath string `json:"current_path"`
}
type BuildReport struct {
	SearchEvidence        []string      `json:"search_evidence,omitempty"`
	DocumentationMappings []DocMapping  `json:"documentation_mappings"`
	DocsSHA               string        `json:"docs_sha"`
	PRs                   []PRReport    `json:"prs"`
	OmittedBodies         []OmittedBody `json:"omitted_bodies"`
}

func digest(b []byte) string { h := sha256.Sum256(b); return hex.EncodeToString(h[:]) }
func bounded(b []byte, n int) string {
	if n < 0 {
		n = 0
	}
	if len(b) > n {
		b = b[:n]
	}
	for !utf8.Valid(b) && len(b) > 0 {
		b = b[:len(b)-1]
	}
	return string(b)
}
func compressed(path string) bool {
	if !strings.HasPrefix(path, "help/docs/") && (strings.EqualFold(pathpkg.Ext(path), ".md") || strings.EqualFold(pathpkg.Ext(path), ".mdx")) {
		return true
	}
	return strings.HasPrefix(path, "e2e/") || strings.Contains("/"+path, "/src/test/") || strings.Contains("/"+path, "/tests/") || strings.HasPrefix(path, ".apm/skills/") || strings.HasPrefix(path, ".agents/skills/") || strings.HasPrefix(path, ".claude/skills/")
}

func autoDocumentationCandidates(paths []string) []DocMapping {
	var candidates []DocMapping
	for _, source := range paths {
		page := ""
		switch {
		case source == "ui/src/pages/ChainGraphView.tsx" || strings.HasPrefix(source, "ui/src/components/graph/"):
			page = "help/docs/01__Chains/1__Graph/readme.md"
		case source == "ui/src/components/UserMenu.tsx":
			page = "help/docs/00__Overview/2__General_Functions/readme.md"
		case source == "ui/src/pages/Chains.tsx":
			page = "help/docs/01__Chains/readme.md"
		case source == "ui/src/pages/Masking.tsx":
			page = "help/docs/01__Chains/6__Masking/readme.md"
		case source == "ui/src/pages/SessionPage.tsx" || source == "ui/src/pages/Sessions.tsx":
			page = "help/docs/01__Chains/4__Sessions/readme.md"
		case strings.HasPrefix(source, "ui/src/pages/testing/") || strings.HasPrefix(source, "ui/src/components/testing/"):
			page = "help/docs/01__Chains/8__Testing/readme.md"
		case strings.HasPrefix(source, "ui/src/components/admin_tools/design-templates/"):
			page = "help/docs/03__Admin_Tools/7__Design_Templates/readme.md"
		case strings.HasPrefix(source, "ui/src/components/admin_tools/access-control/"):
			page = "help/docs/03__Admin_Tools/6__Access_Control/readme.md"
		case source == "ui/src/components/admin_tools/ImportInstructions.tsx":
			page = "help/docs/03__Admin_Tools/4__Import_Instructions/readme.md"
		case strings.HasPrefix(source, "ui/src/components/admin_tools/exchanges/"):
			page = "help/docs/03__Admin_Tools/8__Live_Exchanges/readme.md"
		case strings.HasPrefix(source, "ui/src/components/services/context/"):
			page = "help/docs/02__Services/4__Context/readme.md"
		case strings.HasPrefix(source, "ui/src/components/services/mcp/"):
			page = "help/docs/02__Services/5__MCP/readme.md"
		case strings.HasPrefix(source, "ui/src/components/services/"):
			page = "help/docs/02__Services/readme.md"
		case source == "ui/src/components/modal/ImportChains.tsx":
			page = "help/docs/01__Chains/readme.md"
		case strings.HasPrefix(source, "ui/src/components/dev_tools/Diagnostic"):
			page = "help/docs/04__Dev_Tools/2__Diagnostic/readme.md"
		case strings.Contains(source, "service-call") && (strings.HasPrefix(source, "schemas/") || strings.HasPrefix(source, "integration-build-pipeline/")):
			page = "help/docs/01__Chains/1__Graph/1__Elements_Library/7__Senders/6__Service_Call/readme.md"
		case source == "ui/src/pages/Snapshots.tsx" || source == "ui/src/components/modal/SaveAndDeploy.tsx":
			page = "help/docs/01__Chains/2__Snapshots/readme.md"
		case source == "ui/src/pages/Deployments.tsx":
			page = "help/docs/01__Chains/3__Deployments/readme.md"
		case strings.HasPrefix(source, "ui/src/components/admin_tools/domains/"):
			page = "help/docs/03__Admin_Tools/1__Domains/readme.md"
		case strings.HasPrefix(source, "ui/src/components/admin_tools/variables/"):
			page = "help/docs/03__Admin_Tools/2__Variables/readme.md"
		}
		if page != "" {
			candidates = append(candidates, DocMapping{source, page})
		}
	}
	return candidates
}

func documentationCandidates(paths []string) []DocMapping {
	var original []string
	for _, p := range paths {
		if p == "ui/src/pages/Snapshots.tsx" || p == "ui/src/pages/Deployments.tsx" || strings.HasPrefix(p, "ui/src/components/admin_tools/domains/") || strings.HasPrefix(p, "ui/src/components/admin_tools/variables/") {
			original = append(original, p)
		}
	}
	return autoDocumentationCandidates(original)
}

func Build(ctx context.Context, c Config) (doctriage.Packet, BuildReport, error) {
	p := doctriage.Packet{Repository: c.Repository, Complete: true}
	r := BuildReport{}
	git := func(args ...string) ([]byte, error) {
		out, err := exec.CommandContext(ctx, "git", append([]string{"-c", "core.hooksPath=/dev/null", "-c", "core.fsmonitor=false", "-C", c.RepositoryPath}, args...)...).CombinedOutput()
		if err != nil {
			return nil, fmt.Errorf("git %s: %w: %s", strings.Join(args, " "), err, out)
		}
		return out, nil
	}
	fail := func(err error) (doctriage.Packet, BuildReport, error) {
		p.Complete = false
		return p, r, err
	}
	resolve := func(ref string) (string, error) {
		b, e := git("rev-parse", "--verify", ref+"^{commit}")
		return strings.TrimSpace(string(b)), e
	}
	if strings.TrimSpace(c.RepositoryPath) == "" || strings.TrimSpace(c.Repository) == "" || strings.TrimSpace(c.DocsRef) == "" || c.Issue.Number <= 0 || strings.TrimSpace(c.Issue.Title) == "" {
		return fail(fmt.Errorf("packet requires repository path, repository, documentation ref, positive issue number, and issue title"))
	}
	seenPRs := map[int]bool{}
	for _, pr := range c.Issue.PRs {
		if pr.Number <= 0 || strings.TrimSpace(pr.MergeSHA) == "" || seenPRs[pr.Number] {
			return fail(fmt.Errorf("PR #%d requires a unique positive number and merge revision", pr.Number))
		}
		seenPRs[pr.Number] = true
	}
	if len(c.Issue.PRs) == 0 {
		return fail(fmt.Errorf("issue %d has no linked pull requests", c.Issue.Number))
	}
	if c.MaxDiffBytes <= 0 {
		c.MaxDiffBytes = 160000
	}
	if c.MaxDocBytes <= 0 {
		c.MaxDocBytes = 80000
	}
	if c.MaxSourceBytes <= 0 {
		c.MaxSourceBytes = 64000
	}
	var err error
	p.DocsSHA, err = resolve(c.DocsRef)
	if err != nil {
		return fail(err)
	}
	r.DocsSHA = p.DocsSHA
	p.Issue, err = json.Marshal(c.Issue)
	if err != nil {
		return fail(err)
	}
	p.ID = fmt.Sprintf("issue-%d-%s", c.Issue.Number, p.DocsSHA)
	p.Scope = fmt.Sprintf("Documentation impact of the complete landed issue #%d across all supplied linked main pull requests, compared with current documentation at %s.", c.Issue.Number, p.DocsSHA)
	paths := map[string]bool{}
	autoDocs := map[string]bool{}
	var patch strings.Builder
	for _, pr := range c.Issue.PRs {
		if pr.Target != "main" {
			return fail(fmt.Errorf("PR #%d targets %q; supply only linked main pull requests", pr.Number, pr.Target))
		}
		merge, e := resolve(pr.MergeSHA)
		if e != nil {
			return fail(e)
		}
		if _, e = git("merge-base", "--is-ancestor", merge, p.DocsSHA); e != nil {
			return fail(fmt.Errorf("documentation revision %s does not contain PR #%d merge %s: %w", p.DocsSHA, pr.Number, merge, e))
		}
		base, evidence, e := landedBase(ctx, git, c.ResolvePRHead, pr, merge)
		if e != nil {
			return fail(fmt.Errorf("resolve landed changes for PR #%d: %w", pr.Number, e))
		}
		if p.BaseSHA == "" {
			p.BaseSHA = base
		}
		p.HeadSHA = merge
		full, e := git("diff", "--no-ext-diff", "--no-textconv", "--binary", base, merge, "--")
		if e != nil {
			return fail(e)
		}
		r.PRs = append(r.PRs, PRReport{pr.Number, merge, base, len(full), digest(full)})
		p.SearchEvidence = append(p.SearchEvidence, fmt.Sprintf("PR #%d landed on main. Base %s, tip %s, and raw diff SHA256 %s (%d bytes). %s", pr.Number, base, merge, digest(full), len(full), evidence))
		names, e := git("diff", "--name-only", "--no-renames", "-z", base, merge, "--")
		if e != nil {
			return fail(e)
		}
		compressedCount, compressedBytes := 0, 0
		historicalCount, assetCount := 0, 0
		headerIncluded := false
		for _, path := range strings.Split(string(names), "\x00") {
			if path == "" {
				continue
			}
			paths[path] = true
			body, e := git("diff", "--no-ext-diff", "--no-textconv", "--no-renames", "--binary", base, merge, "--", path)
			if e != nil {
				return fail(e)
			}

			reason := ""
			helpPath := strings.HasPrefix(path, "help/docs/")
			historicalDoc := helpPath && (strings.HasSuffix(strings.ToLower(path), ".md") || strings.HasSuffix(strings.ToLower(path), ".mdx"))
			helpAsset := false
			if helpPath {
				switch strings.ToLower(pathpkg.Ext(path)) {
				case ".svg", ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".bmp", ".avif":
					helpAsset = true
				}
			}
			if historicalDoc {
				autoDocs[path] = true
				reason = "historical documentation patch replaced by current documentation"
				historicalCount++
			} else if helpAsset {
				reason = "documentation asset body omitted; visual content not inspected"
				assetCount++
			}

			if reason == "" && compressed(path) {
				reason = "test or developer reference body compressed; changed path retained"
			}
			if !helpAsset && (strings.Contains(string(body), "GIT binary patch") || !utf8.Valid(body)) {
				reason = "binary change requires separate inspection"
				p.Complete = false
				p.Limitations = append(p.Limitations, fmt.Sprintf("Inspect binary change %s in PR #%d.", path, pr.Number))
			}
			if reason != "" {
				if reason == "test or developer reference body compressed; changed path retained" {
					compressedCount++
					compressedBytes += len(body)
				}
				r.OmittedBodies = append(r.OmittedBodies, OmittedBody{pr.Number, path, reason, len(body), digest(body)})
				continue
			}
			header := ""
			if !headerIncluded {
				header = fmt.Sprintf("\nPR #%d: %s..%s\n", pr.Number, base, merge)
				headerIncluded = true
			}
			available := c.MaxDiffBytes - patch.Len()
			combined := []byte(header + string(body))
			if len(combined) > available {
				p.Complete = false
				p.Limitations = append(p.Limitations, fmt.Sprintf("Diff budget omitted part or all of %s in PR #%d; increase MaxDiffBytes and rebuild.", path, pr.Number))
				r.OmittedBodies = append(r.OmittedBodies, OmittedBody{pr.Number, path, "diff budget", len(body), digest(body)})
			}
			patch.WriteString(bounded(combined, available))
		}

		if historicalCount > 0 || assetCount > 0 {
			p.SearchEvidence = append(p.SearchEvidence, fmt.Sprintf("PR #%d: omitted %d historical documentation patches in favor of current pages and %d documentation asset bodies; exact identities are archived in BuildReport.", pr.Number, historicalCount, assetCount))
			if assetCount > 0 {
				p.Limitations = append(p.Limitations, fmt.Sprintf("PR #%d documentation asset visual content was not inspected (%d assets); assess text-based behavior using current pages.", pr.Number, assetCount))
			}
		}
		if compressedCount > 0 {
			p.SearchEvidence = append(p.SearchEvidence, fmt.Sprintf("PR #%d: compressed %d test or developer reference patch bodies (%d bytes); all paths remain in changed_paths. Exact body hashes and byte counts are archived in BuildReport. This compression does not omit production implementation evidence.", pr.Number, compressedCount, compressedBytes))
		}
	}
	for path := range paths {
		p.ChangedPaths = append(p.ChangedPaths, path)
	}
	sort.Strings(p.ChangedPaths)
	p.Diff = patch.String()
	if p.Diff == "" {
		p.Diff = "No textual patch bodies included. Consult changed_paths and compression summaries in search_evidence; exact omitted-body identities are archived in BuildReport."
	}
	for _, o := range r.OmittedBodies {
		if o.Reason == "test or developer reference body compressed; changed path retained" || o.Reason == "historical documentation patch replaced by current documentation" || o.Reason == "documentation asset body omitted; visual content not inspected" {
			continue
		}
		p.SearchEvidence = append(p.SearchEvidence, fmt.Sprintf("Omitted body PR #%d path %q: %s; %d bytes; SHA256 %s.", o.PRNumber, o.Path, o.Reason, o.Bytes, o.SHA256))
	}

	landedDiffComplete := p.Complete
	docPaths := append([]string{}, c.DocsPaths...)
	if c.AutoRetrieve {
		for _, candidate := range autoDocumentationCandidates(p.ChangedPaths) {
			docPaths = append(docPaths, candidate.CurrentPath)
		}
	}
	sourcePaths := append([]string{}, c.SourcePaths...)
	if c.AutoRetrieve {
		retrieved, e := retrieve(git, p.DocsSHA, p.ChangedPaths, c.Issue, p.Diff)
		if e != nil {
			return fail(e)
		}
		docPaths = append(docPaths, retrieved.docs...)
		sourcePaths = append(sourcePaths, retrieved.sources...)
		p.SearchEvidence = append(p.SearchEvidence, retrieved.evidence...)
		p.SearchEvidence = append(p.SearchEvidence, "Automatic lexical retrieval is heuristic and does not prove semantic coverage.")
		if len(retrieved.limitations) > 0 {
			p.Complete = false
			seenLimit := map[string]bool{}
			for _, limitation := range retrieved.limitations {
				if !seenLimit[limitation] {
					p.Limitations = append(p.Limitations, limitation)
					seenLimit[limitation] = true
				}
			}
		}
		mapped := map[string]bool{}
		for _, candidate := range autoDocumentationCandidates(p.ChangedPaths) {
			mapped[candidate.ChangedPath] = true
		}
		for _, changed := range p.ChangedPaths {
			if (strings.HasPrefix(changed, "ui/src/pages/") || strings.HasPrefix(changed, "ui/src/components/admin_tools/") || strings.HasPrefix(changed, "ui/src/components/services/") || strings.HasPrefix(changed, "ui/src/components/dev_tools/") || strings.HasPrefix(changed, "ui/src/components/testing/") || (len(mapped) == 0 && !buildOnly(p.ChangedPaths) && !strings.HasPrefix(changed, "help/docs/") && !compressed(changed))) && !mapped[changed] {
				p.Complete = false
				p.Limitations = append(p.Limitations, "Automatic retrieval has no documentation mapping for changed source: "+changed)
			}
		}
	}
	for _, candidate := range documentationCandidates(p.ChangedPaths) {
		docPaths = append(docPaths, candidate.CurrentPath)
		p.SearchEvidence = append(p.SearchEvidence, fmt.Sprintf("Documentation retrieval candidate %s selected by known UI screen mapping from changed source %s; additive retrieval only, not a documentation verdict or exhaustive coverage claim.", candidate.CurrentPath, candidate.ChangedPath))
	}

	helpComplete := true
	changedDocs := make([]string, 0, len(autoDocs))
	for changed := range autoDocs {
		changedDocs = append(changedDocs, changed)
	}
	sort.Strings(changedDocs)
	for _, changed := range changedDocs {
		current := changed
		if _, e := git("cat-file", "-e", p.DocsSHA+":"+current); e != nil {
			current = pathpkg.Join(pathpkg.Dir(changed), "readme.md")
			if _, e = git("cat-file", "-e", p.DocsSHA+":"+current); e != nil {
				helpComplete = false
				p.Limitations = append(p.Limitations, "Changed documentation "+changed+" has no current page or same-directory readme.md; resolve its current location and rebuild.")
				r.DocumentationMappings = append(r.DocumentationMappings, DocMapping{ChangedPath: changed})
				continue
			}
		}
		r.DocumentationMappings = append(r.DocumentationMappings, DocMapping{changed, current})
		docPaths = append(docPaths, current)
	}
	remaining := c.MaxDocBytes
	if len(docPaths) == 0 && !(c.AutoRetrieve && buildOnly(p.ChangedPaths)) {
		helpComplete = false
		p.Limitations = append(p.Limitations, "No current documentation pages selected; retrieve relevant help/docs pages and rebuild.")
	}
	seenDocs := map[string]bool{}
	for _, path := range docPaths {
		if seenDocs[path] {
			continue
		}
		seenDocs[path] = true
		if !strings.HasPrefix(path, "help/docs/") || strings.Contains(path, ":") || strings.Contains(path, "..") {
			return fail(fmt.Errorf("invalid documentation path %q", path))
		}
		b, e := git("show", p.DocsSHA+":"+path)
		if e != nil {
			helpComplete = false
			p.Limitations = append(p.Limitations, fmt.Sprintf("Selected documentation %s is missing at %s; select an existing page.", path, p.DocsSHA))
			continue
		}
		p.SearchEvidence = append(p.SearchEvidence, fmt.Sprintf("Selected current documentation %s at %s; %d bytes; SHA256 %s.", path, p.DocsSHA, len(b), digest(b)))
		if !utf8.Valid(b) || strings.Contains(string(b), "\x00") {
			helpComplete = false
			p.Limitations = append(p.Limitations, "Inspect binary documentation "+path+" separately.")
			continue
		}
		if len(b) > remaining {
			helpComplete = false
			p.Limitations = append(p.Limitations, "Documentation budget truncated "+path+"; increase MaxDocBytes and rebuild.")
		}
		content := bounded(b, remaining)
		remaining -= len(content)
		if content != "" {
			p.Documentation = append(p.Documentation, doctriage.Document{Path: path, Content: content})
		} else {
			helpComplete = false
			p.Limitations = append(p.Limitations, "Selected documentation has no included content: "+path)
		}
	}

	sourceRemaining := c.MaxSourceBytes
	seenSources := map[string]bool{}
	for _, source := range sourcePaths {
		if source == "" || strings.HasPrefix(source, "/") || strings.Contains(source, ":") || strings.Contains(source, "..") {
			return fail(fmt.Errorf("invalid source path %q", source))
		}
		if seenSources[source] {
			continue
		}
		seenSources[source] = true
		b, e := git("show", p.DocsSHA+":"+source)
		if e != nil {
			p.Complete = false
			p.Limitations = append(p.Limitations, "Selected source missing at documentation revision: "+source)
			continue
		}
		p.SearchEvidence = append(p.SearchEvidence, fmt.Sprintf("Current source %s at %s; %d bytes; SHA256 %s.", source, p.DocsSHA, len(b), digest(b)))
		if !utf8.Valid(b) || strings.Contains(string(b), "\x00") {
			p.Complete = false
			p.Limitations = append(p.Limitations, "Inspect binary source separately: "+source)
			continue
		}
		if len(b) > sourceRemaining {
			p.Complete = false
			p.Limitations = append(p.Limitations, "Source budget truncated "+source+"; increase MaxSourceBytes and rebuild.")
		}
		content := bounded(b, sourceRemaining)
		sourceRemaining -= len(content)
		if content != "" {
			p.Sources = append(p.Sources, doctriage.Document{Path: source, Content: content})
		} else {
			p.Complete = false
			p.Limitations = append(p.Limitations, "Selected source has no included content: "+source)
		}
	}
	p.Complete = p.Complete && helpComplete
	p.PartialContext = !p.Complete && landedDiffComplete && helpComplete && c.AutoRetrieve
	r.SearchEvidence = append([]string(nil), p.SearchEvidence...)
	return p, r, nil
}
