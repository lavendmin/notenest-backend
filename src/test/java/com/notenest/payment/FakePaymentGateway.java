package com.notenest.payment;

import java.util.ArrayList;
import java.util.List;

/**
 * 통합 테스트용 PG 대역. 실제 아임포트 호출 없이 결제 금액을 결정적으로 주입하고,
 * 취소 호출을 기록한다. 금액은 원(KRW) 단위 정수.
 */
public class FakePaymentGateway implements PaymentGateway {

    /** fetchPaidAmountWon 이 돌려줄 원 단위 금액. 테스트가 시나리오별로 설정한다. */
    public long paidAmountWon;

    /** 취소 호출 기록("impUid:reason"). 불일치 보상 취소가 실제로 일어났는지 검증한다. */
    public final List<String> cancellations = new ArrayList<>();

    @Override
    public long fetchPaidAmountWon(String impUid) {
        return paidAmountWon;
    }

    @Override
    public void cancelPayment(String impUid, String reason) {
        cancellations.add(impUid + ":" + reason);
    }
}
