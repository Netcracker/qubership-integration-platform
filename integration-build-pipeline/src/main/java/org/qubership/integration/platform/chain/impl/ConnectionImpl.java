package org.qubership.integration.platform.chain.impl;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.qubership.integration.platform.chain.model.Connection;
import org.qubership.integration.platform.chain.model.Element;

@Data
@AllArgsConstructor
public class ConnectionImpl implements Connection {
    private Element from;
    private Element to;
}
