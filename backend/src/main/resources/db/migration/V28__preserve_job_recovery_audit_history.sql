-- Preserve recovery audit history when the referenced job or endpoint is deleted.
-- Keep the original identifiers as historical values and snapshot the endpoint MAC.

ALTER TABLE job_recovery_event
    ADD COLUMN endpoint_mac_address TEXT;

UPDATE job_recovery_event event
   SET endpoint_mac_address = endpoint.mac_address
  FROM endpoint
 WHERE endpoint.id = event.endpoint_id;

ALTER TABLE job_recovery_event
    ALTER COLUMN endpoint_mac_address SET NOT NULL;

ALTER TABLE job_recovery_event
    DROP CONSTRAINT IF EXISTS job_recovery_event_job_id_fkey;

ALTER TABLE job_recovery_event
    DROP CONSTRAINT IF EXISTS job_recovery_event_endpoint_id_fkey;
