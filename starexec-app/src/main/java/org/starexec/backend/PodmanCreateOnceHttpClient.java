package org.starexec.backend;

import com.github.dockerjava.transport.DockerHttpClient;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Objects;

/** Prevents transport-level replay of container creation after an ambiguous response. */
final class PodmanCreateOnceHttpClient implements DockerHttpClient {
    private final DockerHttpClient delegate;

    PodmanCreateOnceHttpClient(DockerHttpClient delegate) {
        this.delegate=Objects.requireNonNull(delegate);
    }

    @Override
    public Response execute(Request request) {
        String path=request.path().split("\\?",2)[0];
        byte[] bytes=request.bodyBytes();
        if (!"POST".equals(request.method()) || !path.matches("(?:/v[0-9]+\\.[0-9]+)?/containers/create")
                || bytes==null) return delegate.execute(request);
        // The Apache transport treats stream entities as non-repeatable, including on 429/503.
        InputStream body=new ByteArrayInputStream(bytes);
        return delegate.execute(new Request() {
            @Override public String method() { return request.method(); }
            @Override public String path() { return request.path(); }
            @Override public byte[] bodyBytes() { return null; }
            @Override public InputStream body() { return body; }
            @Override public InputStream hijackedInput() { return request.hijackedInput(); }
            @Override public Map<String,String> headers() { return request.headers(); }
        });
    }

    @Override
    public void close() throws IOException { delegate.close(); }
}
