package com.notenest.service;

import com.notenest.domain.Bid;
import com.notenest.domain.Payment;
import com.notenest.domain.User;
import com.notenest.dto.PaymentReq;
import com.notenest.dto.PaymentRes;
import com.notenest.repository.BidRepository;
import com.notenest.repository.PaymentRepository;
import com.notenest.repository.UserRepository;
import com.siot.IamportRestClient.IamportClient;
import com.siot.IamportRestClient.exception.IamportResponseException;
import com.siot.IamportRestClient.request.CancelData;
import com.siot.IamportRestClient.response.IamportResponse;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private IamportClient iamportClient;

    private final BidRepository bidRepository;
    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;

    @Value("${imp.api.key}")
    private String apiKey;

    @Value("${imp.api.secretkey}")
    private String secretKey;

    @PostConstruct
    public void inint() {
        this.iamportClient = new IamportClient(apiKey, secretKey);
    }

    @Transactional
    public PaymentRes createPayment(PaymentReq paymentReq) throws IamportResponseException, IOException {
        try {
            // 아임포트 API를 통해 결제 정보 조회
            IamportResponse<com.siot.IamportRestClient.response.Payment> iamResponse = iamportClient.paymentByImpUid(paymentReq.getImpUid());

            if (iamResponse == null || iamResponse.getResponse() == null) {
                throw new IllegalArgumentException("Invalid payment response from Iamport");
            }

            // 결제된 금액 가져오기
            int paidAmount = iamResponse.getResponse().getAmount().intValue();

            // 결제하려는 입찰 가져오기
            Bid bid = bidRepository.findById(paymentReq.getBidUuid())
                    .orElseThrow(() -> new IllegalArgumentException("Invalid bid UUID"));

            // 현재 입찰가 가져오기
            int priceToPay = (int) (bid.getPrice() * 100);

            // 결제된 금액과 입찰가 비교
            if (paidAmount != priceToPay) {
                log.warn("Payment amount {} does not match expected amount {}", paidAmount, priceToPay);
                // 결제 금액이 맞지 않을 경우 결제 취소하고 예외 발생
                CancelData cancelData = new CancelData(paymentReq.getImpUid(), true);
                iamportClient.cancelPaymentByImpUid(cancelData);
                throw new IllegalArgumentException("결제 금액이 맞지 않습니다.");
            }

            // 결제 정보 저장
            bid.setImpUid(paymentReq.getImpUid());
            bidRepository.save(bid);

            Payment payment = new Payment();
            payment.setImpUid(paymentReq.getImpUid());
            payment.setPrice((double) paidAmount);
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
