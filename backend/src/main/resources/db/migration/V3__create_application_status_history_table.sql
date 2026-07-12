CREATE TABLE application_status_history (
    id BIGSERIAL PRIMARY KEY,
    application_id BIGINT NOT NULL REFERENCES job_applications(id),
    status VARCHAR(30) NOT NULL,
    changed_at TIMESTAMP NOT NULL DEFAULT now(),
    note TEXT,
    CONSTRAINT chk_application_status_history_status CHECK (status IN (
        'APPLIED', 'ONLINE_ASSESSMENT', 'PHONE_SCREEN', 'ONSITE', 'OFFER', 'REJECTED', 'WITHDRAWN'
    ))
);

CREATE INDEX idx_application_status_history_application_id ON application_status_history(application_id);
