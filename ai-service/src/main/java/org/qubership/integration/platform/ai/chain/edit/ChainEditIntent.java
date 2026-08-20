package org.qubership.integration.platform.ai.chain.edit;

import java.util.List;
import java.util.Objects;
import org.qubership.integration.platform.ai.plan.ChainPlanGraphValidator;

/**
 * What the reader asked for, resolved against the imported chain but not yet compiled.
 *
 * <p>An intent names the action and the elements it acts on. It never names a schema property key,
 * a catalog id, or a topology change: structure belongs to the shared structure stage and
 * configuration belongs to the generators selected from pinned ownership metadata.
 * {@code ADD_ELEMENTS} is complete when the capture names the catalog type. A new root trigger
 * needs no existing element: a trigger type with disposition other than {@code NEST} or
 * {@code REMOVE} is placed at chain root. For an insertion, {@code targetNodeIds} is the address:
 * one or two existing element ids naming the pair the new subgraph sits between, or the sole
 * element it follows when that element has exactly one successor. A replacement names the element
 * being swapped in {@code targetNodeIds} and sets {@code disposition} to {@code REMOVE}. A nest
 * names the elements it wraps in {@code targetNodeIds}: {@link ChainEditStructureMerge} grants a
 * reparent only to a named target, so a nest with an empty address describes a merge no capture
 * can satisfy and asks the reader which element it wraps instead.
 * {@code CONFIGURE} is complete when the capture names both a target and at least one property
 * key; {@code propertyKeys} is empty for every other action. A resolver that cannot decide which
 * existing element an edit means, or which of an anchor's several successors an insertion sits
 * before, says so through {@code unresolvedAmbiguities} and leaves {@code targetNodeIds} empty or
 * partial.
 */
public record ChainEditIntent(
    ChainEditAction action,
    List<String> targetNodeIds,
    String requestedChange,
    String externalBindingQuery,
    String requestedElementType,
    String cronExpression,
    List<String> propertyKeys,
    List<String> unresolvedAmbiguities,
    ChainEditDisposition disposition) {

  /** An edit of elements the chain already has, without a requested element type. */
  public ChainEditIntent(
      ChainEditAction action,
      List<String> targetNodeIds,
      String requestedChange,
      String externalBindingQuery,
      List<String> unresolvedAmbiguities) {
    this(
        action,
        targetNodeIds,
        requestedChange,
        externalBindingQuery,
        null,
        null,
        List.of(),
        unresolvedAmbiguities,
        ChainEditDisposition.UNSET);
  }

  /** An addition or edit that names a type but not a schedule or disposition. */
  public ChainEditIntent(
      ChainEditAction action,
      List<String> targetNodeIds,
      String requestedChange,
      String externalBindingQuery,
      String requestedElementType,
      List<String> unresolvedAmbiguities) {
    this(
        action,
        targetNodeIds,
        requestedChange,
        externalBindingQuery,
        requestedElementType,
        null,
        List.of(),
        unresolvedAmbiguities,
        ChainEditDisposition.UNSET);
  }

  /** An addition that names type and keys, and infers disposition from the address. */
  public ChainEditIntent(
      ChainEditAction action,
      List<String> targetNodeIds,
      String requestedChange,
      String externalBindingQuery,
      String requestedElementType,
      String cronExpression,
      List<String> propertyKeys,
      List<String> unresolvedAmbiguities) {
    this(
        action,
        targetNodeIds,
        requestedChange,
        externalBindingQuery,
        requestedElementType,
        cronExpression,
        propertyKeys,
        unresolvedAmbiguities,
        ChainEditDisposition.UNSET);
  }

  public ChainEditIntent {
    Objects.requireNonNull(action, "action");
    requestedElementType =
        requestedElementType == null || requestedElementType.isBlank()
            ? null
            : requestedElementType.trim();
    cronExpression =
        cronExpression == null || cronExpression.isBlank() ? null : cronExpression.trim();
    targetNodeIds = targetNodeIds == null ? List.of() : List.copyOf(targetNodeIds);
    requestedChange = requestedChange == null ? "" : requestedChange;
    externalBindingQuery =
        externalBindingQuery == null || externalBindingQuery.isBlank()
            ? null
            : externalBindingQuery.trim();
    propertyKeys = propertyKeys == null ? List.of() : List.copyOf(propertyKeys);
    unresolvedAmbiguities =
        unresolvedAmbiguities == null ? List.of() : List.copyOf(unresolvedAmbiguities);
    disposition =
        resolvedDisposition(action, requestedElementType, targetNodeIds, disposition);
  }

  public boolean resolved() {
    if (action == ChainEditAction.UNRESOLVED || !unresolvedAmbiguities.isEmpty()) {
      return false;
    }
    if (action == ChainEditAction.NO_CHANGE) {
      return true;
    }
    if (action == ChainEditAction.ADD_ELEMENTS) {
      if (requestedElementType == null) {
        return false;
      }
      return isRootTrigger() || !targetNodeIds.isEmpty();
    }
    if (action == ChainEditAction.CONFIGURE) {
      return !targetNodeIds.isEmpty() && !propertyKeys.isEmpty();
    }
    return !targetNodeIds.isEmpty();
  }

  /**
   * Whether this addition needs the shared structure stage to build its shape.
   *
   * <p>Keep splices at the address {@code targetNodeIds} names. Nest wraps, moves, or branches an
   * existing element. Remove puts a new subgraph in a named element's place. All three can add
   * more than one linked element at once, so they go through the stage that produces a whole
   * subgraph rather than a single bare node. A root trigger needs no address and no subgraph, so
   * it is placed directly.
   */
  public boolean requiresStructureStage() {
    return action == ChainEditAction.ADD_ELEMENTS && !isRootTrigger();
  }

  /**
   * Whether the structure stage captures what this edit adds instead of the chain it rebuilds.
   *
   * <p>A nest is the edit that suffered most from the older contract: a capture of the whole chain
   * let the generator decide containment for elements the request never named, and the wrap was
   * refused afterwards for enclosing a neighbour. Keep and remove still capture a whole graph, so
   * both contracts are live and the disposition is what tells them apart.
   */
  public boolean capturesSubgraph() {
    return action == ChainEditAction.ADD_ELEMENTS && disposition == ChainEditDisposition.NEST;
  }

  /**
   * Whether the named targets are removed and the new subgraph takes their connections.
   *
   * <p>Keep and nest leave those elements on the chain. Remove is the same insertion with the
   * address element deleted, so one approval both adds the subgraph and takes the old element
   * away.
   */
  public boolean replacesAddressElement() {
    return action == ChainEditAction.ADD_ELEMENTS && disposition == ChainEditDisposition.REMOVE;
  }

  /**
   * A new trigger at chain root, fanning into the start existing triggers already share.
   *
   * <p>Named {@code targetNodeIds} are that start, when the request named it. Nest and remove
   * still go through the structure stage even when the new type is a trigger.
   */
  public boolean isRootTrigger() {
    return action == ChainEditAction.ADD_ELEMENTS
        && requestedElementType != null
        && ChainPlanGraphValidator.isTriggerElementType(requestedElementType)
        && disposition != ChainEditDisposition.NEST
        && disposition != ChainEditDisposition.REMOVE;
  }

  ChainEditIntent withTargets(List<String> targets) {
    return new ChainEditIntent(
        action,
        targets,
        requestedChange,
        externalBindingQuery,
        requestedElementType,
        cronExpression,
        propertyKeys,
        List.of(),
        disposition);
  }

  private static ChainEditDisposition resolvedDisposition(
      ChainEditAction action,
      String requestedElementType,
      List<String> targetNodeIds,
      ChainEditDisposition disposition) {
    if (disposition != null && disposition != ChainEditDisposition.UNSET) {
      return disposition;
    }
    if (action != ChainEditAction.ADD_ELEMENTS) {
      return ChainEditDisposition.UNSET;
    }
    if (requestedElementType != null
        && ChainPlanGraphValidator.isTriggerElementType(requestedElementType)) {
      return ChainEditDisposition.UNSET;
    }
    if (targetNodeIds != null && !targetNodeIds.isEmpty()) {
      return ChainEditDisposition.KEEP;
    }
    return ChainEditDisposition.UNSET;
  }
}
