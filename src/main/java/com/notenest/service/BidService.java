package com.notenest.service;

import com.notenest.domain.Bid;
import com.notenest.dto.BidListDTO;
import com.notenest.dto.CompletedBidDTO;
import com.notenest.dto.CreateBidDTO;
import com.notenest.dto.MyBidListDTO;
import com.notenest.dto.PendingBidDTO;
import com.siot.IamportRestClient.exception.IamportResponseException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.UUID;

public interface BidService {
    Bid createBid(CreateBidDTO createBidDTO, String loggedInUserEmail);
    void deleteBid(UUID bidUuid, String loggedInUserEmail);
    Page<BidListDTO> getAllBidsByMusic(UUID musicUuid, Pageable pageable);

    Page<Bid> getBidsByUser(Pageable pageable, String loggedInUserEmail);


    void processAuctionEnd(UUID musicUuid)throws IamportResponseException, IOException;

    // 마이페이지 입찰내역
    Page<MyBidListDTO> getUserBids(String loggedInUserEmail, LocalDateTime from, LocalDateTime to,
                                   String searchTerm, String sortBy, Pageable pageable);

    // 마이페이지 낙찰내역 - 결제 대기
    Page<PendingBidDTO> getPendingBids(String loggedInUserEmail, LocalDateTime from, LocalDateTime to,
                                       String searchTerm, String sortBy, Pageable pageable);

    // 마이페이지 낙찰내역 - 결제 완료
    Page<CompletedBidDTO> getCompletedBids(String loggedInUserEmail, LocalDateTime from, LocalDateTime to,
                                           String searchTerm, String sortBy, Pageable pageable);

    @Scheduled(fixedRate = 10000) // 10초 간격으로 실행
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void checkAuctionEnd() throws IamportResponseException, IOException;
}
