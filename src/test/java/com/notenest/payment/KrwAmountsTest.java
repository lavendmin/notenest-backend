package com.notenest.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * NB2 금액 계약(KRW 원 단위 정수, 무손실 변환)의 단위 재현·검증 테스트.
 *
 * <p>DB·PG 없이 순수 로직만 검증한다. 먼저 <b>기존 변환 경로의 위험</b>(조용한 절삭·오버플로우)을
 * 특정 값으로 재현하고, 이어서 {@link KrwAmounts}가 같은 입력을 어떻게 거부/무손실 처리하는지 고정한다.
 */
class KrwAmountsTest {

    @Nested
    @DisplayName("기존 변환 경로의 위험을 특정 값으로 재현한다 (수정 대상)")
    class LegacyHazards {

        @Test
        @DisplayName("BigDecimal.intValue() 는 소수부를 조용히 버린다 — 검증을 잘못 통과시킬 수 있다")
        void intValue_truncatesFractionSilently() {
            // 기존 PaymentService: int paidAmount = getAmount().intValue();
            // PG가 11000.9 를 돌려주면 11000 으로 조용히 절삭되어, 입찰가 11000 과 "일치"로 오판될 수 있다.
            assertThat(new BigDecimal("11000.9").intValue()).isEqualTo(11000);
        }

        @Test
        @DisplayName("BigDecimal.intValue() 는 int 범위를 넘는 금액을 조용히 음수로 뒤집는다")
        void intValue_overflowsSilently() {
            // 30억원은 int(≈21억) 범위를 넘어 intValue() 가 음수로 오버플로우한다.
            assertThat(new BigDecimal("3000000000").intValue()).isNegative();
        }

        @Test
        @DisplayName("소수 금액에 *100 을 곱해 int 로 캐스팅하면 부동소수점 절삭이 발생한다")
        void floatMultiplyThenCast_truncates() {
            // 기존 경로: (int) (price * 100). 소수 금액(여기 0.29 는 소수 둘째 자리 — API 직접 입력 등
            // 임의 소수 입력의 예시이며 프론트의 소수 첫째 자리 허용과는 무관)을 double 로 *100 하면
            // 28.999... 가 되어 29 가 아니라 28 로 절삭된다.
            assertThat((int) (0.29 * 100)).isEqualTo(28);
        }
    }

    @Nested
    @DisplayName("NB2 계약: 원 단위 정수로 무손실 변환만 허용한다")
    class Contract {

        @Test
        @DisplayName("정상 원 단위 정수는 그대로 변환된다")
        void validWon_isReturnedExactly() {
            assertThat(KrwAmounts.requirePaymentWon(new BigDecimal("11000"))).isEqualTo(11000L);
            // int 범위를 넘는 금액도 long 으로 정확히 보존된다(오버플로우 없음).
            assertThat(KrwAmounts.requirePaymentWon(new BigDecimal("3000000000"))).isEqualTo(3_000_000_000L);
        }

        @Test
        @DisplayName("소수부가 있으면 절삭하지 않고 예외를 던진다")
        void fraction_isRejected() {
            assertThatThrownBy(() -> KrwAmounts.requirePaymentWon(new BigDecimal("11000.9")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("원 단위 정수가 아닙니다");
        }

        @Test
        @DisplayName("null·0원·음수·상한 초과는 모두 거부한다")
        void nullZeroNegativeAndOverCap_areRejected() {
            assertThatThrownBy(() -> KrwAmounts.requirePaymentWon(null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> KrwAmounts.requirePaymentWon(BigDecimal.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> KrwAmounts.requirePaymentWon(new BigDecimal("-1")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> KrwAmounts.requirePaymentWon(new BigDecimal(KrwAmounts.MAX_WON + 1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("허용 범위");
        }

        @Test
        @DisplayName("입찰가·시작가 범위 검증은 결제 허용 범위와 동일한 상·하한을 쓴다")
        void requireWonInRange_matchesPaymentBounds() {
            assertThat(KrwAmounts.requireWonInRange(11000L, "입찰가")).isEqualTo(11000L);
            assertThat(KrwAmounts.requireWonInRange(KrwAmounts.MAX_WON, "입찰가")).isEqualTo(KrwAmounts.MAX_WON);
            assertThatThrownBy(() -> KrwAmounts.requireWonInRange(0L, "입찰가"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> KrwAmounts.requireWonInRange(-1L, "입찰가"))
                    .isInstanceOf(IllegalArgumentException.class);
            // 결제(requirePaymentWon)에서 거부되는 상한 초과가 입찰·시작가에서도 동일하게 거부된다.
            assertThatThrownBy(() -> KrwAmounts.requireWonInRange(KrwAmounts.MAX_WON + 1, "입찰가"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("허용 범위");
        }
    }
}
