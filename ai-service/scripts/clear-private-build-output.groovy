// Rebuild generated private resources from the explicitly selected package on every build.
def output = new File(project.build.outputDirectory)
['qipknowledge', 'META-INF/rocky-skills', 'product-pipelines'].each { path ->
    new File(output, path).deleteDir()
}
new File(project.build.directory, 'knowledge-runtime').deleteDir()
