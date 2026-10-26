-- Stable identities encode both issuer and subject, so the evidence outbox needs the
-- same bound as policy author and reviewer columns.
alter table evidence_outbox alter column subject type varchar(512);
