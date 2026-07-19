package com.notenest.controller;

import com.notenest.dto.PaymentReq;
import com.notenest.service.BidService;
import com.notenest.service.PaymentService;
import com.siot.IamportRestClient.IamportClient;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;


@Controller
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/api/payment")
public class PaymentController {
    private final PaymentService paymentService;

    private IamportClient iamportClient;

    private final BidService bidService;

    @Value("${imp.api.key}")
    private String apiKey;

    @Value("${imp.api.secretkey}")
    private String secretKey;

    @PostConstruct
    public void init() {
        this.iamportClient = new IamportClient(apiKey, secretKey);
    }

    //결제 생성
    @PostMapping("/validate")
    public ResponseEntity<String> createPayment(@RequestBody PaymentReq paymentReq) {
        try {
            bidService.processAuctionEnd(paymentReq.getMusicUuid());
            return ResponseEntity.status(HttpStatus.CREATED).body("Payment processing initiated.");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to process payment.");
        }
    }

    // 결제 처리 -> 결제 대기 목록에 결제하기 Url
    @PostMapping("/process")
    public ResponseEntity<?> processPayment(@RequestBody PaymentReq paymentReq) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String loggedInUserEmail = authentication.getName();

        Map<String, String> response = new HashMap<>(); // JSON 반환
        try {
            paymentService.processPayment(paymentReq, loggedInUserEmail);
            response.put("message", "Payment processed successfully.");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to process payment.");
            response.put("error", "Failed to process payment: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
}
