package com.notenest.service;

import com.notenest.domain.Bid;
import com.notenest.domain.Payment;
import com.notenest.domain.User;
import com.notenest.dto.PaymentReq;
import com.notenest.dto.PaymentRes;
import com.notenest.payment.PaymentGateway;
import com.notenest.repository.BidRepository;
import com.notenest.repository.PaymentRepository;
import com.notenest.repository.UserRepository;
import com.siot.IamportRestClient.exception.IamportResponseException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final BidRepository bidRepository;
    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final PaymentGateway paymentGateway;

    @Transactional
    public PaymentRes createPayment(PaymentReq paymentReq) throws IamportResponseException, IOException {
        try {
            // PG 응답 결제 금액(원 단위 정수). 소수·범위 초과는 게이트웨이에서 예외로 거른다.
            long paidAmount = paymentGateway.fetchPaidAmountWon(paymentReq.getImpUid());

            // 결제하려는 입찰 가져오기
            Bid bid = bidRepository.findById(paymentReq.getBidUuid())
                    .orElseThrow(() -> new IllegalArgumentException("Invalid bid UUID"));

            // 입찰가(원 단위 정수)와 그대로 비교 — 배율(*100) 없음(NB2 금액 계약).
            long priceToPay = bid.getPrice();

            // 결제된 금액과 입찰가 비교
            if (paidAmount != priceToPay) {
                log.warn("Payment amount {} does not match expected amount {}", paidAmount, priceToPay);
                // 결제 금액이 맞지 않을 경우 결제 취소하고 예외 발생
                paymentGateway.cancelPayment(paymentReq.getImpUid(), "결제 금액 불일치");
                throw new IllegalArgumentException("결제 금액이 맞지 않습니다.");
            }

            // 결제 정보 저장
            bid.setImpUid(paymentReq.getImpUid());
            bidRepository.save(bid);

            Payment payment = new Payment();
            payment.setImpUid(paymentReq.getImpUid());
            payment.setPrice(paidAmount);
            payment.setStatus("PAID");
            payment.setBid(bid);
            paymentRepository.save(payment);

            return new PaymentRes(bid.getBidUuid());
        } catch (IllegalArgumentException e) {
            log.warn("Payment validation failed: {}", e.getMessage());
            throw e;
        } catch (IamportResponseException | IOException e) {
            log.error("Failed to process payment.", e);
            throw e;
        }

    }


    // 수동 결제 처리
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processPayment(PaymentReq paymentReq, String loggedInUserEmail) throws IamportResponseException, IOException {
        Bid bid = bidRepository.findById(paymentReq.getBidUuid())
                .orElseThrow(() -> new IllegalArgumentException("Invalid bid UUID: " + paymentReq.getBidUuid()));

        log.info("Bid found: {}", bid);  // 로그 추가

        User user = userRepository.findByEmail(loggedInUserEmail);
        if (user == null) {
            throw new IllegalArgumentException("로그인 후 이용 가능합니다.");
        }

        log.info("User found: {}", user);  // 로그 추가

        if (!bid.getUser().getEmail().equals(loggedInUserEmail)) {
            throw new IllegalArgumentException("낙찰자만 결제를 진행할 수 있습니다.");
        }

        createPayment(paymentReq);

    }

}
