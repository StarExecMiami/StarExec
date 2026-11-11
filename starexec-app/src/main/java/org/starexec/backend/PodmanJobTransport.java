package org.starexec.backend;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import org.starexec.data.database.JobExecutionProtocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** One acknowledged request permits one send; an uncertain response never permits another. */
final class PodmanJobTransport {
    private static final int MAX_REPLY=16*1024;
    private static final int MAX_INSPECTION=16*1024*1024;
    private static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final ObjectMapper PROVIDER_JSON=com.github.dockerjava.core.DockerClientConfig.getDefaultObjectMapper().copy()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    @FunctionalInterface private interface Decision {boolean consume() throws IOException,SQLException;}
    @FunctionalInterface private interface Reply<T> {T read(com.github.dockerjava.transport.DockerHttpClient.Response response) throws IOException;}

    private PodmanJobTransport() { }

    static Optional<String> createOnce(Connection connection,PodmanJobCreation policy,DockerClient provider,
            String socket,int connectSeconds,int responseSeconds) throws IOException,SQLException {
        var request=check(policy,JobExecutionProtocol.Transport.CURL,provider,socket,connectSeconds,responseSeconds);
        return curlPost(socket,connectSeconds,responseSeconds,"/v1.41/containers/create?name="+request.name(),request.payload(),201,
                ()->policy.claimCreation(connection));
    }

    private static Optional<String> curlPost(String socket,int connectSeconds,int responseSeconds,String path,String payload,
            int expectedStatus,Decision first) throws IOException,SQLException {
        String endpoint=socket.startsWith("unix://")?socket.substring(7):socket;
        Path directory=Files.createTempDirectory("starexec-ordinary-create-");Path input=directory.resolve("request.json");
        Process child=null;Thread reader=null;
        try {
            Files.write(input,payload.getBytes(StandardCharsets.UTF_8));
            if (!first.consume()) return Optional.empty();
            long started=System.nanoTime();long timeout=TimeUnit.SECONDS.toNanos((long)responseSeconds+1);
            child=new ProcessBuilder("curl","-q","--silent","--noproxy","*","--retry","0","--proto","=http",
                    "--connect-timeout",Integer.toString(connectSeconds),"--max-time",Integer.toString(responseSeconds),
                    "--request","POST","--unix-socket",endpoint,"--header","Content-Type: application/json",
                    "--data-binary","@-","--write-out","\n%{http_code}",
                    "http://localhost"+path)
                    .redirectInput(input.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            Process process=child;
            var receive=new FutureTask<byte[]>(()->{
                try(var stream=process.getInputStream()) {return stream.readNBytes(MAX_REPLY+5);}
            });
            reader=new Thread(receive,"ordinary-create-response");reader.setDaemon(true);reader.start();
            try {
                if (!process.waitFor(timeout,TimeUnit.NANOSECONDS))
                    throw new IOException("Ordinary creation timed out; provider outcome is unresolved");
                long remaining=timeout-(System.nanoTime()-started);
                if (remaining<=0) throw new IOException("Ordinary creation response deadline expired; outcome is unresolved");
                byte[] reply=receive.get(remaining,TimeUnit.NANOSECONDS);
                if (reply.length>MAX_REPLY+4) throw new IOException("Ordinary creation response exceeds its bound; outcome is unresolved");
                if (process.exitValue()!=0) throw new IOException("Ordinary creation transport exited with code "+process.exitValue()+"; outcome is unresolved");
                if (reply.length<4 || reply[reply.length-4]!='\n'
                        || !Integer.toString(expectedStatus).equals(new String(reply,reply.length-3,3,StandardCharsets.US_ASCII)))
                    throw new IOException("Ordinary lifecycle request did not return HTTP "+expectedStatus+"; outcome is unresolved");
                return Optional.of(acknowledgement(Arrays.copyOf(reply,reply.length-4),expectedStatus));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Ordinary creation was interrupted; provider outcome is unresolved",interrupted);
            } catch (ExecutionException | TimeoutException unknown) {
                throw new IOException("Ordinary creation response is unavailable; provider outcome is unresolved",unknown);
            }
        } finally {cleanup(child,reader,input,directory);}
    }

    static Optional<String> createJavaOnce(Connection connection,PodmanJobCreation policy,DockerClient provider,
            String socket,int connectSeconds,int responseSeconds) throws IOException,SQLException {
        var request=check(policy,JobExecutionProtocol.Transport.JAVA,provider,socket,connectSeconds,responseSeconds);
        return javaExchange(socket,connectSeconds,responseSeconds,"POST","/v1.41/containers/create?name="+request.name(),
                request.payload().getBytes(StandardCharsets.UTF_8),()->policy.claimCreation(connection),response->postReply(response,201));
    }

    private static <T> Optional<T> javaExchange(String socket,int connectSeconds,int responseSeconds,String method,String path,
            byte[] body,Decision first,Reply<T> reply) throws IOException,SQLException {
        String endpoint=socket.startsWith("unix://")?socket.substring(7):socket;
        final java.net.URI address;
        try {address=new java.net.URI("unix",null,endpoint,null,null);}
        catch(java.net.URISyntaxException invalid) {throw new IOException("Ordinary provider socket address is invalid",invalid);}
        var builder=com.github.dockerjava.transport.DockerHttpClient.Request.builder().method(method).path(path);
        if(body!=null) builder.putHeader("Content-Type","application/json").bodyBytes(body);
        // An empty non-repeatable entity prevents automatic replay of a lost inspection response.
        else builder.body(new java.io.ByteArrayInputStream(new byte[0]));
        var message=builder.build();
        var http=new PodmanCreateOnceHttpClient(new com.github.dockerjava.httpclient5.ApacheDockerHttpClient.Builder()
                .dockerHost(address).maxConnections(1).connectionTimeout(java.time.Duration.ofSeconds(connectSeconds))
                .responseTimeout(java.time.Duration.ofSeconds(responseSeconds)).build());
        Thread worker=null;
        try {
            if(!first.consume()) return Optional.empty();
            var operation=new FutureTask<T>(()->{
                try(var response=http.execute(message)) {
                    return reply.read(response);
                }
            });
            worker=new Thread(operation,"ordinary-java-create");worker.setDaemon(true);worker.start();
            try {return Optional.of(operation.get((long)responseSeconds+1,TimeUnit.SECONDS));}
            catch(InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Ordinary Java creation was interrupted; outcome is unresolved",interrupted);
            } catch(ExecutionException | TimeoutException unknown) {
                throw new IOException("Ordinary Java creation response is unresolved",unknown);
            }
        } finally {cleanup(http,worker);}
    }

    /** Direct acknowledgements may start only the trusted hold; historical requests never permit replay. */
    static Optional<String> createAndStartHeldOnce(Connection connection,PodmanJobCreation policy,DockerClient provider,
            String socket,int connectSeconds,int responseSeconds) throws IOException,SQLException {
        if(policy==null) throw new IOException("Ordinary creation policy is unavailable");
        var request=policy.creation();boolean curl=request.transport()==JobExecutionProtocol.Transport.CURL;
        var created=curl?createOnce(connection,policy,provider,socket,connectSeconds,responseSeconds)
                :createJavaOnce(connection,policy,provider,socket,connectSeconds,responseSeconds);
        if(created.isEmpty()) return Optional.empty();
        String container=created.orElseThrow();
        var inspection=inspectOnce(socket,connectSeconds,responseSeconds,container);
        Decision claim=()->policy.claimStart(connection,container,inspection);
        String path="/v1.41/containers/"+container+"/start";
        var started=curl?curlPost(socket,connectSeconds,responseSeconds,path,"",204,claim)
                :javaExchange(socket,connectSeconds,responseSeconds,"POST",path,new byte[0],claim,response->postReply(response,204));
        if(started.isEmpty()) throw new IOException("No first acknowledged ordinary hold-start claim is available");
        return created;
    }

    private static com.github.dockerjava.api.command.InspectContainerResponse inspectOnce(String socket,int connectSeconds,int responseSeconds,
            String container) throws IOException,SQLException {
        return javaExchange(socket,connectSeconds,responseSeconds,"GET","/v1.41/containers/"+container+"/json",null,()->true,response->{
            if(response.getStatusCode()!=200 || response.getBody()==null) throw new IOException("Ordinary created inspection is unavailable");
            byte[] bytes=response.getBody().readNBytes(MAX_INSPECTION+1);
            if(bytes.length>MAX_INSPECTION) throw new IOException("Ordinary created inspection exceeds its bound");
            return PROVIDER_JSON.readValue(bytes,com.github.dockerjava.api.command.InspectContainerResponse.class);
        }).orElseThrow(()->new IOException("Ordinary created inspection is unavailable"));
    }

    static boolean releaseHeldOnce(Connection connection,PodmanJobCreation policy,DockerClient provider,String socket,String container,
            org.starexec.backend.storage.BuildExecutionRuntime.Identity identity,java.util.Map<String,String> forbiddenNamespaces,
            int connectSeconds,int responseSeconds) throws IOException,SQLException,InterruptedException {
        if(policy==null || !policy.unreleasedHold(connection,container)) return false;
        var request=check(policy,policy.creation().transport(),provider,socket,connectSeconds,responseSeconds);
        var before=inspectOnce(socket,connectSeconds,responseSeconds,container);
        var held=PodmanJobObservation.held(request,container,before);
        var runtime=policy.captureRuntime(connection,container,before,identity,forbiddenNamespaces);
        final String response;
        try {response=PodmanPrivateBuild.collect(provider,container,runtime);}
        catch(RuntimeException invalid) {throw new IOException("Ordinary runtime observation failed",invalid);}
        var observation=runtime.verify(response);
        policy.preparation(connection).requireCurrent(connection);
        var after=PodmanJobObservation.held(request,container,inspectOnce(socket,connectSeconds,responseSeconds,container));
        if(!held.equals(after)) throw new IOException("Ordinary held provider changed during kernel observation");
        Decision claim=()->policy.claimRelease(connection,container,observation,held);
        String path="/v1.41/containers/"+container+"/kill?signal=USR2";
        var sent=request.transport()==JobExecutionProtocol.Transport.CURL
                ?curlPost(socket,connectSeconds,responseSeconds,path,"",204,claim)
                :javaExchange(socket,connectSeconds,responseSeconds,"POST",path,new byte[0],claim,result->postReply(result,204));
        return sent.isPresent();
    }

    private static String postReply(com.github.dockerjava.transport.DockerHttpClient.Response response,int expectedStatus) throws IOException {
        if(response.getStatusCode()!=expectedStatus)
            throw new IOException("Ordinary lifecycle request did not return HTTP "+expectedStatus+"; outcome is unresolved");
        if(response.getBody()==null && expectedStatus!=204) throw new IOException("Ordinary lifecycle response is unavailable");
        byte[] bytes=response.getBody()==null?new byte[0]:response.getBody().readNBytes(MAX_REPLY+1);
        return acknowledgement(bytes,expectedStatus);
    }

    private static String acknowledgement(byte[] bytes,int status) throws IOException {
        if(status==201) return identity(bytes);
        if(status!=204 || bytes.length!=0) throw new IOException("Ordinary hold start did not return an empty acknowledgement; outcome is unresolved");
        return "";
    }

    private static JobExecutionProtocol.Creation check(PodmanJobCreation policy,JobExecutionProtocol.Transport transport,
            DockerClient provider,String socket,int connectSeconds,int responseSeconds) throws IOException {
        if(policy==null || connectSeconds<1 || responseSeconds<1)
            throw new IOException("Ordinary creation policy or transport deadlines are unavailable");
        var request=policy.creation();
        if(request.transport()!=transport) throw new IOException("Ordinary creation transport differs from its conserved selection");
        PodmanPrivateBuild.requireLocalRootlessProvider(provider,socket);return request;
    }

    private static String identity(byte[] reply) throws IOException {
        if(reply.length>MAX_REPLY) throw new IOException("Ordinary creation response exceeds its bound; outcome is unresolved");
        var value=JSON.readTree(reply);var id=value==null?null:value.get("Id");
        if(value==null || !value.isObject() || id==null || !id.isTextual() || !id.textValue().matches("[0-9a-f]{64}"))
            throw new IOException("Ordinary creation returned no immutable provider identity; outcome is unresolved");
        return id.textValue();
    }

    private static void cleanup(com.github.dockerjava.transport.DockerHttpClient http,Thread worker) throws IOException {
        boolean interrupted=Thread.interrupted();IOException closeFailure=null;
        try {
            try {http.close();} catch(IOException failure) {closeFailure=failure;}
            if(worker!=null) {
                worker.interrupt();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(worker.isAlive() && System.nanoTime()<deadline) {
                    try {worker.join(50);} catch(InterruptedException signal) {interrupted=true;}
                }
                if(worker.isAlive()) {
                    var unresolved=new IOException("Ordinary Java transport worker remains unresolved");
                    if(closeFailure!=null) unresolved.addSuppressed(closeFailure);
                    throw unresolved;
                }
            }
            if(closeFailure!=null) throw closeFailure;
        } finally {if(interrupted) Thread.currentThread().interrupt();}
    }

    private static void cleanup(Process child,Thread reader,Path input,Path directory) throws IOException {
        boolean interrupted=Thread.interrupted();
        try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            if (child!=null) {
                if (child.isAlive()) child.destroyForcibly();
                while (child.isAlive() && System.nanoTime()<deadline) {
                    try {child.waitFor(50,TimeUnit.MILLISECONDS);}
                    catch (InterruptedException signal) {interrupted=true;}
                }
                if (child.isAlive()) throw new IOException("Ordinary transport cleanup could not confirm child termination");
                child.getInputStream().close();
            }
            if (reader!=null) {
                reader.interrupt();
                while (reader.isAlive() && System.nanoTime()<deadline) {
                    try {reader.join(50);}
                    catch (InterruptedException signal) {interrupted=true;}
                }
                if (reader.isAlive()) throw new IOException("Ordinary transport response reader remains unresolved");
            }
            Files.deleteIfExists(input);Files.delete(directory);
        } finally {if(interrupted) Thread.currentThread().interrupt();}
    }
}
