-- V3 created this FK without ON DELETE CASCADE, which meant DELETE
-- /api/applications/{id} started failing with a 500 as soon as an
-- application had any status history -- i.e. always, since M3 records an
-- initial history entry on every create(). Found during M4 verification.
ALTER TABLE application_status_history
    DROP CONSTRAINT application_status_history_application_id_fkey,
    ADD CONSTRAINT application_status_history_application_id_fkey
        FOREIGN KEY (application_id) REFERENCES job_applications(id) ON DELETE CASCADE;
