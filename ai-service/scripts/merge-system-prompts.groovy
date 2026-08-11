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

// ── Phase 1: merge base + role files into *-system.md ──────────────────────────────────────

rolesDir.eachFile { role ->
    if (!role.isFile() || !role.name.endsWith('.md')) {
        return
    }
    def name = role.name.replaceFirst(/\.md$/, '-system.md')
    def out = new File(outDir, name)
    def roleText = escapeForQuteSystemPrompt(role.getText(StandardCharsets.UTF_8.name()))
    out.setText(base + sep + roleText, StandardCharsets.UTF_8.name())
    log.info('[merge-system-prompts] ' + out.name)
}

// ── Phase 2: copy local block files from src/main/resources/prompts/blocks/ ────────────────

def blocksOutDir = new File(outDir, 'blocks')
def localBlocksDir = new File(project.basedir, 'src/main/resources/prompts/blocks')
if (localBlocksDir.isDirectory()) {
    localBlocksDir.eachFileRecurse { file ->
        if (!file.isFile() || !file.name.endsWith('.md')) return
        def relative = localBlocksDir.toPath().relativize(file.toPath()).toString()
        def outFile = new File(blocksOutDir, relative)
        outFile.parentFile.mkdirs()
        outFile.setText(file.getText(StandardCharsets.UTF_8.name()), StandardCharsets.UTF_8.name())
        log.info("[merge-system-prompts] blocks/${relative} (local)")
    }
}
