package org.qubership.integration.platform.engine.camel.resources;

import com.netcracker.cloud.quarkus.security.auth.M2MManager;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.impl.engine.DefaultResourceResolvers;
import org.apache.camel.spi.ContentTypeAware;
import org.apache.camel.spi.Resource;
import org.apache.camel.spi.annotations.ResourceResolver;
import org.apache.camel.support.ResourceSupport;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;

@Slf4j
@ResourceResolver(DefaultResourceResolvers.HttpResolver.SCHEME)
public class M2MHttpResourceResolver extends DefaultResourceResolvers.HttpResolver {

    @Override
    public Resource createResource(String location, String remaining) {
        log.debug("Resolving M2M-authenticated resource for location: {}", location);
        return new M2MAuthenticatedResource(location);
    }

    /**
     * Mirrors {@code org.apache.camel.impl.engine.DefaultResourceResolvers.HttpResource} with one addition:
     * every connection sets an {@code Authorization: Bearer} header obtained from {@link M2MManager}.
     *
     * <p><strong>Keep in sync with the Camel source when upgrading Camel.</strong>
     * Verify that {@link #exists()} and {@link #getInputStream()} still match the upstream implementation.
     */
    private static final class M2MAuthenticatedResource extends ResourceSupport implements ContentTypeAware {
        private String contentType;

        M2MAuthenticatedResource(String location) {
            super(DefaultResourceResolvers.HttpResolver.SCHEME, location);
        }

        @Override
        public boolean exists() {
            URLConnection connection = null;
            try {
                connection = URI.create(getLocation()).toURL().openConnection();
                connection.setRequestProperty("Authorization", "Bearer " + getToken());
                if (!(connection instanceof HttpURLConnection httpURLConnection)) {
                    return connection.getContentLengthLong() > 0L;
                }
                return httpURLConnection.getResponseCode() == HttpURLConnection.HTTP_OK;
            } catch (IOException e) {
                throw new IllegalArgumentException(e);
            } finally {
                if (connection instanceof HttpURLConnection httpURLConnection) {
                    httpURLConnection.disconnect();
                }
            }
        }

        @Override
        public InputStream getInputStream() throws IOException {
            URLConnection connection = URI.create(getLocation()).toURL().openConnection();
            connection.setRequestProperty("Authorization", "Bearer " + getToken());
            connection.setUseCaches(false);
            try {
                setContentType(connection.getContentType());
                return connection.getInputStream();
            } catch (IOException e) {
                if (connection instanceof HttpURLConnection httpURLConnection) {
                    httpURLConnection.disconnect();
                }
                throw e;
            }
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public void setContentType(String contentType) {
            this.contentType = contentType;
        }

        private static String getToken() {
            return M2MManager.getInstance().getToken().getTokenValue();
        }
    }
}
