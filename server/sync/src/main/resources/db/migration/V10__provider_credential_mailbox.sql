-- Independent opaque provisioning mailbox. No workspace envelope/cursor/key semantics change.
-- One current request or OD-059 anti-replay tombstone per target/config.
CREATE TABLE provider_credential_mailbox (
    target_device_id TEXT NOT NULL REFERENCES device(device_id),
    provider_config_id TEXT NOT NULL,
    credential_revision BIGINT NOT NULL CHECK (credential_revision > 0),
    account_id TEXT NOT NULL REFERENCES account(account_id),
    provisioner_device_id TEXT REFERENCES device(device_id),
    delivery_state TEXT NOT NULL CHECK (delivery_state IN ('REQUESTED','DELIVERED','ACKNOWLEDGED','DELIVERY_EXPIRED')),
    canonical_envelope BYTEA,
    envelope_digest BYTEA,
    acknowledgement_result TEXT CHECK (acknowledgement_result IN ('INSTALLED','DUPLICATE')),
    created_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    PRIMARY KEY (target_device_id, provider_config_id),
    CHECK (octet_length(canonical_envelope) <= 16384),
    CHECK (envelope_digest IS NULL OR octet_length(envelope_digest) = 32),
    CONSTRAINT provider_credential_delivery_fields CHECK (
        (delivery_state = 'DELIVERY_EXPIRED'
            AND provisioner_device_id IS NULL AND canonical_envelope IS NULL
            AND envelope_digest IS NULL AND acknowledgement_result IS NULL
            AND created_at IS NULL AND expires_at IS NULL)
        OR (delivery_state <> 'DELIVERY_EXPIRED'
            AND provisioner_device_id IS NOT NULL AND created_at IS NOT NULL AND expires_at IS NOT NULL
            AND expires_at = created_at + INTERVAL '604800 seconds'
            AND ((delivery_state = 'REQUESTED' AND canonical_envelope IS NULL AND envelope_digest IS NULL AND acknowledgement_result IS NULL)
                OR (delivery_state = 'DELIVERED' AND canonical_envelope IS NOT NULL AND envelope_digest IS NOT NULL AND acknowledgement_result IS NULL)
                OR (delivery_state = 'ACKNOWLEDGED' AND canonical_envelope IS NULL AND envelope_digest IS NOT NULL AND acknowledgement_result IS NOT NULL)))
    )
);
CREATE INDEX provider_credential_assigned_idx ON provider_credential_mailbox(account_id, provisioner_device_id, delivery_state, target_device_id, provider_config_id);
