package experiment

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentruntime"
)

type StandConfig struct {
	DockerHost string `json:"docker_host"`
	Kubeconfig string `json:"kubeconfig"`
}

func NativeEnvironment(profile Profile, workspace, home string, tools ToolPaths, stand StandConfig, runToken string) ([]string, error) {
	paths := []string{filepath.Join(tools.Node, "bin")}
	if tools.Maven != "" {
		paths = append(paths, filepath.Join(tools.Maven, "bin"))
	}
	paths = append(paths, "/usr/lib/jvm/java-21-openjdk-amd64/bin", "/usr/local/go/bin", "/usr/local/bin", "/usr/bin", "/bin", "/snap/bin")
	if hostPath := os.Getenv("PATH"); hostPath != "" {
		paths = append(paths, hostPath)
	}
	env := map[string]string{
		"HOME": home, "PATH": strings.Join(paths, ":"), "LANG": "C.UTF-8",
		"JAVA_HOME":         "/usr/lib/jvm/java-21-openjdk-amd64",
		"JAVA_TOOL_OPTIONS": "-Duser.home=" + home + " -Xmx2048m -XX:ActiveProcessorCount=2",
		"NODE_OPTIONS":      "--max-old-space-size=8192",
		"MAVEN_ARGS":        "-Dmaven.repo.local=" + filepath.Join(home, ".m2", "repository") + " -Dgpg.skip=true",
		"CODEX_HOME":        filepath.Join(home, ".codex"),
		"XDG_CONFIG_HOME":   filepath.Join(home, "config"), "XDG_DATA_HOME": filepath.Join(home, "data"), "XDG_CACHE_HOME": filepath.Join(home, "cache"),
		"OPENCODE_CONFIG_DIR": filepath.Join(home, "config", "opencode"), "OPENCODE_CONFIG_PROJECT_DISABLE": "1", "OPENCODE_DISABLE_CLAUDE_CODE": "1",
		"E2E_RUN": runToken, "E2E_WORKERS": "1", "E2E_SWEEP": "never", "CIP_TARGET": "k8s",
	}
	if profile.Runtime == "opencode" {
		config, e := experimentruntime.OpenCodeConfig(profile.RuntimeProfile(), workspace)
		if e != nil {
			return nil, e
		}
		env["OPENCODE_CONFIG_CONTENT"] = config
	}
	if tools.Browsers != "" {
		env["PLAYWRIGHT_BROWSERS_PATH"] = tools.Browsers
	}
	if stand.DockerHost != "" {
		env["DOCKER_HOST"] = stand.DockerHost
	}
	if stand.Kubeconfig != "" {
		if !filepath.IsAbs(stand.Kubeconfig) {
			return nil, fmt.Errorf("kubeconfig must be an absolute path")
		}
		env["KUBECONFIG"] = stand.Kubeconfig
	}
	keys := make([]string, 0, len(env))
	for key := range env {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	result := make([]string, 0, len(env))
	for _, key := range keys {
		result = append(result, key+"="+env[key])
	}
	return result, nil
}

func seedDependencies(ctx context.Context, tools ToolPaths, home, workspace string) error {
	entries := [][2]string{{tools.MavenCache, filepath.Join(home, ".m2", "repository")}, {tools.NodeModules, filepath.Join(workspace, "node_modules")}, {tools.E2ENodeModules, filepath.Join(workspace, "e2e", "node_modules")}}
	if tools.NodeModules != "" {
		for _, module := range []string{"schemas", "ui", "vscode-extension"} {
			source := filepath.Join(filepath.Dir(tools.NodeModules), module, "node_modules")
			if _, e := os.Stat(source); e == nil {
				entries = append(entries, [2]string{source, filepath.Join(workspace, module, "node_modules")})
			}
		}
	}
	for _, entry := range entries {
		if entry[0] == "" {
			continue
		}
		if !filepath.IsAbs(entry[0]) {
			return fmt.Errorf("dependency source must be absolute")
		}
		if existing, e := os.ReadDir(entry[1]); e == nil && len(existing) > 0 {
			return fmt.Errorf("dependency destination is not fresh: %s", entry[1])
		} else if e != nil && !os.IsNotExist(e) {
			return e
		}
		if e := os.MkdirAll(entry[1], 0700); e != nil {
			return e
		}
		cmd := exec.CommandContext(ctx, "/usr/bin/cp", "-a", "--reflink=auto", entry[0]+"/.", entry[1])
		cmd.Env = []string{"PATH=/usr/bin:/bin", "LANG=C.UTF-8"}
		if out, e := cmd.CombinedOutput(); e != nil {
			return fmt.Errorf("copy baseline dependencies: %w: %s", e, out)
		}
	}
	return nil
}
