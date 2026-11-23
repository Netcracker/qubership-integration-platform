package experiment

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestNativeDependencyCopiesAreIndependent(t *testing.T) {
	base := t.TempDir()
	if e := os.WriteFile(filepath.Join(base, "artifact.jar"), []byte("baseline"), 0600); e != nil {
		t.Fatal(e)
	}
	homes := []string{t.TempDir(), t.TempDir()}
	for _, home := range homes {
		if e := seedDependencies(context.Background(), ToolPaths{MavenCache: base}, home, t.TempDir()); e != nil {
			t.Fatal(e)
		}
	}
	if e := os.WriteFile(filepath.Join(homes[0], ".m2", "repository", "artifact.jar"), []byte("changed"), 0600); e != nil {
		t.Fatal(e)
	}
	for _, path := range []string{filepath.Join(base, "artifact.jar"), filepath.Join(homes[1], ".m2", "repository", "artifact.jar")} {
		b, e := os.ReadFile(path)
		if e != nil {
			t.Fatal(e)
		}
		if string(b) != "baseline" {
			t.Fatal("dependency changes leaked into another run")
		}
	}
}

func TestNativeEnvironmentExposesSelectedStandWithoutInheritedTokens(t *testing.T) {
	t.Setenv("GITHUB_TOKEN", "fixture-publication-token")
	t.Setenv("OPENAI_API_KEY", "fixture-paid-key")
	home := t.TempDir()
	workspace := t.TempDir()
	stand := StandConfig{DockerHost: "unix:///tmp/test-docker.sock", Kubeconfig: "/tmp/test-kubeconfig"}
	env, e := NativeEnvironment(Profiles()[0], workspace, home, ToolPaths{Node: "/opt/test-node"}, stand, "abc123")
	if e != nil {
		t.Fatal(e)
	}
	joined := "\n" + strings.Join(env, "\n") + "\n"
	for _, value := range []string{"DOCKER_HOST=" + stand.DockerHost, "KUBECONFIG=" + stand.Kubeconfig, "HOME=" + home} {
		if !strings.Contains(joined, "\n"+value+"\n") {
			t.Fatalf("missing direct stand setting %s", value)
		}
	}
	if strings.Contains(joined, "GITHUB_TOKEN=") || strings.Contains(joined, "OPENAI_API_KEY=") {
		t.Fatal("inherited credentials into model environment")
	}
}
