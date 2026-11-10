import groovy.json.JsonOutput
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream
import org.apache.tools.tar.TarInputStream

def property = { String name ->
    session.userProperties.getProperty(name, project.properties.getProperty(name, ''))
}
if (Boolean.parseBoolean(property('qip.schemas.sync.skip'))) {
    return
}

def staging = new File(project.build.directory, 'qip-schemas-staging/qip-schemas').toPath()
def local = property('qip.schemas.local.dir')
def archiveUrl = property('qip.schemas.download.url')
def source = local ? new File(local) : new File(project.basedir, '../schemas/src/main/resources/conf-model')
if (local && !source.isDirectory()) {
    throw new IOException('Schema directory not found: ' + source)
}
if (!source.isDirectory() && !archiveUrl) {
    throw new IOException('Set qip.schemas.local.dir or qip.schemas.download.url, ' +
        'or check out schemas/ next to ai-service')
}
if (staging.toFile().exists() && !staging.toFile().deleteDir()) {
    throw new IOException('Cannot replace schema staging directory: ' + staging)
}
Files.createDirectories(staging)

if (source.isDirectory()) {
    def root = source.toPath()
    def paths = Files.walk(root)
    try {
        paths.forEach { path ->
            def target = staging.resolve(root.relativize(path))
            if (Files.isDirectory(path)) {
                Files.createDirectories(target)
            } else {
                Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    } finally {
        paths.close()
    }
} else {
    URI.create(archiveUrl).toURL().withInputStream { download ->
        new TarInputStream(new GZIPInputStream(download)).withCloseable { archive ->
            def entry
            while ((entry = archive.nextEntry) != null) {
                def match = entry.name =~ (
                    /^(?:[^\/]+\/)?(?:schemas\/src\/main\/resources\/conf-model/ +
                    /|src\/main\/resources\/qip-model)\/(.*)$/)
                if (!match.matches()) {
                    continue
                }
                def target = staging.resolve(match.group(1)).normalize()
                if (!target.startsWith(staging)) {
                    throw new IOException('Schema archive entry escapes its directory: ' + entry.name)
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target)
                } else if (entry.isFile()) {
                    Files.createDirectories(target.parent)
                    Files.copy(archive, target, StandardCopyOption.REPLACE_EXISTING)
                } else {
                    throw new IOException('Unsupported schema archive entry: ' + entry.name)
                }
            }
        }
    }
}

def elementDir = staging.resolve('element').toFile()
if (!elementDir.isDirectory()) {
    throw new IOException('Element schema directory not found: ' + elementDir)
}
def elements = elementDir.listFiles()
    .findAll { schema ->
        schema.isFile() && schema.name.endsWith('.schema.yaml') && schema.name != 'element.schema.yaml'
    }
    .sort { schema -> schema.name }
    .collect { schema ->
        def type = schema.name.substring(0, schema.name.length() - '.schema.yaml'.length())
        def titleLine = schema.readLines('UTF-8').find { line -> line.startsWith('title:') }
        def title = titleLine?.substring('title:'.length())?.trim()?.replaceAll(/^"|"$/, '') ?: type
        [type: type, title: title, deprecated: title.toLowerCase(Locale.ROOT).contains('(deprecated)')]
    }
staging.resolve('element-index.json').toFile().setText(
    JsonOutput.prettyPrint(JsonOutput.toJson([elements: elements])) + '\n', 'UTF-8')
log.info('fetch-qip-schemas: staged ' + elements.size() + ' element schemas from ' +
    (source.isDirectory() ? source : archiveUrl))
