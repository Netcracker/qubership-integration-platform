package org.qubership.integration.platform.runtime.catalog.adapters;

import org.qubership.integration.platform.chain.model.MaskedField;

public class MaskedFieldAdapter implements MaskedField {
    private final org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.MaskedField maskedField;

    public MaskedFieldAdapter(org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.MaskedField maskedField) {
        this.maskedField = maskedField;
    }

    @Override
    public String getId() {
        return maskedField.getId();
    }

    @Override
    public String getName() {
        return maskedField.getName();
    }

    @Override
    public String getDescription() {
        return maskedField.getDescription();
    }
}
