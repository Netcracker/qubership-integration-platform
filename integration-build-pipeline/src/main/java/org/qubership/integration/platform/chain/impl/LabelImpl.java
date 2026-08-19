package org.qubership.integration.platform.chain.impl;

import org.qubership.integration.platform.chain.model.Label;

public class LabelImpl implements Label {
    private final String name;
    private final boolean technical;

    public LabelImpl(String name, boolean technical) {
        this.name = name;
        this.technical = technical;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean isTechnical() {
        return technical;
    }
}
