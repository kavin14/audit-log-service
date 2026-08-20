package com.kavin.auditlog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "audit.retention")
public class RetentionProperties {

    /** Records older than this (by recordedAt) are eligible for archival. */
    private int archiveAfterDays = 90;

    public int getArchiveAfterDays() {
        return archiveAfterDays;
    }

    public void setArchiveAfterDays(int archiveAfterDays) {
        this.archiveAfterDays = archiveAfterDays;
    }
}
