package org.qubership.integration.platform.runtime.catalog.model.constant;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class QcpConstants {

    public static final String CALLBACK_URL_HEADER = "X-Callback-Url";

    public static final String STATUS_ROLLOUT_IN_PROGRESS = "Rollout In Progress";
    public static final String STATUS_ROLLOUT_SUCCESS = "Rollout Success";
    public static final String STATUS_ROLLOUT_FAILED = "Rollout Failed";

    public static final String ERROR_CODE_INTERNAL = "QIP-QCP-0001";
    public static final String ERROR_CODE_IMPORT_FAILED = "QIP-QCP-0002";
    public static final String ERROR_CODE_EMPTY_PACKAGE = "QIP-QCP-0003";

    public static final String CHAINS_DIR_NAME = "chains";
    public static final String SERVICES_DIR_NAME = "services";
    public static final String VARIABLES_DIR_NAME = "variables";
}
