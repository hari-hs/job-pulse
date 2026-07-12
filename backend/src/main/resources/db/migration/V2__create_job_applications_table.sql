CREATE TABLE job_applications (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    company_name VARCHAR(255) NOT NULL,
    job_title VARCHAR(255) NOT NULL,
    job_url VARCHAR(2048),
    status VARCHAR(30) NOT NULL,
    applied_date DATE,
    location VARCHAR(255),
    source VARCHAR(255),
    notes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT chk_job_applications_status CHECK (status IN (
        'APPLIED', 'ONLINE_ASSESSMENT', 'PHONE_SCREEN', 'ONSITE', 'OFFER', 'REJECTED', 'WITHDRAWN'
    ))
);

CREATE INDEX idx_job_applications_user_id ON job_applications(user_id);
CREATE INDEX idx_job_applications_status ON job_applications(status);
CREATE INDEX idx_job_applications_applied_date ON job_applications(applied_date);
