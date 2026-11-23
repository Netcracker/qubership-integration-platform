package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experiment"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentenv"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentprobe"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimenttoolcheck"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentworkspace"
)

func run(ctx context.Context, args []string) error {
	if len(args) == 0 {
		return fmt.Errorf("usage: experiment init|list|baseline|environment|prepare|refresh-context|toolcheck|capability|command|execute|capture|assessment|metrics|cleanup [flags]")
	}
	f := flag.NewFlagSet(args[0], flag.ContinueOnError)
	root := f.String("root", "", "campaign directory")
	source := f.String("source", "", "source repository")
	revision := f.String("revision", "", "full source commit SHA")
	inputs := f.String("inputs", "", "task input directory")
	id := f.String("job", "", "job ID")
	role := f.String("role", "implement", "implement, r1, r2, or r3")
	node := f.String("node-root", "", "Node installation containing both runtimes")
	readiness := f.String("readiness", "", "controller-side evidence manifest")
	login := f.String("codex-login", "", "ChatGPT subscription login file; never copied into task inputs")
	kubeconfig := f.String("kubeconfig", "", "controller-only kubeconfig")
	toolPaths := f.String("tools", "", "toolchain and sealed dependency paths JSON")
	stand := f.String("stand-config", "", "direct Docker and Kubernetes configuration")
	catalog := f.String("model-catalog", "", "version-matched Codex model catalog")
	applyCleanup := f.Bool("apply", false, "remove completed-run dependency copies after preserving file hashes")
	excludeCleanup := f.String("exclude-job", "", "job to retain during cleanup")
	if e := f.Parse(args[1:]); e != nil {
		return e
	}
	if *root == "" || f.NArg() != 0 {
		return fmt.Errorf("--root is required; positional arguments are not accepted")
	}
	abs, e := filepath.Abs(*root)
	if e != nil {
		return e
	}
	*root = abs
	if args[0] == "execute" || args[0] == "capability" {
		if err := experiment.CheckContinuation(*root); err != nil {
			return err
		}
	}
	if e = os.MkdirAll(filepath.Dir(*root), 0700); e != nil {
		return e
	}
	if args[0] != "metrics" && args[0] != "cleanup" {
		unlock, err := experiment.Lock(*root)
		if err != nil {
			return err
		}
		defer unlock()
	}
	var result any
	switch args[0] {
	case "cleanup":
		result, e = experiment.CleanupCompleted(ctx, *root, *excludeCleanup, *applyCleanup)
	case "init":
		if *source == "" || *revision == "" || *inputs == "" {
			return fmt.Errorf("init requires --source, --revision, and --inputs")
		}
		p, err := experiment.NewPlan(*source, *revision, *inputs)
		if err != nil {
			return err
		}
		if e = experiment.Init(*root, p); e != nil {
			return e
		}
		result = p
	case "list":
		result, e = experiment.Load(*root)
	case "baseline":
		p, err := experiment.Load(*root)
		if err != nil {
			return err
		}
		parent := filepath.Join(*root, "baseline")
		if e = os.MkdirAll(parent, 0700); e != nil {
			return e
		}
		result, e = experimentworkspace.Create(ctx, p.Source, p.Revision, filepath.Join(parent, "repository"), "experiment/baseline")
		if e == nil {
			e = experiment.WriteJSON(filepath.Join(parent, "snapshot.json"), result)
		}
	case "environment":
		if *kubeconfig == "" {
			return fmt.Errorf("environment requires --kubeconfig")
		}
		result, e = experimentenv.Discover(ctx, experimentenv.Config{Kubeconfig: *kubeconfig, Context: "docker-desktop", Namespace: "cip-e2e", BaseURL: "http://localhost:30080"})
	case "prepare":
		result, e = experiment.Prepare(ctx, *root, *id, *role)
	case "refresh-context":
		result, e = experiment.RefreshContext(*root, *id, *role)
	case "command":
		result, e = experiment.PreparedCommand(*root, *id, *role)
	case "toolcheck":
		if *toolPaths == "" {
			return fmt.Errorf("toolcheck requires --tools")
		}
		if _, err := experiment.Load(*root); err != nil {
			return err
		}
		dir, err := experiment.RunDirectory(*root, *id, *role)
		if err != nil {
			return err
		}
		var prepared experiment.Prepared
		if err = experiment.ReadJSON(filepath.Join(dir, "prepared.json"), &prepared); err != nil {
			return err
		}
		var paths experiment.ToolPaths
		if err = experiment.ReadJSON(*toolPaths, &paths); err != nil {
			return err
		}
		var access experiment.StandConfig
		if *stand != "" {
			if err = experiment.ReadJSON(*stand, &access); err != nil {
				return err
			}
		}
		result, e = experimenttoolcheck.Run(ctx, experimenttoolcheck.Config{Workspace: prepared.Snapshot.Directory, Root: *root, Tools: paths, Stand: access})
	case "execute":
		if *node == "" || *readiness == "" {
			return fmt.Errorf("execute requires --node-root and --readiness")
		}
		paths := experiment.ToolPaths{Node: *node}
		if *toolPaths != "" {
			if err := experiment.ReadJSON(*toolPaths, &paths); err != nil {
				return err
			}
		}
		if *stand == "" {
			return fmt.Errorf("execute requires --stand-config")
		}
		result, e = experiment.Execute(ctx, *root, *id, *role, paths, *readiness, *login, *stand)
	case "capability":
		p, err := experiment.Load(*root)
		if err != nil {
			return err
		}
		job, err := p.Job(*id)
		if err != nil {
			return err
		}
		profile := job.Executor
		if *role != "implement" {
			found := false
			for _, r := range job.Reviewers {
				if r.ID == *role {
					profile = r
					found = true
				}
			}
			if !found {
				return fmt.Errorf("unknown reviewer")
			}
		}
		result, e = experimentprobe.Run(ctx, experimentprobe.Config{Profile: profile.RuntimeProfile(), NodeRoot: *node, Root: filepath.Join(*root, "capabilities"), CodexLoginFile: *login, ModelCatalog: *catalog})
	case "capture":
		result, e = experiment.Capture(ctx, *root, *id)
	case "assessment":
		result, e = experiment.AssessmentTemplate(*root, *id)
	case "metrics":
		result, e = experiment.RefreshMetrics(*root, *id, *role)
	default:
		return fmt.Errorf("unknown command %q", args[0])
	}
	if e != nil {
		return e
	}
	enc := json.NewEncoder(os.Stdout)
	enc.SetIndent("", "  ")
	return enc.Encode(result)
}

func main() {
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	if e := run(ctx, os.Args[1:]); e != nil {
		fmt.Fprintln(os.Stderr, e)
		os.Exit(1)
	}
}
