package localwork

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func startAuthorHelper(ctx context.Context, answer func(context.Context, int, string) (string, error)) (string, func(), error) {
	directory, err := os.MkdirTemp("", "qip-author-helper-")
	if err != nil {
		return "", nil, err
	}
	socket := filepath.Join(directory, "ask.sock")
	listener, err := net.Listen("unix", socket)
	if err != nil {
		_ = os.RemoveAll(directory)
		return "", nil, err
	}
	ctx, cancel := context.WithCancel(ctx)
	var gate sync.Mutex
	number := 0
	server := &http.Server{
		BaseContext: func(net.Listener) context.Context { return ctx },
		Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.Method != http.MethodPost || r.URL.Path != "/" {
				http.NotFound(w, r)
				return
			}
			if !gate.TryLock() {
				http.Error(w, "An author helper request is already running.", http.StatusConflict)
				return
			}
			defer gate.Unlock()
			if r.Context().Err() != nil {
				return
			}
			body, err := io.ReadAll(r.Body)
			if err != nil || strings.TrimSpace(string(body)) == "" {
				http.Error(w, "Send a source question as plain text.", http.StatusBadRequest)
				return
			}
			number++
			text, err := answer(r.Context(), number, string(body))
			if err != nil {
				http.Error(w, fmt.Sprintf("Author helper failed for request %d. %v", number, err), http.StatusInternalServerError)
				return
			}
			w.Header().Set("Content-Type", "text/plain; charset=utf-8")
			_, _ = io.WriteString(w, text)
		}),
	}
	stopped := make(chan struct{})
	go func() {
		defer close(stopped)
		_ = server.Serve(listener)
	}()
	stop := func() {
		cancel()
		_ = server.Close()
		<-stopped
		gate.Lock()
		gate.Unlock()
		_ = os.RemoveAll(directory)
	}
	return socket, stop, nil
}

func authorHelperInstructions(socket string) string {
	return fmt.Sprintf(`You may ask a read-only helper a focused question about the source. Use this interface for model assistance. You own all edits, product checks, and conclusions. Wait for the reply before changing the files in the question. Confirm any relevant claims in the reply yourself.

curl --noproxy '*' --fail-with-body --silent --show-error --unix-socket '%s' --data-binary @- http://localhost/ <<'QIP_HELPER_QUESTION'
<your focused question>
QIP_HELPER_QUESTION`, strings.ReplaceAll(socket, "'", "'\"'\"'"))
}

func (d *Driver) runAuthorHelper(ctx context.Context, request workcycle.Request, parent string, number int, question string) (text string, err error) {
	dir := filepath.Join(parent, fmt.Sprintf("helper-%04d", number))
	if err := os.Mkdir(dir, 0700); err != nil {
		return "", err
	}
	invoked := false
	defer func() {
		if err != nil && !invoked {
			receipt := map[string]any{"stage": "author-helper", "native_process_started": false, "error": err.Error()}
			err = errors.Join(err, writeJSON(filepath.Join(dir, "helper-setup-failure.json"), receipt))
		}
	}()
	candidate, err := d.Snapshot(ctx)
	if err != nil {
		return "", err
	}
	view, err := d.prepareReadOnlyReview(ctx, candidate)
	if err != nil {
		return "", err
	}
	defer func() { err = errors.Join(err, view.cleanup()) }()
	home := filepath.Join(dir, "home")
	if err := os.Mkdir(home, 0700); err != nil {
		return "", err
	}
	prompt := fmt.Sprintf(`Answer the author's focused question using the supplied read-only checkout for task %s. Inspect relevant files and callers. The base commit is %s. Return a short answer with repository-relative paths and line numbers. Use the code and existing tests as evidence. Leave all edits, test execution, and product checks to the author.

Selected task attachments are in %q.

Task:
%s

Author's question:
%s`, request.TaskID, d.base, view.taskDirectory, request.Task, question)
	invoked = true
	text, err = d.runAgent(ctx, "author-helper", dir, home, prompt, *d.Config.AuthorHelperProfile, "", false, view)
	paths := strings.NewReplacer(view.workspace+"/", d.Config.Workspace+"/", view.taskDirectory+"/", d.Config.TaskDirectory+"/")
	return paths.Replace(text), err
}
