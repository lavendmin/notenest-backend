package com.notenest.payment;

import com.notenest.domain.Bid;
import com.notenest.domain.User;
import com.notenest.dto.PaymentReq;
import com.notenest.repository.BidRepository;
import com.notenest.repository.PaymentRepository;
import com.notenest.repository.UserRepository;
import com.notenest.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PaymentService 금액 검증 로직의 재현·검증 테스트 (Mockito, DB·네트워크 없음).
 *
 * <p>PG 응답은 {@link PaymentGateway} 대역으로 결정적으로 주입한다. 핵심은:
 * <ul>
 *   <li>배율(*100) 제거 — PG 금액이 입찰가와 <b>같아야</b> 성공(과거처럼 100배가 아님)</li>
 *   <li>금액 불일치 시 결제 취소 호출 + 성공 결제 레코드 미저장</li>
 *   <li>낙찰자만 결제 진행(소유권 검증)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceAmountTest {

    @Mock private BidRepository bidRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private UserRepository userRepository;
    @Mock private PaymentGateway paymentGateway;

    private PaymentService paymentService;

    private static final String BIDDER = "bidder@test.local";

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(bidRepository, paymentRepository, userRepository, paymentGateway);
    }

    private Bid bidOf(long price, String ownerEmail) {
        User owner = new User();
        owner.setEmail(ownerEmail);
        Bid bid = new Bid();
        bid.setBidUuid(UUID.randomUUID());
        bid.setPrice(price);
        bid.setUser(owner);
        return bid;
    }

    @Test
    @DisplayName("PG 금액이 입찰가(원 단위)와 같으면 결제가 원 단위 그대로 저장된다 — *100 배율 없음")
    void matchingAmount_savesPaymentInWon() throws Exception {
        Bid bid = bidOf(11000L, BIDDER);
        PaymentReq req = new PaymentReq();
        req.setImpUid("imp_ok");
        req.setBidUuid(bid.getBidUuid());

        when(paymentGateway.fetchPaidAmountWon("imp_ok")).thenReturn(11000L);
        when(bidRepository.findById(bid.getBidUuid())).thenReturn(Optional.of(bid));

        paymentService.createPayment(req);

        ArgumentCaptor<com.notenest.domain.Payment> captor =
                ArgumentCaptor.forClass(com.notenest.domain.Payment.class);
        verify(paymentRepository).save(captor.capture());
        assertThat(captor.getValue().getPrice()).isEqualTo(11000L); // 1,100,000 이 아님
        assertThat(captor.getValue().getStatus()).isEqualTo("PAID");
        assertThat(bid.getImpUid()).isEqualTo("imp_ok");
        verify(paymentGateway, never()).cancelPayment(any(), any());
    }

    @Test
    @DisplayName("과거 배율(입찰가*100)로 온 금액은 이제 불일치로 거부된다 — 계약 변경을 고정")
    void legacyScaledAmount_isNowRejected() throws Exception {
        Bid bid = bidOf(11000L, BIDDER);
        PaymentReq req = new PaymentReq();
        req.setImpUid("imp_scaled");
        req.setBidUuid(bid.getBidUuid());

        when(paymentGateway.fetchPaidAmountWon("imp_scaled")).thenReturn(1_100_000L); // 과거 *100 값
        when(bidRepository.findById(bid.getBidUuid())).thenReturn(Optional.of(bid));

        assertThatThrownBy(() -> paymentService.createPayment(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("결제 금액이 맞지 않습니다");

        verify(paymentGateway).cancelPayment(eq("imp_scaled"), any());
        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("금액 불일치 시 결제를 취소하고 성공 결제 레코드를 저장하지 않는다")
    void mismatch_cancelsAndDoesNotSave() throws Exception {
        Bid bid = bidOf(11000L, BIDDER);
        PaymentReq req = new PaymentReq();
        req.setImpUid("imp_bad");
        req.setBidUuid(bid.getBidUuid());

        when(paymentGateway.fetchPaidAmountWon("imp_bad")).thenReturn(9999L);
        when(bidRepository.findById(bid.getBidUuid())).thenReturn(Optional.of(bid));

        assertThatThrownBy(() -> paymentService.createPayment(req))
                .isInstanceOf(IllegalArgumentException.class);

        verify(paymentGateway).cancelPayment(eq("imp_bad"), any());
        verify(paymentRepository, never()).save(any());
        assertThat(bid.getImpUid()).isNull();
    }

    @Test
    @DisplayName("낙찰자가 아니면 결제를 진행할 수 없다 — PG 조회 전에 차단된다")
    void nonOwner_isRejectedBeforeGatewayCall() throws Exception {
        Bid bid = bidOf(11000L, "someoneelse@test.local");
        PaymentReq req = new PaymentReq();
        req.setImpUid("imp_owner");
        req.setBidUuid(bid.getBidUuid());

        when(bidRepository.findById(bid.getBidUuid())).thenReturn(Optional.of(bid));
        when(userRepository.findByEmail(BIDDER)).thenReturn(new User());

        assertThatThrownBy(() -> paymentService.processPayment(req, BIDDER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("낙찰자만");

        verify(paymentGateway, never()).fetchPaidAmountWon(any());
        verify(paymentRepository, never()).save(any());
    }
}
