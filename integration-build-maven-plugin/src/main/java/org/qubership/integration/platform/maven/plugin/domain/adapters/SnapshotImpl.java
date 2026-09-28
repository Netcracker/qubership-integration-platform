package org.qubership.integration.platform.maven.plugin.domain.adapters;

import lombok.Setter;
import lombok.ToString;
import org.qubership.integration.platform.chain.impl.ChainImpl;
import org.qubership.integration.platform.chain.model.*;

@ToString
public class SnapshotImpl extends ChainImpl implements Snapshot {
    @Setter
    private Chain chain;

    @Override
    public Chain getChain() {
        return chain;
    }
}
