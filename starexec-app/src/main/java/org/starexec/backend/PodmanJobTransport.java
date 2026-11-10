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
    private static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private PodmanJobTransport() { }

    static Optional<String> createOnce(Connection connection,PodmanJobCreation policy,DockerClient provider,
            String socket,int connectSeconds,int responseSeconds) throws IOException,SQLException {
        if (policy==null || connectSeconds<1 || responseSeconds<1)
            throw new IOException("Ordinary creation policy or transport deadlines are unavailable");
        var request=policy.creation();
        if (request.transport()!=JobExecutionProtocol.Transport.CURL)
            throw new IOException("Ordinary creation requires the preselected curl transport");
        PodmanPrivateBuild.requireLocalRootlessProvider(provider,socket);
        String endpoint=socket.startsWith("unix://")?socket.substring(7):socket;
        Path directory=Files.createTempDirectory("starexec-ordinary-create-");Path input=directory.resolve("request.json");
        Process child=null;Thread reader=null;
        try {
            Files.write(input,request.payload().getBytes(StandardCharsets.UTF_8));
            if (!policy.claimCreation(connection)) return Optional.empty();
            long started=System.nanoTime();long timeout=TimeUnit.SECONDS.toNanos((long)responseSeconds+1);
            child=new ProcessBuilder("curl","-q","--silent","--noproxy","*","--retry","0","--proto","=http",
                    "--connect-timeout",Integer.toString(connectSeconds),"--max-time",Integer.toString(responseSeconds),
                    "--request","POST","--unix-socket",endpoint,"--header","Content-Type: application/json",
                    "--data-binary","@-","--write-out","\n%{http_code}",
                    "http://localhost/v1.41/containers/create?name="+request.name())
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
                        || !"201".equals(new String(reply,reply.length-3,3,StandardCharsets.US_ASCII)))
                    throw new IOException("Ordinary creation did not return HTTP 201; outcome is unresolved");
                var value=JSON.readTree(Arrays.copyOf(reply,reply.length-4));var id=value==null?null:value.get("Id");
                if (value==null || !value.isObject() || id==null || !id.isTextual() || !id.textValue().matches("[0-9a-f]{64}"))
                    throw new IOException("Ordinary creation returned no immutable provider identity; outcome is unresolved");
                return Optional.of(id.textValue());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Ordinary creation was interrupted; provider outcome is unresolved",interrupted);
            } catch (ExecutionException | TimeoutException unknown) {
                throw new IOException("Ordinary creation response is unavailable; provider outcome is unresolved",unknown);
            }
        } finally {cleanup(child,reader,input,directory);}
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
