package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/jev"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/tasktriage"
)

func run(ctx context.Context, args []string) error {
	fs := flag.NewFlagSet("jev-task", flag.ContinueOnError)
	packet := fs.String("packet", "", "Input packet JSON")
	output := fs.String("output", "", "New output directory")
	keyFile := fs.String("key-file", "", "Local OpenRouter key file")
	source := fs.String("source", "", "Repository for committed source hints")
	live := fs.Bool("live", false, "Call Jev; otherwise save the request only")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if *packet == "" || *output == "" {
		return fmt.Errorf("packet and output are required")
	}
	b, e := os.ReadFile(*packet)
	if e != nil {
		return e
	}
	var p tasktriage.Packet
	if e = json.Unmarshal(b, &p); e != nil {
		return e
	}
	if *source != "" && p.Phase == "intake" {
		p, e = tasktriage.Discover(ctx, *source, p)
		if e != nil {
			return e
		}
	}
	req, e := tasktriage.Build(p)
	if e != nil {
		return e
	}
	if e = os.Mkdir(*output, 0700); e != nil {
		return e
	}
	save := func(name string, v any) error {
		b, e := json.MarshalIndent(v, "", "  ")
		if e != nil {
			return e
		}
		return os.WriteFile(filepath.Join(*output, name), append(b, '\n'), 0600)
	}
	if e = save("request.json", req); e != nil {
		return e
	}
	if !*live {
		return nil
	}
	key := os.Getenv("OPENROUTER_API_KEY")
	if *keyFile != "" {
		info, statErr := os.Lstat(*keyFile)
		if statErr != nil || !info.Mode().IsRegular() || info.Mode().Perm()&0077 != 0 {
			return fmt.Errorf("key file must be a private regular file")
		}
		b, e = os.ReadFile(*keyFile)
		if e != nil {
			return e
		}
		key = strings.TrimSpace(string(b))
	}
	var c tasktriage.Classifier
	if p.Phase != "failure" || p.Failure == nil || p.Failure.Kind == "" {
		c, e = jev.NewClient(key, jev.Options{})
		if e != nil {
			return e
		}
	}
	report, e := tasktriage.Evaluate(ctx, p, c)
	if e != nil {
		return e
	}
	if e = save("report.json", report); e != nil {
		return e
	}
	if report.Error != "" {
		return fmt.Errorf("classification failed; see report.json")
	}
	return nil
}
func main() {
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	if e := run(ctx, os.Args[1:]); e != nil {
		fmt.Fprintln(os.Stderr, e)
		os.Exit(1)
	}
}
