package org.qubership.integration.platform.engine.util;

import com.netcracker.cloud.quarkus.security.auth.M2MManager;
import com.netcracker.cloud.security.core.auth.Token;
import com.netcracker.cloud.security.core.utils.k8s.AudienceName;
import com.netcracker.cloud.security.core.utils.k8s.KubernetesAudienceToken;
import org.springframework.lang.Nullable;

import java.util.Optional;

public class M2MTokenUtils {

    @Deprecated
    public static String getOldM2MToken() {
        return Optional.ofNullable(M2MManager.getInstance().getToken())
                .map(Token::getTokenValue)
                .orElse("");
    }

    @Nullable
    public static String getDefaultM2MToken() {
        return getM2MToken(AudienceName.NETCRACKER);
    }

    @Nullable
    public static String getM2MToken(String audience) {
        return KubernetesAudienceToken.getToken(audience);
    }
}
