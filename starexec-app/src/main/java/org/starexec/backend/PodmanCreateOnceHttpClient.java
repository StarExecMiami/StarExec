package org.starexec.backend;

import com.github.dockerjava.transport.DockerHttpClient;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Objects;

/** Prevents transport-level replay of once-only container lifecycle requests. */
final class PodmanCreateOnceHttpClient implements DockerHttpClient {
    private final DockerHttpClient delegate;

    PodmanCreateOnceHttpClient(DockerHttpClient delegate) {
        this.delegate=Objects.requireNonNull(delegate);
    }

    @Override
    public Response execute(Request request) {
        String[] address=request.path().split("\\?",2);String path=address[0];
        String prefix="(?:/v[0-9]+\\.[0-9]+)?/containers/";
        boolean creation=path.matches(prefix+"create");
        boolean start=path.matches(prefix+"[0-9a-f]{64}/start");
        boolean release=path.matches(prefix+"[0-9a-f]{64}/kill") && address.length==2
                && java.util.Arrays.stream(address[1].split("&")).anyMatch(q->q.equals("signal=USR2") || q.equals("signal=SIGUSR2"));
        byte[] bytes=request.bodyBytes();
        if (!"POST".equals(request.method()) || !(creation || start || release)) return delegate.execute(request);
        // The Apache transport treats stream entities as non-repeatable, including on 429/503.
        InputStream original=bytes==null?request.body():new ByteArrayInputStream(bytes);
        InputStream body=original==null?new ByteArrayInputStream(new byte[0]):original;
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
