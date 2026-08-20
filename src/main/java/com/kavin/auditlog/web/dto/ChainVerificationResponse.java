package com.kavin.auditlog.web.dto;

public record ChainVerificationResponse(
        boolean intact,
        long recordsChecked,
        Long firstBrokenSequenceNumber,
        String violationType,
        String details
) {
    public static ChainVerificationResponse intact(long recordsChecked) {
        return new ChainVerificationResponse(true, recordsChecked, null, null, null);
    }

    public static ChainVerificationResponse broken(long recordsChecked, long sequenceNumber,
                                                     String violationType, String details) {
        return new ChainVerificationResponse(false, recordsChecked, sequenceNumber, violationType, details);
    }
}
