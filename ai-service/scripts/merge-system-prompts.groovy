import java.nio.charset.StandardCharsets

def baseFile = new File(project.basedir, 'src/main/resources/prompts/qip-base-system.md')
if (!baseFile.exists()) {
    throw new IOException('Missing shared prompt: ' + baseFile.absolutePath)
}
def base = baseFile.getText(StandardCharsets.UTF_8.name())

def rolesDir = new File(project.basedir, 'src/main/resources/prompts/roles')
if (!rolesDir.isDirectory()) {
    throw new IOException('Missing roles directory: ' + rolesDir.absolutePath)
}

def outDir = new File(project.build.outputDirectory, 'prompts')
outDir.mkdirs()

def sep = '\n\n---\n\n'

/** Same rules as QuteUserMessageEscaping — system prompts are rendered as Qute templates at runtime. */
def escapeForQuteSystemPrompt = { String text ->
    if (text == null || text.isEmpty()) {
        return text
    }
    return text.replace('\\', '\\\\').replace('{', '\\{')
}

def implementChainBranching = new File(project.basedir, 'src/main/resources/prompts/implement-chain-branching.md')
rolesDir.eachFile { role ->
    if (!role.isFile() || !role.name.endsWith('.md')) {
        return
    }
    def name = role.name.replaceFirst(/\.md$/, '-system.md')
    def out = new File(outDir, name)
    def roleText = role.getText(StandardCharsets.UTF_8.name())
    if ((role.name == 'implement-chain.md' || role.name == 'create-chain-plan.md')
            && implementChainBranching.exists()) {
        String branchingText = implementChainBranching.getText(StandardCharsets.UTF_8.name())
        roleText = roleText + sep + escapeForQuteSystemPrompt(branchingText)
    }
    out.setText(base + sep + roleText, StandardCharsets.UTF_8.name())
    log.info('[merge-system-prompts] ' + out.name)
}
