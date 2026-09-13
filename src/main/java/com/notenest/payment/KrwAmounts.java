package com.notenest.payment;

import java.math.BigDecimal;

/**
 * NoteNest 금액 계약(NB2) — 기준 통화·최소 단위·정밀도를 한곳에 명문화한다.
 *
 * <p><b>계약</b>
 * <ul>
 *   <li>기준 통화: 대한민국 원(KRW).</li>
 *   <li>최소 단위: 1원. 소수 통화·소수부를 허용하지 않는다(원 단위 정수, {@code long}).</li>
 *   <li>배율 없음: API·도메인·DB·PG 응답 금액은 모두 "원" 그대로다. 과거의 {@code * 100} 배율은
 *       새 백엔드 계약에서 제거한다(프론트 호환은 별도 후속 작업).</li>
 *   <li>반올림·절삭 금지: PG 응답 금액을 원 단위 정수로 <b>무손실</b> 변환만 허용한다. 소수부가 있거나
 *       {@code long} 범위를 넘으면 조용히 버리지 않고 예외로 만든다.</li>
 * </ul>
 *
 * <p>이 클래스가 대체하는 위험 경로:
 * <pre>
 *   // (기존) PG 응답 금액을 int 로 절삭 — 소수부·범위 초과를 조용히 삼킴
 *   int paidAmount = iamResponse.getResponse().getAmount().intValue();
 * </pre>
 */
public final class KrwAmounts {

    /** 결제(PG 응답) 최소 허용 금액. 0원·음수 결제는 성립하지 않는다. */
    public static final long MIN_PAYMENT_WON = 1L;

    /** 정합성 상한(sanity cap). 이 값을 넘는 결제 금액은 비정상으로 보고 거부한다(100억원). */
    public static final long MAX_WON = 10_000_000_000L;

    private KrwAmounts() {
    }

    /**
     * PG 응답 금액({@link BigDecimal})을 원 단위 정수({@code long})로 무손실 변환한다.
     *
     * <p>{@code null}·소수부 존재·{@code long} 범위 초과·최소/최대 범위 밖은 모두
     * {@link IllegalArgumentException}으로 거부한다. 절삭·반올림은 하지 않는다.
     *
     * @param pgAmount PG(아임포트 등) 응답의 결제 금액
     * @return 원 단위 정수 금액
     * @throws IllegalArgumentException 금액이 없거나 원 단위 정수로 무손실 표현할 수 없을 때
     */
    public static long requirePaymentWon(BigDecimal pgAmount) {
        if (pgAmount == null) {
            throw new IllegalArgumentException("결제 금액이 없습니다.");
        }
        long won;
        try {
            // longValueExact(): 소수부가 있거나 long 범위를 넘으면 ArithmeticException.
            // intValue() 와 달리 조용히 절삭·오버플로우하지 않는다.
            won = pgAmount.longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("결제 금액이 원 단위 정수가 아닙니다: " + pgAmount.toPlainString(), e);
        }
        if (won < MIN_PAYMENT_WON) {
            throw new IllegalArgumentException("결제 금액은 " + MIN_PAYMENT_WON + "원 이상이어야 합니다: " + won);
        }
        if (won > MAX_WON) {
            throw new IllegalArgumentException("결제 금액이 허용 범위(" + MAX_WON + "원)를 초과했습니다: " + won);
        }
        return won;
    }
}
