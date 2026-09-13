package com.notenest.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notenest.dto.CreateBidDTO;
import com.notenest.dto.CreateMusicDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * API 입력(JSON) 역직렬화의 엄격성 검증 (P1).
 *
 * <p>Spring 이 구성한 실제 {@link ObjectMapper}(application.properties 의
 * {@code spring.jackson.deserialization.accept-float-as-int=false} 반영)를 사용한다.
 * 소수 금액이 정수 필드로 <b>조용히 절삭</b>되던 입력 경로를 예외로 바꾼 것을 고정한다.
 */
@JsonTest
class AmountJsonStrictnessTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("입찰가에 소수 JSON({\"price\":11000.9})이 오면 절삭하지 않고 예외를 던진다")
    void createBid_rejectsFractionalPrice() {
        assertThatThrownBy(() -> objectMapper.readValue("{\"price\":11000.9}", CreateBidDTO.class))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.MismatchedInputException.class);
    }

    @Test
    @DisplayName("정수 입찰가는 정상 역직렬화된다")
    void createBid_acceptsIntegerPrice() throws Exception {
        CreateBidDTO dto = objectMapper.readValue("{\"price\":11000}", CreateBidDTO.class);
        assertThat(dto.getPrice()).isEqualTo(11000L);
    }

    @Test
    @DisplayName("시작가에 소수 JSON({\"startingPrice\":10000.5})이 오면 절삭하지 않고 예외를 던진다")
    void createMusic_rejectsFractionalStartingPrice() {
        assertThatThrownBy(() -> objectMapper.readValue("{\"startingPrice\":10000.5}", CreateMusicDTO.class))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.MismatchedInputException.class);
    }
}
