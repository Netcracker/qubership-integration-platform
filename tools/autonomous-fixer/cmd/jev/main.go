package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/doctriage"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/jev"
)

func write(path string, v any) error {
	b, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, append(b, '\n'), 0600)
}
func readKey(path string) (string, error) {
	if path == "" {
		key := strings.TrimSpace(os.Getenv("OPENROUTER_API_KEY"))
		if key == "" {
			return "", errors.New("set OPENROUTER_API_KEY or provide --key-file")
		}
		return key, nil
	}
	f, err := os.Open(path)
	if err != nil {
		return "", errors.New("cannot open the API key file")
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil || !info.Mode().IsRegular() || info.Mode().Perm()&0077 != 0 {
		return "", errors.New("API key file must be a regular file readable only by its owner")
	}
	b, err := io.ReadAll(io.LimitReader(f, 8193))
	if err != nil || len(b) > 8192 {
		return "", errors.New("cannot read the API key file")
	}
	key := strings.TrimSpace(string(b))
	if key == "" || strings.ContainsAny(key, "\r\n\t ") {
		return "", errors.New("API key file must contain only the token")
	}
	return key, nil
}

func run(ctx context.Context, args []string) error {
	f := flag.NewFlagSet("jev", flag.ContinueOnError)
	input := f.String("input", "", "documentation evidence packet JSON")
	output := f.String("output", "", "new output directory")
	keyFile := f.String("key-file", "", "private file containing the OpenRouter token")
	model := f.String("model", jev.DefaultModel, "explicit Jev model version")
	dry := f.Bool("dry-run", false, "write the request without contacting OpenRouter")
	if err := f.Parse(args); err != nil {
		return err
	}
	if *input == "" || *output == "" || f.NArg() != 0 {
		return errors.New("usage: jev --input packet.json --output new-directory [--key-file private-file] [--dry-run]")
	}
	file, err := os.Open(*input)
	if err != nil {
		return err
	}
	var packet doctriage.Packet
	decoder := json.NewDecoder(file)
	decoder.DisallowUnknownFields()
	err = decoder.Decode(&packet)
	if err == nil {
		var extra any
		if decoder.Decode(&extra) != io.EOF {
			err = errors.New("expected one packet")
		}
	}
	file.Close()
	if err != nil {
		return err
	}
	req, err := doctriage.Request(packet, *model)
	if err != nil {
		return err
	}
	if err := os.Mkdir(*output, 0700); err != nil {
		return err
	}
	if err := write(filepath.Join(*output, "request.json"), req); err != nil {
		return err
	}
	if err := write(filepath.Join(*output, "packet.json"), packet); err != nil {
		return err
	}
	if *dry {
		return json.NewEncoder(os.Stdout).Encode(map[string]any{"mode": "dry-run", "request": filepath.Join(*output, "request.json"), "model": *model})
	}
	key, err := readKey(*keyFile)
	if err != nil {
		return err
	}
	client, err := jev.NewClient(key, jev.Options{})
	if err != nil {
		return err
	}
	report, callErr := doctriage.Evaluate(ctx, packet, *model, client)
	if err := write(filepath.Join(*output, "report.json"), report); err != nil {
		return err
	}
	if err := json.NewEncoder(os.Stdout).Encode(report); err != nil {
		return err
	}
	return callErr
}
func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if err := run(ctx, os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
