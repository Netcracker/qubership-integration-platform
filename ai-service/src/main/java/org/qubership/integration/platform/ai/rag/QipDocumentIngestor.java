package org.qubership.integration.platform.ai.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentByCharacterSplitter;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.configuration.AppConfig;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Ingests two corpora into the in-memory embedding store at application startup (idempotent):
 *
 * <ol>
 *   <li>QIP documentation markdown files from {@code classpath:docs/} (tracked under {@code
 *       src/main/resources/docs/}; module-root {@code /docs} is gitignored for local dumps)
 *   <li>QIP element schema YAML files from {@code classpath:qip-schemas/}
 * </ol>
 *
 * Each document is chunked and tagged with {@code source} metadata ({@code "docs"} or {@code
 * "schema"}) plus {@code elementType} for schemas.
 */
@ApplicationScoped
public class QipDocumentIngestor {

  private static final Logger LOG = Logger.getLogger(QipDocumentIngestor.class);

  @Inject AppConfig config;

  @Inject EmbeddingModel embeddingModel;

  @Inject EmbeddingStore<TextSegment> embeddingStore;

  void onStart(@Observes StartupEvent ev) {
    LOG.info("Starting QIP RAG document ingestion...");
    try {
      ingestAll();
    } catch (Exception e) {
      LOG.errorf(e, "RAG ingestion failed — service will start without embedded knowledge");
    }
  }

  private void ingestAll() throws Exception {
    AppConfig.RagConfig ragCfg = config.rag();
    EmbeddingStoreIngestor ingestor =
        EmbeddingStoreIngestor.builder()
            .embeddingModel(embeddingModel)
            .embeddingStore(embeddingStore)
            .documentSplitter(
                new DocumentByCharacterSplitter(ragCfg.chunkSize(), ragCfg.chunkOverlap()))
            .build();

    List<Document> docs = new ArrayList<>();
    docs.addAll(loadClasspathDirectory("docs", "source", "docs"));
    docs.addAll(loadClasspathDirectory("qip-schemas", "source", "schema"));

    if (docs.isEmpty()) {
      LOG.warn("No documents found for RAG ingestion — check classpath resources");
      return;
    }

    LOG.infof("Ingesting %d documents into in-memory embedding store...", docs.size());
    ingestor.ingest(docs);
    LOG.infof("RAG ingestion complete. %d documents embedded.", docs.size());
  }

  /**
   * Recursively loads all files from a classpath directory as {@link Document} objects. Adds {@code
   * sourceKey=sourceValue} and file-derived metadata to each document.
   */
  private List<Document> loadClasspathDirectory(
      String classpathDir, String sourceKey, String sourceValue) throws Exception {
    List<Document> documents = new ArrayList<>();
    URL rootUrl = Thread.currentThread().getContextClassLoader().getResource(classpathDir);
    if (rootUrl == null) {
      LOG.warnf("Classpath directory not found: %s", classpathDir);
      return documents;
    }

    URI rootUri = rootUrl.toURI();
    Path rootPath;

    // Handle both filesystem and jar/classpath paths
    if ("jar".equals(rootUri.getScheme())) {
      try (FileSystem fs = FileSystems.newFileSystem(rootUri, Map.of())) {
        rootPath = fs.getPath("/" + classpathDir);
        collectFiles(rootPath, sourceKey, sourceValue, classpathDir, documents);
      }
    } else {
      rootPath = Path.of(rootUri);
      collectFiles(rootPath, sourceKey, sourceValue, classpathDir, documents);
    }

    return documents;
  }

  private void collectFiles(
      Path rootPath,
      String sourceKey,
      String sourceValue,
      String classpathPrefix,
      List<Document> documents)
      throws IOException {
    try (var stream = Files.walk(rootPath)) {
      stream
          .filter(Files::isRegularFile)
          .filter(p -> isReadableFile(p.getFileName().toString()))
          .forEach(
              filePath -> {
                try {
                  String content = Files.readString(filePath, StandardCharsets.UTF_8);
                  String fileName = rootPath.relativize(filePath).toString().replace('\\', '/');

                  Metadata metadata =
                      Metadata.from(
                          Map.of(
                              "source", sourceValue,
                              "file", fileName,
                              "elementType", deriveElementType(fileName)));

                  documents.add(Document.from("# " + fileName + "\n\n" + content, metadata));
                } catch (IOException e) {
                  LOG.warnf("Could not read file %s: %s", filePath, e.getMessage());
                }
              });
    }
  }

  private boolean isReadableFile(String name) {
    return name.endsWith(".md")
        || name.endsWith(".yaml")
        || name.endsWith(".yml")
        || name.endsWith(".txt")
        || name.endsWith(".json");
  }

  /**
   * Derives an element type label from the schema file name. E.g. {@code
   * "element/service-call.schema.yaml"} → {@code "service-call"}.
   */
  private String deriveElementType(String fileName) {
    if (!fileName.endsWith(".schema.yaml")) {
      return "doc";
    }
    String base = fileName.substring(fileName.lastIndexOf('/') + 1);
    return base.replace(".schema.yaml", "");
  }
}
