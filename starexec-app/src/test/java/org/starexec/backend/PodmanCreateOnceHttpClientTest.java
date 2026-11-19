package org.starexec.backend;

import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.*;

class PodmanCreateOnceHttpClientTest {
    private DockerHttpClient client(int port) {
        return new PodmanCreateOnceHttpClient(new ApacheDockerHttpClient.Builder()
                .dockerHost(URI.create("tcp://127.0.0.1:"+port))
                .connectionTimeout(Duration.ofSeconds(3))
                .responseTimeout(Duration.ofSeconds(5)).build());
    }

    @ParameterizedTest
    @ValueSource(ints={429,503})
    @Timeout(20)
    void creationIsNotRetriedAfterAnAmbiguousServerResponse(int status) throws Exception {
        AtomicInteger received=new AtomicInteger();
        AtomicReference<byte[]> receivedBytes=new AtomicReference<>();
        AtomicReference<String> receivedType=new AtomicReference<>();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        byte[] payload="{\"Image\":\"synthetic\"}".getBytes(StandardCharsets.UTF_8);
        server.createContext("/v1.44/containers/create",exchange->{
            try {
                received.incrementAndGet();
                receivedBytes.set(exchange.getRequestBody().readAllBytes());
                receivedType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                exchange.sendResponseHeaders(status,-1);
            } finally { exchange.close(); }
        });
        server.start();
        try (DockerHttpClient client=client(server.getAddress().getPort())) {
            var request=DockerHttpClient.Request.builder().method("POST")
                    .path("/v1.44/containers/create?name=starexec-build-1")
                    .putHeader("Content-Type","application/json").bodyBytes(payload).build();
            try (var response=client.execute(request)) { assertEquals(status,response.getStatusCode()); }
            assertEquals(1,received.get(),"A create response never authorizes another POST");
            assertArrayEquals(payload,receivedBytes.get());
            assertEquals("application/json",receivedType.get());
        } finally { server.stop(0); }
    }

    @Test
    @Timeout(20)
    void creationIsNotRetriedWhenTheResponseConnectionIsLost() throws Exception {
        AtomicInteger received=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/containers/create",exchange->{
            received.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.close();
        });
        server.start();
        try (DockerHttpClient client=client(server.getAddress().getPort())) {
            var request=DockerHttpClient.Request.builder().method("POST").path("/containers/create")
                    .putHeader("Content-Type","application/json").bodyBytes(new byte[]{'{','}'}).build();
            assertThrows(RuntimeException.class,()->client.execute(request));
            assertEquals(1,received.get());
        } finally { server.stop(0); }
    }

    @ParameterizedTest
    @ValueSource(ints={429,503})
    @Timeout(20)
    void startPermissionCannotBeReplayedByHttpRecovery(int status) throws Exception {
        assertSingleLifecycleWrite("/containers/"+"a".repeat(64)+"/start",status);
    }

    @ParameterizedTest
    @ValueSource(ints={429,503})
    @Timeout(20)
    void releasePermissionCannotBeReplayedByHttpRecovery(int status) throws Exception {
        assertSingleLifecycleWrite("/v1.44/containers/"+"a".repeat(64)+"/kill?signal=USR2",status);
    }

    private void assertSingleLifecycleWrite(String path,int status) throws Exception {
        AtomicInteger received=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext(path.split("\\?",2)[0],exchange->{
            try {received.incrementAndGet();exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(status,-1);}
            finally {exchange.close();}
        });
        server.start();
        try(DockerHttpClient client=client(server.getAddress().getPort())) {
            var request=DockerHttpClient.Request.builder().method("POST").path(path).build();
            try(var response=client.execute(request)) {assertEquals(status,response.getStatusCode());}
            assertEquals(1,received.get(),"One lifecycle permission must not become two HTTP writes");
        } finally {server.stop(0);}
    }

    @ParameterizedTest
    @ValueSource(strings={"/containers/json","/containers/create-extra","/v1.44/containers/id/start"})
    void unrelatedRequestsAreDelegatedUnchanged(String path) throws Exception {
        DockerHttpClient delegate=mock(DockerHttpClient.class);
        var request=DockerHttpClient.Request.builder().method("POST").path(path).bodyBytes(new byte[]{'{','}'}).build();
        var response=mock(DockerHttpClient.Response.class);
        when(delegate.execute(request)).thenReturn(response);
        try (var client=new PodmanCreateOnceHttpClient(delegate)) {
            assertSame(response,client.execute(request));
            verify(delegate).execute(same(request));
        }
        verify(delegate).close();
    }
}
