package experimenttoolcheck

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"time"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experiment"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentruntime"
)

type Config struct {
	Workspace, Root string
	Tools           experiment.ToolPaths
	Stand           experiment.StandConfig
	UIURL           string
}
type Report struct {
	Workspace                         string
	Tools                             experiment.ToolPaths
	IsolationClaim                    string
	Program                           string
	Args                              []string
	DependencySource                  string
	Directory, Screenshot, ReportPath string
	Process                           experimentruntime.Result
	Success                           bool
}

func Run(ctx context.Context, c Config) (r Report, err error) {
	r.Workspace = c.Workspace
	r.Tools = c.Tools
	r.IsolationClaim = "none; direct host execution with a fresh HOME"
	if !filepath.IsAbs(c.Root) || !filepath.IsAbs(c.Workspace) {
		return r, fmt.Errorf("absolute artifact root and workspace are required")
	}
	for _, p := range []string{c.Tools.Node, c.Tools.Maven, c.Tools.E2ENodeModules, c.Tools.Browsers} {
		if !filepath.IsAbs(p) {
			return r, fmt.Errorf("Node, Maven, browser, and fallback e2e dependency paths must be explicit absolute paths")
		}
	}
	parent := filepath.Join(c.Root, "toolchecks")
	if err = os.MkdirAll(parent, 0700); err != nil {
		return r, err
	}
	r.Directory, err = os.MkdirTemp(parent, time.Now().UTC().Format("20060102T150405Z")+"-")
	if err != nil {
		return r, err
	}
	r.ReportPath = filepath.Join(r.Directory, "report.json")
	defer func() {
		b, e := json.MarshalIndent(r, "", "  ")
		if e == nil {
			e = os.WriteFile(r.ReportPath, append(b, '\n'), 0600)
		}
		if err == nil {
			err = e
		}
	}()
	home := filepath.Join(r.Directory, "home")
	inputs := filepath.Join(r.Directory, "inputs")
	for _, p := range []string{home, inputs} {
		if err = os.Mkdir(p, 0700); err != nil {
			return r, err
		}
	}
	for name, body := range map[string]string{"probe.sh": shellProbe, "SecurityProbe.java": javaProbe, "browser.cjs": browserProbe} {
		if err = os.WriteFile(filepath.Join(inputs, name), []byte(body), 0600); err != nil {
			return r, err
		}
	}
	r.DependencySource = filepath.Join(c.Workspace, "e2e", "node_modules")
	if _, e := os.Stat(filepath.Join(r.DependencySource, "@playwright", "test")); e != nil {
		r.DependencySource = c.Tools.E2ENodeModules
	}
	if c.UIURL == "" {
		c.UIURL = "http://localhost:30080/chains"
	}
	env, e := experiment.NativeEnvironment(experiment.Profile{Runtime: "codex"}, c.Workspace, home, c.Tools, c.Stand, "probea")
	if e != nil {
		return r, e
	}
	env = append(env, "PROBE_E2E_MODULES="+r.DependencySource, "PROBE_UI_URL="+c.UIURL)
	r.Program = "/bin/sh"
	r.Args = []string{filepath.Join(inputs, "probe.sh"), filepath.Join(inputs, "SecurityProbe.java"), filepath.Join(inputs, "browser.cjs")}
	r.Process, err = experimentruntime.Run(ctx, experimentruntime.Command{Program: r.Program, Args: r.Args, Workspace: c.Workspace}, filepath.Join(r.Directory, "process"), env, nil, nil)
	r.Screenshot = filepath.Join(home, "evidence", "chains.png")
	if err != nil {
		return r, err
	}
	if r.Process.ExitCode != 0 {
		return r, fmt.Errorf("host toolcheck failed with exit code %d; inspect %s", r.Process.ExitCode, r.Process.StderrPath)
	}
	if _, err = os.Stat(r.Screenshot); err != nil {
		return r, err
	}
	r.Success = true
	return r, nil
}

const shellProbe = `set -eu
java -version
mvn --version
java "$1"
test -r /etc/fonts/fonts.conf
fc-match sans-serif
if [ -n "${DOCKER_HOST:-}" ]; then docker version --format '{{.Server.Version}}'; docker buildx version; fi
if [ -n "${KUBECONFIG:-}" ]; then kubectl --context docker-desktop --request-timeout=15s get nodes -o name; kubectl --context docker-desktop auth can-i create deployments -n cip-e2e; fi
node "$2"
`
const javaProbe = `import java.security.Security;
import javax.net.ssl.SSLContext;
class SecurityProbe {
	public static void main(String[] args) throws Exception {
		if (Security.getProviders().length < 5 || Security.getProvider("SUN") == null) throw new IllegalStateException("Java security providers unavailable");
		var context=SSLContext.getDefault();
		System.out.println("Java security providers="+Security.getProviders().length+" TLS="+context.getProtocol());
	}
}
`
const browserProbe = `const fs = require('node:fs/promises');
const { chromium } = require(process.env.PROBE_E2E_MODULES+'/@playwright/test');
(async () => {
	const browser = await chromium.launch({headless:true});
	try {
		const page = await browser.newPage({viewport:{width:1440,height:1000}});
		const response=await page.goto(process.env.PROBE_UI_URL,{waitUntil:'networkidle',timeout:45000});
		if (!response || response.status() !== 200) throw new Error('Allocated UI route is not HTTP 200');
		await page.getByText('Chains',{exact:true}).first().waitFor({state:'visible',timeout:20000});
		const text=await page.locator('body').innerText();
		if (!text.trim() || /502 Bad Gateway|Application error/.test(text)) throw new Error('Allocated UI is not visible');
		await fs.mkdir(process.env.HOME+'/evidence',{recursive:true});
		await page.screenshot({path:process.env.HOME+'/evidence/chains.png',fullPage:true});
		await fs.writeFile(process.env.HOME+'/evidence/browser.json',JSON.stringify({url:page.url(),status:response.status(),title:await page.title(),visibleChains:true},null,2));
		console.log('Browser read-only smoke passed: '+page.url());
	} finally { await browser.close(); }
})().catch(error=>{console.error(error);process.exitCode=1;});
`
