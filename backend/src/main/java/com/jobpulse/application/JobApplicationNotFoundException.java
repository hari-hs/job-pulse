package com.jobpulse.application;

public class JobApplicationNotFoundException extends RuntimeException {

    public JobApplicationNotFoundException(Long id) {
        super("Job application not found: " + id);
    }
}
