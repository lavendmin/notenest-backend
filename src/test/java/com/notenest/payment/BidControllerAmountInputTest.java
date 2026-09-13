package com.notenest.payment;

import com.notenest.controller.BidController;
import com.notenest.service.BidService;
import com.notenest.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 입력 계약 검증 (P1) — 소수 금액 입력이 컨트롤러에 도달하기 전에 400 으로 거부되는지 확인한다.
 * 역직렬화 실패(accept-float-as-int=false)가 HttpMessageNotReadableException → 400 으로 이어진다.
 */
@WebMvcTest(BidController.class)
@AutoConfigureMockMvc(addFilters = false)
class BidControllerAmountInputTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean private BidService bidService;
    @MockBean private UserService userService;
    @MockBean private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("POST /api/bid/create 에 소수 price 를 보내면 400 (조용한 절삭 없음)")
    void fractionalPrice_returns400() throws Exception {
        mockMvc.perform(post("/api/bid/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"musicUuid\":\"11111111-1111-1111-1111-111111111111\",\"price\":11000.9,\"password\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }
}
