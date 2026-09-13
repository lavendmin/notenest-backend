package com.notenest.payment;

import com.siot.IamportRestClient.exception.IamportResponseException;

import java.io.IOException;

/**
 * 외부 PG(아임포트) 호출을 추상화하는 결제 게이트웨이 경계.
 *
 * <p>이 경계 덕분에 결제 검증 로직({@code PaymentService})을 실제 네트워크·PG SDK 없이
 * 테스트 대역으로 결정적으로 검증할 수 있다. 금액은 항상 원(KRW) 단위 정수({@code long})로
 * 주고받는다({@link KrwAmounts} 계약).
 */
public interface PaymentGateway {

    /**
     * 결제 고유 식별자(impUid)로 PG에 조회한 실제 결제 금액을 원 단위 정수로 반환한다.
     *
     * @param impUid PG 결제 고유 식별자
     * @return 원 단위 정수 결제 금액
     * @throws IllegalArgumentException 결제 정보가 없거나 금액이 원 단위 정수로 표현되지 않을 때
     */
    long fetchPaidAmountWon(String impUid) throws IamportResponseException, IOException;

    /**
     * 결제를 전액 취소한다(금액 불일치 등 검증 실패 시 보상).
     *
     * @param impUid PG 결제 고유 식별자
     * @param reason 취소 사유
     */
    void cancelPayment(String impUid, String reason) throws IamportResponseException, IOException;
}
