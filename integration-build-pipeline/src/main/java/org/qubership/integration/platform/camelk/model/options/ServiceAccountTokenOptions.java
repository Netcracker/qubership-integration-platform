package org.qubership.integration.platform.camelk.model.options;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class ServiceAccountTokenOptions {
    private String name;
    private Integer defaultMode;
    private String mountPath;
    private String audience;
    private Long expirationSeconds;

    @Builder.Default
    private String path = "token";

    public static ServiceAccountTokenOptions valueOf(String spec) {
        String[] parts = spec.trim().split(";");
        String[] nameAndMountPath = parts[0].split("@", 2);
        if (nameAndMountPath.length != 2
            || nameAndMountPath[0].isBlank()
            || nameAndMountPath[1].isBlank()) {
            throw new IllegalArgumentException(
                "Invalid projected service account token '" + spec + "', expected <name>@<mountPath>[;key=value...]");
        }

        ServiceAccountTokenOptions token = new ServiceAccountTokenOptions();
        token.setName(nameAndMountPath[0].trim());
        token.setMountPath(nameAndMountPath[1].trim());
        for (int i = 1; i < parts.length; i++) {
            String[] attribute = parts[i].split("=", 2);
            if (attribute.length != 2) {
                throw new IllegalArgumentException("Invalid attribute '" + parts[i] + "' in '" + spec + "'");
            }

            String value = attribute[1].trim();
            switch (attribute[0].trim()) {
                case "defaultMode" -> token.setDefaultMode(Integer.valueOf(value));
                case "audience" -> token.setAudience(value);
                case "expirationSeconds" -> token.setExpirationSeconds(Long.valueOf(value));
                case "path" -> token.setPath(value);
                default -> throw new IllegalArgumentException(
                    "Unknown attribute '" + attribute[0] + "' in '" + spec + "'");
            }
        }
        return token;
    }
}
