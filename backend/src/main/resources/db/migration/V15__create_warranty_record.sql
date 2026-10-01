-- V15: warranty_record
-- Stores warranty data loaded from a CSV export (e.g., ITAM / OEM portal).
-- Keyed by serial_number so it can be joined to hardware_health_report.
-- The ingest service fills hardware_health_report.warranty_status and
-- warranty_days_remaining by matching on serial number.

CREATE TABLE warranty_record (
    id                 UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    serial_number      TEXT        NOT NULL,
    vendor             TEXT        NOT NULL,
    -- ISO 8601 date string stored as TEXT to survive OEM format variation.
    -- The ingest service normalises to a LocalDate before persisting.
    expires_on         DATE        NOT NULL,
    -- Free-text field from the CSV; nullable.
    product_name       TEXT,
    -- e.g. "CSV_UPLOAD"; prepared for OEM API sources later.
    source             TEXT        NOT NULL DEFAULT 'CSV_UPLOAD',
    uploaded_by        TEXT        NOT NULL,
    uploaded_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Allow re-uploading a corrected CSV without losing history: the latest
    -- row per serial_number wins.  Uniqueness is NOT enforced so duplicates
    -- from overlapping uploads are preserved (application layer picks latest).
    CONSTRAINT warranty_record_serial_not_blank CHECK (trim(serial_number) <> '')
);

-- Lookup path used by the join-to-hardware query: serial_number → latest row.
CREATE INDEX idx_warranty_record_serial ON warranty_record (serial_number, uploaded_at DESC);
