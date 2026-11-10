package org.starexec.data.database;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.starexec.backend.storage.DedicatedStorageResource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Conserved ordinary output allocation; readback alone never authorizes filesystem writes. */
public final class JobOutputReservations {
    private static final ObjectMapper JSON=new ObjectMapper();
    private JobOutputReservations() { }

    public record Reservation(JobExecutionProtocol.Receipt request,Path parent,String parentIdentity,Path target,
            long limitBytes,int entryLimit,long chargedBytes,Instant copyStartedAt,String directoryIdentity,
            Long verifiedBytes,Instant verifiedAt,Instant releasedAt) { }

    /** Only a first confirmed receipt books a new destination; ambiguous replies preserve the existing charge. */
    public static boolean reserve(Connection connection,JobStorageLeases.Lease lease,DedicatedStorageResource resource,
            JobExecutionProtocol.Receipt request) throws SQLException {
        if(request==null || request.phase()!=JobExecutionProtocol.Phase.OUTPUT_REQUEST) return false;
        return JobExecutionProtocol.recordReceipt(connection,lease,resource,request);
    }

    public static Optional<Reservation> find(Connection connection,UUID token) throws SQLException {
        try(var query=connection.prepareStatement("SELECT * FROM starexec.job_output_storage_reservations WHERE execution_token=?")) {
            query.setObject(1,token);
            try(var row=query.executeQuery()) {
                if(!row.next()) return Optional.empty();
                var request=JobExecutionProtocol.findReceipt(connection,token,JobExecutionProtocol.Phase.OUTPUT_REQUEST)
                        .orElseThrow(()->new SQLException("Ordinary output allocation has no conserved request"));
                try {
                    JsonNode payload=JSON.readTree(request.payload()),facts=payload.path("facts");
                    Path parent=Path.of(row.getString("parent_path")),target=Path.of(row.getString("target_path"));
                    long limit=row.getLong("limit_bytes"),charged=row.getLong("charged_bytes");int entries=row.getInt("entry_limit");
                    String parentIdentity=row.getString("parent_identity"),manifest=facts.path("source_manifest_payload").textValue();
                    if(!request.operation().equals(row.getObject("operation_token",UUID.class))
                            || payload.path("owner_id").intValue()!=row.getInt("origin_owner_id")
                            || payload.path("pair_id").intValue()!=row.getInt("origin_pair_id")
                            || !parent.isAbsolute() || parent.getParent()==null || !parent.equals(parent.normalize())
                            || !parent.resolve("job-"+token).equals(target) || parentIdentity==null || parentIdentity.isBlank()
                            || !parent.toString().equals(facts.path("parent_path").textValue())
                            || !target.toString().equals(facts.path("target_path").textValue())
                            || !parentIdentity.equals(facts.path("parent_identity").textValue())
                            || !facts.path("limit_bytes").isIntegralNumber() || facts.path("limit_bytes").longValue()!=limit
                            || !facts.path("entry_limit").isIntegralNumber() || facts.path("entry_limit").intValue()!=entries
                            || limit<0 || entries<2 || charged<0 || charged>limit || manifest==null
                            || !JobExecutionProtocol.digest(manifest).equals(row.getString("source_manifest_sha256"))
                            || !row.getString("source_manifest_sha256").equals(facts.path("source_manifest_sha256").textValue()))
                        throw new SQLException("Ordinary output allocation differs from its conserved request");
                    Long verified=(Long)row.getObject("verified_bytes");
                    return Optional.of(new Reservation(request,parent,parentIdentity,target,limit,entries,charged,
                            instant(row.getTimestamp("copy_started_at")),row.getString("directory_identity"),verified,
                            instant(row.getTimestamp("verified_at")),instant(row.getTimestamp("released_at"))));
                } catch(java.io.IOException | IllegalArgumentException | NullPointerException invalid) {
                    throw new SQLException("Stored ordinary output allocation is invalid",invalid);
                }
            }
        }
    }

    private static Instant instant(Timestamp value) { return value==null?null:value.toInstant(); }
}
