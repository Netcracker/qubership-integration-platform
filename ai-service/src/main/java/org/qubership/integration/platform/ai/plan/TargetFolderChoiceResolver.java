package org.qubership.integration.platform.ai.plan;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import org.qubership.integration.platform.ai.chain.folder.CatalogFolders;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftSettings;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftUpdate;

/**
 * Turns one catalog-folder answer into a source-backed settings update.
 *
 * <p>One resolved path is submitted through {@link RequirementCaptureTools}. An unknown name or
 * several matches stay unresolved, so the target-folder gate remains the active question.
 */
@ApplicationScoped
public class TargetFolderChoiceResolver {

  private final CatalogFolders folders;
  private final RequirementCaptureTools tools;

  @Inject
  public TargetFolderChoiceResolver(CatalogFolders folders, RequirementCaptureTools tools) {
    this.folders = folders;
    this.tools = tools;
  }

  /** The settings update for one unique folder path, or empty when the answer does not select one. */
  public Optional<DraftUpdate> selection(String answerText) {
    if (folders == null || answerText == null || answerText.isBlank()) {
      return Optional.empty();
    }
    if (!(CatalogFolders.resolve(answerText, folders.tree()) instanceof CatalogFolders.Resolution.Found found)
        || found.folder() == null || found.folder().path() == null || found.folder().path().isBlank()) {
      return Optional.empty();
    }
    return Optional.of(new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
        new DraftSettings(null, null, found.folder().path())));
  }

  /**
   * Submits {@link #selection(String)} through requirement capture.
   *
   * @return the tool result, or null when the answer does not select one folder
   */
  public String apply(String answerText) {
    DraftUpdate update = selection(answerText).orElse(null);
    if (update == null || tools == null) {
      return null;
    }
    return tools.updateRequirementDraft(update);
  }
}
