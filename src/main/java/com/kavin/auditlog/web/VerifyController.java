package com.kavin.auditlog.web;

import com.kavin.auditlog.service.ChainVerificationService;
import com.kavin.auditlog.web.dto.ChainVerificationResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/audit/verify")
public class VerifyController {

    private final ChainVerificationService verificationService;

    public VerifyController(ChainVerificationService verificationService) {
        this.verificationService = verificationService;
    }

    @GetMapping
    public ChainVerificationResponse verify() {
        return verificationService.verify();
    }
}
