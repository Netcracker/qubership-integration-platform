package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"os/signal"
	"syscall"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/localwork"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func run(ctx context.Context, args []string) error {
	if len(args) != 1 {
		return fmt.Errorf("usage: work /absolute/path/to/config.json")
	}
	f, err := os.Open(args[0])
	if err != nil {
		return err
	}
	var cfg localwork.Config
	err = localwork.Decode(f, &cfg)
	f.Close()
	if err != nil {
		return err
	}
	report, err := localwork.Execute(ctx, cfg)
	return finish(os.Stdout, report, err)
}

func finish(out io.Writer, report workcycle.Report, err error) error {
	if report.Status != "" {
		if e := json.NewEncoder(out).Encode(report); e != nil {
			return e
		}
	}
	if err != nil {
		return err
	}
	if report.Successful() || (report.Workflow == "fix-issue" && report.Status == "author-reported-fixed") {
		return nil
	}
	return fmt.Errorf("work finished with status %q", report.Status)
}

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if err := run(ctx, os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
