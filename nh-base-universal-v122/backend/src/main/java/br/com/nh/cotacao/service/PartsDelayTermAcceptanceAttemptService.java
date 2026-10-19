package br.com.nh.cotacao.service;

import br.com.nh.cotacao.dto.InspectionDtos.WebAuthnAssertionFinishRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class PartsDelayTermAcceptanceAttemptService {
    private final JdbcTemplate jdbc;

    public PartsDelayTermAcceptanceAttemptService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Attempt record(UUID termId, WebAuthnAssertionFinishRequest input) {
        UUID id = UUID.randomUUID();
        OffsetDateTime receivedAt = OffsetDateTime.now();
        jdbc.update("""
                insert into parts_delay_term_acceptance_attempts(
                    id,term_id,received_at,status,raw_id,credential_id,credential_type,
                    client_data_json,authenticator_data,assertion_signature,user_handle,created_at,updated_at
                ) values (?,?,?,'RECEIVED',?,?,?,?,?,?,?,now(),now())
                """,
                id, termId, receivedAt,
                input.rawId(), input.id(), input.type(), input.clientDataJSON(), input.authenticatorData(),
                input.signature(), input.userHandle()
        );
        return new Attempt(id, termId, receivedAt, input.rawId(), input.id(), input.type(),
                input.clientDataJSON(), input.authenticatorData(), input.signature(), input.userHandle());
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Attempt latestRecoverable(UUID termId) {
        return jdbc.query("""
                select id,term_id,received_at,raw_id,credential_id,credential_type,
                       client_data_json,authenticator_data,assertion_signature,user_handle
                  from parts_delay_term_acceptance_attempts
                 where term_id=? and status='RECEIVED'
                 order by received_at desc
                 limit 1
                """, rs -> rs.next() ? new Attempt(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("term_id")),
                rs.getObject("received_at", OffsetDateTime.class),
                rs.getString("raw_id"),
                rs.getString("credential_id"),
                rs.getString("credential_type"),
                rs.getString("client_data_json"),
                rs.getString("authenticator_data"),
                rs.getString("assertion_signature"),
                rs.getString("user_handle")
        ) : null, termId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markVerified(UUID id) {
        jdbc.update("""
                update parts_delay_term_acceptance_attempts
                   set status='VERIFIED',verified_at=now(),validation_error=null,updated_at=now()
                 where id=?
                """, id);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRejected(UUID id, String error) {
        String message = error == null ? "Falha de validação WebAuthn" : error.trim();
        if (message.length() > 2000) message = message.substring(0, 2000);
        jdbc.update("""
                update parts_delay_term_acceptance_attempts
                   set status='REJECTED',validation_error=?,updated_at=now()
                 where id=?
                """, message, id);
    }

    public record Attempt(
            UUID id,
            UUID termId,
            OffsetDateTime receivedAt,
            String rawId,
            String credentialId,
            String type,
            String clientDataJSON,
            String authenticatorData,
            String signature,
            String userHandle
    ) {
        public WebAuthnAssertionFinishRequest toRequest() {
            return new WebAuthnAssertionFinishRequest(
                    credentialId,
                    rawId,
                    type,
                    clientDataJSON,
                    authenticatorData,
                    signature,
                    userHandle
            );
        }
    }
}
