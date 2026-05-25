package org.qubership.integration.platform.ai.llm.artifact;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.storage.S3Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * After CREATE_DESIGN streaming completes, persists the full IDS markdown to S3/MinIO and produces
 * an optional footer line for the chat (download link).
 */
@ApplicationScoped
public class DesignArtifactService {

  private static final Logger LOG = Logger.getLogger(DesignArtifactService.class);

  @Inject S3Service s3Service;

  /**
   * @param accumulatedAssistantMarkdown full streamed IDS text (may be blank)
   * @return non-empty footer markdown when a link was produced or upload failed with user text
   */
  public Optional<String> buildPostStreamFooter(String accumulatedAssistantMarkdown) {
    if (accumulatedAssistantMarkdown == null || accumulatedAssistantMarkdown.isBlank()) {
      return Optional.empty();
    }
    try {
      String key = s3Service.putDesignIdsMarkdown(accumulatedAssistantMarkdown);
      return Optional.of(markdownDownloadFooter(key));
    } catch (Exception e) {
      LOG.errorf(e, "Failed to persist IDS design artifact to object storage");
      String msg =
          e.getMessage() != null
              ? e.getMessage().replace("\r", " ").replace("\n", " ")
              : "unknown error";
      if (msg.length() > 200) {
        msg = msg.substring(0, 200) + "…";
      }
      return Optional.of("\n\n---\n\n*(Could not save IDS to storage: " + msg + ")*\n");
    }
  }

  private static String markdownDownloadFooter(String objectKey) {
    String enc = URLEncoder.encode(objectKey, StandardCharsets.UTF_8);
    String path = "/api/v1/storage/objects?key=" + enc;
    return "\n\n---\n\n**IDS document saved to storage.** [Download markdown](" + path + ")\n";
  }
}
