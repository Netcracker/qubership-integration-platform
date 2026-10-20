package org.qubership.integration.platform.ai.plan.workdocument;

import java.util.ArrayList;
import java.util.List;

/** One record kind a task may create, limited to a parent id when the parent is set. */
public record CreationAllowance(WorkRecordKind kind, String parentId) {

  public CreationAllowance {
    parentId = parentId == null ? "" : parentId;
  }

  /** Allows these kinds under any parent. A blank parent does not widen other kinds. */
  public static List<CreationAllowance> anyParent(WorkRecordKind... kinds) {
    List<CreationAllowance> allowances = new ArrayList<>();
    for (WorkRecordKind kind : kinds) {
      allowances.add(new CreationAllowance(kind, ""));
    }
    return List.copyOf(allowances);
  }
}
