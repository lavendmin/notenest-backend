package com.notenest.payment;

import com.siot.IamportRestClient.IamportClient;
import com.siot.IamportRestClient.exception.IamportResponseException;
import com.siot.IamportRestClient.request.CancelData;
import com.siot.IamportRestClient.response.IamportResponse;
import com.siot.IamportRestClient.response.Payment;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 아임포트(PortOne) REST 클라이언트 기반 {@link PaymentGateway} 구현.
 *
 * <p>PG 응답 금액({@code BigDecimal})을 {@link KrwAmounts#requirePaymentWon}으로 무손실 변환한다.
 * 기존 코드가 쓰던 {@code getAmount().intValue()} 의 조용한 절삭·오버플로우를 여기서 차단한다.
 */
@Component
@Slf4j
public class IamportPaymentGateway implements PaymentGateway {

    private IamportClient iamportClient;

    @Value("${imp.api.key}")
    private String apiKey;

    @Value("${imp.api.secretkey}")
    private String secretKey;

    @PostConstruct
    public void init() {
        this.iamportClient = new IamportClient(apiKey, secretKey);
    }

    @Override
    public long fetchPaidAmountWon(String impUid) throws IamportResponseException, IOException {
        IamportResponse<Payment> response = iamportClient.paymentByImpUid(impUid);
        if (response == null || response.getResponse() == null) {
            throw new IllegalArgumentException("Invalid payment response from Iamport");
        }
        // 원 단위 정수로 무손실 변환(소수·범위 초과는 예외).
        return KrwAmounts.requirePaymentWon(response.getResponse().getAmount());
    }

    @Override
    public void cancelPayment(String impUid, String reason) throws IamportResponseException, IOException {
        log.warn("Cancelling payment impUid={} reason={}", impUid, reason);
        CancelData cancelData = new CancelData(impUid, true);
        iamportClient.cancelPaymentByImpUid(cancelData);
    }
}
