package org.qubership.integration.platform.camelk.naming.validation;

import java.util.regex.Pattern;

public final class K8sNames {
    public static final int K8S_RESOURCE_NAME_LENGTH_LIMIT = 63;
    public static final Pattern K8S_RESOURCE_NAME_PATTERN =
        Pattern.compile("[a-z0-9]([-a-z0-9]*[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*");

    private K8sNames() {
    }
}
