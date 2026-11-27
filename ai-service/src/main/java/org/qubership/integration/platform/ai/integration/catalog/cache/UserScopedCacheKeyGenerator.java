package org.qubership.integration.platform.ai.integration.catalog.cache;

import io.quarkus.cache.CacheKeyGenerator;
import jakarta.enterprise.context.ApplicationScoped;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.qubership.integration.platform.ai.chat.UserTokenStore;

/** Keys a catalog read by caller as well as arguments, so one user's authorized read is never served to another. */
@ApplicationScoped
public class UserScopedCacheKeyGenerator implements CacheKeyGenerator {

  @Override
  public Object generate(Method method, Object... methodParams) {
    String caller = UserTokenStore.current().map(UserScopedCacheKeyGenerator::sha256).orElse("service");
    return List.of(caller, Arrays.asList(methodParams));
  }

  private static String sha256(String token) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
