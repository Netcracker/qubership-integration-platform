package org.qubership.integration.platform.ai.chat.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.zafarkhaja.semver.Version;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.compiler.artifact.ArtifactBlobStore;
import org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogMatch;
import org.qubership.integration.platform.ai.plan.ConversationApiResolutions;
import org.qubership.integration.platform.ai.plan.InteractionAssessment;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;

@ApplicationScoped
public class CatalogVersionSelection {
  public static final String ARTIFACT_TYPE = "catalog-operation-version";
  private static final String PREFIX = "catalog-version-selections/";

  public record Proposal(RequirementFlow.Interaction interaction, CatalogMatch recommendation, boolean confirmed) {}
  public record Pending(Proposal proposal, String hash) {}

  private final ArtifactBlobStore blobs;
  private final ObjectMapper json;
  private final RequirementDraftStore drafts;
  private final ConversationApiResolutions resolutions;

  @Inject
  public CatalogVersionSelection(ArtifactBlobStore blobs, ObjectMapper json,
      RequirementDraftStore drafts, ConversationApiResolutions resolutions) {
    this.blobs = blobs;
    this.json = json;
    this.drafts = drafts;
    this.resolutions = resolutions;
  }

  public Optional<CatalogMatch> propose(String conversationId, RequirementFlow.Interaction interaction,
      List<CatalogMatch> candidates) {
    return newest(candidates).map(match -> {
      save(conversationId, new Proposal(interaction, match, false));
      return match;
    });
  }

  public Optional<Pending> pending(String conversationId) {
    var draft = drafts.get(conversationId);
    if (draft.isEmpty()) return Optional.empty();
    for (var interaction : draft.get().flow().interactions()) {
      if (draft.get().catalogBinding(interaction.interactionId()).isPresent()) continue;
      var proposal = read(conversationId, interaction.interactionId())
          .filter(value -> !value.confirmed() && value.interaction().equals(interaction));
      if (proposal.isPresent()) {
        String revision = drafts.latestRevision(conversationId).orElseThrow().contentHash();
        return Optional.of(new Pending(proposal.get(), hash(revision, proposal.get())));
      }
    }
    return Optional.empty();
  }

  public Optional<CatalogMatch> confirmed(String conversationId, RequirementFlow.Interaction interaction) {
    return read(conversationId, interaction.interactionId())
        .filter(proposal -> proposal.confirmed() && proposal.interaction().equals(interaction))
        .map(Proposal::recommendation);
  }

  public Optional<CatalogMatch> awaitingConfirmation(String conversationId, RequirementFlow.Interaction interaction) {
    return read(conversationId, interaction.interactionId())
        .filter(proposal -> !proposal.confirmed() && proposal.interaction().equals(interaction))
        .map(Proposal::recommendation);
  }

  public CatalogMatch confirm(String conversationId, Pending pending) {
    Proposal proposal = pending.proposal();
    return confirm(conversationId, proposal.interaction(), proposal.recommendation());
  }

  public CatalogMatch confirm(String conversationId, RequirementFlow.Interaction interaction, CatalogMatch match) {
    save(conversationId, new Proposal(interaction, match, true));
    var intent = new InteractionAssessment.Intent(interaction.description(), interaction.participant(),
        interaction.operation(), null, null);
    resolutions.remember(conversationId,
        InteractionAssessment.resolved(interaction.interactionId(), intent, match));
    return match;
  }

  public void dismiss(String conversationId, Pending pending) {
    blobs.put(key(conversationId, pending.proposal().interaction().interactionId()),
        "null".getBytes(StandardCharsets.UTF_8));
  }

  public ChatEvent.Decision decision(Pending pending, long revision, String question) {
    return new ChatEvent.Decision(ARTIFACT_TYPE + ":" + pending.hash(), "approve", question,
        ARTIFACT_TYPE, pending.hash(), revision, null, List.of(),
        List.of(ChatEvent.APPROVE_ACTION, ChatEvent.REQUEST_CHANGES_ACTION));
  }

  static Optional<CatalogMatch> newest(List<CatalogMatch> candidates) {
    if (candidates.size() < 2) return Optional.empty();
    CatalogMatch first = candidates.getFirst();
    if (candidates.stream().anyMatch(match -> !Objects.equals(first.systemId(), match.systemId())
        || !Objects.equals(first.operationName(), match.operationName())
        || match.version() == null || Version.tryParse(match.version(), false).isEmpty())) {
      return Optional.empty();
    }
    Comparator<CatalogMatch> byVersion = Comparator.comparing(match -> Version.parse(match.version(), false),
        Version::compareToIgnoreBuildMetadata);
    List<CatalogMatch> sorted = candidates.stream().sorted(byVersion.reversed()).toList();
    return byVersion.compare(sorted.get(0), sorted.get(1)) > 0
        ? Optional.of(sorted.getFirst()) : Optional.empty();
  }

  private Optional<Proposal> read(String conversationId, String interactionId) {
    return blobs.get(key(conversationId, interactionId)).map(bytes -> {
      try {
        return json.readValue(bytes, Proposal.class);
      } catch (IOException error) {
        throw new IllegalStateException("Cannot read the catalog version selection", error);
      }
    });
  }

  private void save(String conversationId, Proposal proposal) {
    try {
      blobs.put(key(conversationId, proposal.interaction().interactionId()), json.writeValueAsBytes(proposal));
    } catch (IOException error) {
      throw new IllegalStateException("Cannot save the catalog version selection", error);
    }
  }

  private String hash(String revision, Proposal proposal) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      digest.update(revision.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest.digest(json.writeValueAsBytes(proposal)));
    } catch (NoSuchAlgorithmException | IOException error) {
      throw new IllegalStateException("Cannot identify the catalog version selection", error);
    }
  }

  private static String key(String conversationId, String interactionId) {
    return PREFIX + conversationId + "/" + interactionId + ".json";
  }
}
