package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/doccycle"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/docfeedback"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/docgithub"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/docpacket"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/doctriage"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentruntime"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/jev"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/localwork"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcontext"
)

func read(path string, target any) error {
	f, e := os.Open(path)
	if e != nil {
		return e
	}
	defer f.Close()
	return json.NewDecoder(f).Decode(target)
}
func save(path string, v any) error {
	b, e := json.MarshalIndent(v, "", "  ")
	if e != nil {
		return e
	}
	return os.WriteFile(path, append(b, '\n'), 0600)
}
func git(ctx context.Context, source string, args ...string) error {
	c := exec.CommandContext(ctx, "git", append([]string{"-c", "core.hooksPath=/dev/null", "-C", source}, args...)...)
	b, e := c.CombinedOutput()
	if e != nil {
		return fmt.Errorf("git %s: %w: %s", args[0], e, b)
	}
	return nil
}
func preparePRHead(ctx context.Context, client *docgithub.Client, source string, pr docpacket.PR) (docpacket.PRHead, error) {
	head, err := client.MergedPRHead(ctx, pr)
	if err != nil {
		return head, err
	}
	if git(ctx, source, "cat-file", "-e", head.SHA+"^{commit}") == nil {
		return head, nil
	}
	ref := fmt.Sprintf("refs/pull/%d/head", pr.Number)
	if git(ctx, source, "fetch", "--no-tags", "origin", ref) == nil && git(ctx, source, "cat-file", "-e", head.SHA+"^{commit}") == nil {
		return head, nil
	}
	if err = git(ctx, source, "fetch", "--no-tags", "origin", head.SHA); err != nil {
		return head, fmt.Errorf("fetch PR head %s: %w", head.SHA, err)
	}
	return head, git(ctx, source, "cat-file", "-e", head.SHA+"^{commit}")
}
func githubToken(useOrgBot bool) (string, error) {
	token := os.Getenv("GITHUB_TOKEN")
	if token == "" && !useOrgBot {
		token = os.Getenv("GH_TOKEN")
	}
	if token == "" {
		if useOrgBot {
			return "", fmt.Errorf("GITHUB_TOKEN is required in organization account mode")
		}
		return "", fmt.Errorf("set GITHUB_TOKEN or GH_TOKEN")
	}
	return token, nil
}

func githubRepository(repo string) (*docgithub.Client, error) {
	useOrgBot := os.Getenv("AGENT_USE_ORG_BOT") == "true"
	token, err := githubToken(useOrgBot)
	if err != nil {
		return nil, err
	}
	return docgithub.NewClient(repo, token, docgithub.ClientOptions{UseOrgBot: useOrgBot})
}

func github(repo string) (*docgithub.Client, error) {
	useOrgBot := os.Getenv("AGENT_USE_ORG_BOT") == "true"
	token, err := githubToken(useOrgBot)
	if err != nil {
		return nil, err
	}
	projectToken := os.Getenv("AGENT_PROJECT_TOKEN")
	if useOrgBot && projectToken == "" {
		return nil, fmt.Errorf("organization bot mode requires AGENT_PROJECT_TOKEN")
	}
	return docgithub.NewClient(repo, token, docgithub.ClientOptions{ProjectToken: projectToken, UseOrgBot: useOrgBot})
}
func run(ctx context.Context, args []string) error {
	if len(args) == 0 {
		return fmt.Errorf("usage: docs-agent prepare|work|work-summary|publish|reconcile|discover|claim|record-stop")
	}
	f := flag.NewFlagSet(args[0], flag.ContinueOnError)
	repo := f.String("repository", "", "GitHub owner/repository")
	prNumber := f.Int("pull-request", 0, "documentation PR number")
	publisherLogin := f.String("publisher-login", "", "configured trusted publication account")
	number := f.Int("issue", 0, "documentation issue")
	requestID := f.String("request-id", "", "stable invocation or explicit retry identity")
	project := f.Int("project", 12, "organization project number")
	source := f.String("source", "", "local repository or publication candidate worktree")
	output := f.String("output", "", "new output directory")
	prepared := f.String("prepared", "", "trusted prepared artifact directory")
	workspace := f.String("workspace", "", "new dedicated worktree")
	runtimeConfig := f.String("runtime-config", "", "native runtime config JSON")
	hosted := f.Bool("hosted", false, "run OpenCode with OpenRouter through the controller")
	runtime := f.String("runtime", "", "absolute path to the hosted OpenCode binary")
	runtimeVersion := f.String("runtime-version", "", "expected OpenCode version")
	model := f.String("model", "", "OpenRouter author model")
	effort := f.String("effort", "", "OpenCode author model variant")
	handoff := f.String("handoff", "", "new directory for a validated publication artifact")
	keyFile := f.String("key-file", "", "private OpenRouter key file")
	runDir := f.String("run", "", "completed work output directory")
	workOutcome := f.String("work-outcome", "", "Actions work step outcome")
	checkIDs := f.String("check-ids", "", "required documentation check IDs as JSON")
	actor := f.String("actor", "", "trusted workflow initiating actor")
	basis := f.String("basis", "", "documentation basis selected by discovery")
	planID := f.String("plan-id", "", "documentation plan admitted for merge reconciliation")
	reason := f.String("reason", "", "documentation skip or stop reason")
	publicationFailure := f.String("publication-failure", "", "operation where documentation publication stopped")
	publicationAttempt := f.Int("publication-attempt", 0, "workflow attempt that ran publication")
	signature := f.String("signature", "{}", "observed documentation run usage as JSON")
	apply := f.Bool("apply", false, "apply reviewed GitHub publication effects")
	if e := f.Parse(args[1:]); e != nil {
		return e
	}
	if f.NArg() != 0 {
		return fmt.Errorf("unexpected positional arguments")
	}
	switch args[0] {
	case "route-merge", "reconcile-merge":
		return handleDocumentationMerge(ctx, args[0], *repo, *publisherLogin, *number, *planID, *apply)
	case "discover":
		return discoverSchedule(ctx, *repo, *actor, *project, *number)
	case "authorize-command", "reply-command":
		return handleDocumentationCommand(ctx, *repo, *actor, *reason, args[0] == "reply-command")
	case "claim":
		if !*apply {
			return fmt.Errorf("claim requires --apply")
		}
		return claimSchedule(ctx, *prepared, *basis, *actor)
	case "record-stop":
		if !*apply {
			return fmt.Errorf("record-stop requires --apply")
		}
		return recordScheduleStop(ctx, *prepared, *number, docgithub.ScheduleStop{
			Actor: *actor, Basis: *basis, Reason: *reason,
			PublicationFailure: docgithub.PublicationFailure(*publicationFailure), PublicationAttempt: *publicationAttempt,
		}, *signature)
	case "verify-pr":
		c, e := githubRepository(*repo)
		if e != nil {
			return e
		}
		return docgithub.VerifyDocumentationPR(ctx, c, *prNumber, *publisherLogin)
	case "prepare":
		if *repo == "" || *number <= 0 || !filepath.IsAbs(*source) || !filepath.IsAbs(*output) {
			return fmt.Errorf("prepare requires repository, positive issue and absolute source/output")
		}
		client, e := github(*repo)
		if e != nil {
			return e
		}
		if *actor != "" {
			if e = validateActor(ctx, client, *actor); e != nil {
				return e
			}
			if os.Getenv("GITHUB_ACTIONS") != "true" {
				if e = docgithub.AuthorizeActor(ctx, client, *actor); e != nil {
					return e
				}
			}
		}
		intake, e := docgithub.Prepare(ctx, client, *number, *project)
		if e != nil {
			if skip := docgithub.DiscoverySkipReason(e); *basis != "" && skip != "" {
				return json.NewEncoder(os.Stdout).Encode(map[string]string{"skip_reason": skip})
			}
			return e
		}
		intake.Plan.RequestID = *requestID
		refs := []string{intake.Plan.BaseSHA}
		for _, pr := range intake.Issue.PRs {
			refs = append(refs, pr.MergeSHA)
		}
		for _, sha := range refs {
			if e = git(ctx, *source, "cat-file", "-e", sha+"^{commit}"); e != nil {
				if e = git(ctx, *source, "fetch", "--no-tags", "origin", sha); e != nil {
					return e
				}
			}
		}
		packet, details, e := docpacket.Build(ctx, docpacket.Config{
			RepositoryPath: *source, Repository: *repo, DocsRef: intake.Plan.BaseSHA, Issue: intake.Issue,
			AutoRetrieve: true, MaxDiffBytes: 1000000, MaxDocBytes: 1000000, MaxSourceBytes: 256000,
			ResolvePRHead: func(ctx context.Context, pr docpacket.PR) (docpacket.PRHead, error) {
				return preparePRHead(ctx, client, *source, pr)
			},
		})
		if e != nil {
			return e
		}
		if !intake.Complete {
			packet.MarkImplementationIncomplete(intake.Limitations...)
		}
		intake.Plan.PacketSHA256 = docgithub.Digest(packet)
		intake.Plan.ID = ""
		intake.Plan.ID = docgithub.Digest(intake.Plan)
		if e = os.Mkdir(*output, 0700); e != nil {
			return e
		}
		if e = save(filepath.Join(*output, "plan.json"), intake.Plan); e != nil {
			return e
		}
		if e = save(filepath.Join(*output, "intake.json"), intake); e != nil {
			return e
		}
		if e = save(filepath.Join(*output, "packet.json"), packet); e != nil {
			return e
		}
		if e = save(filepath.Join(*output, "preparation.json"), details); e != nil {
			return e
		}
		task := fmt.Sprintf("Handle the documentation stage for %s#%d against source and help at %s. Read packet.json and the current repository. Automatic retrieval is heuristic; investigate limitations and affected consumers before declaring no change. Follow the controller-selected entry route. Record unrelated older help defects separately. Existing help edits are permitted. Do not modify product code, commit, push, or write to GitHub. A publisher handles delivery.\n\nFor a patch, verify help-only diff scope, actual source facts, changed-page structure and local links, and the existing help index build. Put generated checks and indexes outside the checkout. Product startup is unnecessary for source-verifiable prose. A successful no-change decision needs a specific English issue comment and source/help evidence.\n", *repo, *number, intake.Plan.BaseSHA)
		if e = os.WriteFile(filepath.Join(*output, "task.md"), []byte(task), 0600); e != nil {
			return e
		}
		return json.NewEncoder(os.Stdout).Encode(map[string]any{
			"plan_id":            intake.Plan.ID,
			"base_sha":           intake.Plan.BaseSHA,
			"complete":           packet.Complete,
			"pages":              len(packet.Documentation),
			"limitations":        packet.Limitations,
			"publication_checks": doccycle.PublicationChecks(),
		})
	case "work":
		if !filepath.IsAbs(*prepared) || !filepath.IsAbs(*source) || !filepath.IsAbs(*workspace) || !filepath.IsAbs(*output) {
			return fmt.Errorf("work requires absolute prepared/source/workspace/output paths")
		}
		var cfg localwork.Config
		if *hosted {
			if *runtimeConfig != "" || *keyFile != "" {
				return fmt.Errorf("hosted work takes runtime flags and OPENROUTER_API_KEY from the environment")
			}
			cfg = localwork.Config{
				Hosted: true, RuntimeBinary: *runtime, RuntimeVersion: *runtimeVersion,
				Profile: experimentruntime.Profile{Runtime: "opencode", Model: *model, Effort: *effort},
			}
			if e := validateHostedRuntime(ctx, cfg); e != nil {
				return e
			}
		} else if *runtime != "" || *runtimeVersion != "" || *model != "" || *effort != "" || *handoff != "" {
			return fmt.Errorf("runtime and handoff flags require hosted work")
		}
		if *handoff != "" && !filepath.IsAbs(*handoff) {
			return fmt.Errorf("handoff path must be absolute")
		}
		var plan docgithub.Plan
		var packet doctriage.Packet
		if e := read(filepath.Join(*prepared, "plan.json"), &plan); e != nil {
			return e
		}
		if e := plan.Validate(); e != nil {
			return e
		}
		if e := read(filepath.Join(*prepared, "packet.json"), &packet); e != nil {
			return e
		}
		if packet.Repository != plan.Repository || packet.DocsSHA != plan.BaseSHA || plan.PacketSHA256 != docgithub.Digest(packet) {
			return fmt.Errorf("packet differs from trusted preparation plan")
		}
		if e := os.Mkdir(*output, 0700); e != nil {
			return e
		}
		request, e := doctriage.Request(packet, jev.DefaultModel)
		if e != nil {
			return e
		}
		if e = save(filepath.Join(*output, "jev-request.json"), request); e != nil {
			return e
		}
		classification, callErr := classifyPacket(ctx, packet, *keyFile)
		if callErr != nil && classification.PolicyVersion == "" {
			return callErr
		}
		if e = save(filepath.Join(*output, "jev-report.json"), classification); e != nil {
			return e
		}
		routing, e := doctriage.SelectRoute(packet, classification, doctriage.DefaultRoutingPolicy())
		if e != nil {
			return e
		}
		if e = save(filepath.Join(*output, "routing.json"), routing); e != nil {
			return e
		}
		if *runtimeConfig != "" {
			if e = read(*runtimeConfig, &cfg); e != nil {
				return e
			}
		} else if !*hosted {
			if routing.Route != "docs-no-change" {
				return fmt.Errorf("route %s requires runtime-config; classifier artifacts are preserved (%v)", routing.Route, callErr)
			}
			cfg = localwork.Config{RuntimeBinary: "/unused/codex", RuntimeVersion: "unused-auto-completion", Profile: experimentruntime.Profile{Runtime: "codex", Model: "gpt-6.1-terra", Effort: "high"}}
		}
		if e = git(ctx, *source, "worktree", "add", "--detach", *workspace, plan.BaseSHA); e != nil {
			return e
		}
		taskDir := filepath.Join(*output, "task")
		if e = os.Mkdir(taskDir, 0700); e != nil {
			return e
		}
		for _, name := range []string{"packet.json", "plan.json", "task.md"} {
			b, e := os.ReadFile(filepath.Join(*prepared, name))
			if e != nil {
				return e
			}
			if e = os.WriteFile(filepath.Join(taskDir, name), b, 0600); e != nil {
				return e
			}
		}
		if e = save(filepath.Join(taskDir, "jev-report.json"), classification); e != nil {
			return e
		}
		cfg.TaskKind = "documentation"
		cfg.TaskID = fmt.Sprintf("%s#%d:documentation", plan.Repository, plan.IssueNumber)
		cfg.Workspace = *workspace
		cfg.TaskDirectory = taskDir
		cfg.OutputDirectory = filepath.Join(*output, "work")
		cfg.ResumeFrom = ""
		cfg.ResumeMode = ""
		cfg.Context = workcontext.Selection{AttachmentsAsFiles: true, Instructions: []string{".agents/skills/english-developer-style/SKILL.md", "help/README.md"}, Attachments: []string{"packet.json", "jev-report.json", "plan.json"}}
		cfg.DocumentationTriage = &localwork.DocumentationTriage{PacketPath: "packet.json", ReportPath: "jev-report.json"}
		cfg.RequiredChecks = doccycle.PublicationChecks()
		result, e := localwork.Execute(ctx, cfg)
		if e != nil {
			return e
		}
		if result.Status == "docs-no-change" || result.Status == "docs-ready-for-pr" {
			record, e := docfeedback.Import(cfg.OutputDirectory, nil)
			if e != nil {
				return e
			}
			if e = docfeedback.WriteNew(filepath.Join(*output, "feedback.json"), record); e != nil {
				return e
			}
			if *handoff != "" {
				if e = exportHandoff(ctx, plan, *output, *workspace, *handoff); e != nil {
					return e
				}
			}
		}
		return json.NewEncoder(os.Stdout).Encode(map[string]any{"status": result.Status, "route": routing.Route, "plan_id": plan.ID, "work_directory": cfg.OutputDirectory})
	case "work-summary":
		return writeWorkSummary(*prepared, *runDir, *output, *workOutcome, *checkIDs)
	case "restore":
		return restore(ctx, *prepared, *runDir, *source, *workspace)
	case "rejected-diff":
		return exportRejectedDiff(ctx, *prepared, *runDir, *workspace, *output)
	case "publish", "reconcile":
		var plan docgithub.Plan
		var client *docgithub.Client
		var e error
		if args[0] == "reconcile" && *prepared == "" {
			if *repo == "" || *number <= 0 {
				return fmt.Errorf("reconcile requires prepared artifacts or repository and issue")
			}
			client, e = github(*repo)
			if e != nil {
				return e
			}
			plan, e = docgithub.RecoverPlan(ctx, client, *number)
			if e != nil {
				return e
			}
		} else {
			if e = read(filepath.Join(*prepared, "plan.json"), &plan); e != nil {
				return e
			}
			client, e = github(plan.Repository)
			if e != nil {
				return e
			}
		}
		if e = validateActor(ctx, client, *actor); e != nil {
			return e
		}
		opts := docgithub.PublishOptions{Actor: *actor, Apply: *apply, SourcePath: *source}
		if args[0] == "publish" {
			opts.Attempt = workflowAttempt(plan.Repository)
			result, e := docgithub.Publish(ctx, client, plan, *runDir, opts)
			if e != nil {
				return reportPublicationFailure(e)
			}
			return json.NewEncoder(os.Stdout).Encode(result)
		}
		result, e := docgithub.Reconcile(ctx, client, plan, opts)
		if e != nil {
			return e
		}
		return json.NewEncoder(os.Stdout).Encode(result)
	default:
		return fmt.Errorf("unknown docs-agent mode")
	}
}
func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if e := run(ctx, os.Args[1:]); e != nil {
		fmt.Fprintln(os.Stderr, e)
		os.Exit(1)
	}
}

func validateActor(ctx context.Context, c *docgithub.Client, actor string) error {
	if actor == "" {
		return fmt.Errorf("actor is required for publication or authorized execution")
	}
	if os.Getenv("GITHUB_ACTIONS") == "true" {
		if actor != os.Getenv("GITHUB_ACTOR") {
			return fmt.Errorf("actor differs from the workflow event")
		}
		return docgithub.AuthorizeActor(ctx, c, actor, os.Getenv("GITHUB_TRIGGERING_ACTOR"))
	}
	identity, e := c.ActorIdentity(ctx)
	if e != nil {
		return e
	}
	if !strings.EqualFold(actor, identity.Login) {
		return fmt.Errorf("actor differs from authenticated local caller")
	}
	return nil
}

func classifyPacket(ctx context.Context, packet doctriage.Packet, keyFile string) (doctriage.Report, error) {
	if !packet.CanClassify() {
		report := doctriage.SkipIncomplete(packet, jev.DefaultModel)
		return report, doctriage.ValidateReport(packet, report)
	}
	key := strings.TrimSpace(os.Getenv("OPENROUTER_API_KEY"))
	if keyFile != "" {
		st, e := os.Stat(keyFile)
		if e != nil {
			return doctriage.Report{}, e
		}
		if !st.Mode().IsRegular() || st.Mode().Perm()&0077 != 0 {
			return doctriage.Report{}, fmt.Errorf("OpenRouter key file must be private")
		}
		b, e := os.ReadFile(keyFile)
		if e != nil {
			return doctriage.Report{}, e
		}
		key = strings.TrimSpace(string(b))
	}
	client, e := jev.NewClient(key, jev.Options{})
	if e != nil {
		return doctriage.Report{}, e
	}
	return doctriage.Evaluate(ctx, packet, jev.DefaultModel, client)
}
