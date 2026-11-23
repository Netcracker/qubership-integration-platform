package main

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/doctriage"
)

func TestDryRunNeedsNoTokenAndNeverCreatesResponse(t *testing.T) {
	root := t.TempDir()
	input := filepath.Join(root, "packet.json")
	out := filepath.Join(root, "out")
	p := doctriage.Packet{ID: "case", Scope: "scoped behavior", Repository: "owner/repo", Issue: json.RawMessage(`{"title":"issue"}`)}
	if err := write(input, p); err != nil {
		t.Fatal(err)
	}
	if err := run(context.Background(), []string{"--input", input, "--output", out, "--key-file", filepath.Join(root, "missing-key"), "--dry-run"}); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(out, "request.json")); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(out, "report.json")); !os.IsNotExist(err) {
		t.Fatal("dry run created inference result")
	}
	if err := run(context.Background(), []string{"--input", input, "--output", out, "--dry-run"}); err == nil {
		t.Fatal("existing evidence overwritten")
	}
}

func TestPrivateKeyFileAndErrors(t *testing.T) {
	root := t.TempDir()
	path := filepath.Join(root, "token")
	secret := "private-test-token"
	if err := os.WriteFile(path, []byte(secret+"\n"), 0600); err != nil {
		t.Fatal(err)
	}
	key, err := readKey(path)
	if err != nil || key != secret {
		t.Fatal("private key could not be read")
	}
	if err := os.Chmod(path, 0644); err != nil {
		t.Fatal(err)
	}
	if _, err := readKey(path); err == nil || strings.Contains(err.Error(), secret) {
		t.Fatal("unsafe token handling")
	}
	if err := os.Chmod(path, 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(secret+"\nsecond line"), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := readKey(path); err == nil || strings.Contains(err.Error(), secret) {
		t.Fatal("invalid key exposed or accepted")
	}
}
