package com.notenest.service;

import com.notenest.domain.Bid;
import com.notenest.domain.Payment;
import com.notenest.dto.BidListDTO;
import com.notenest.dto.CompletedBidDTO;
import com.notenest.dto.CreateBidDTO;
import com.notenest.dto.MyBidListDTO;
import com.notenest.dto.PendingBidDTO;
import com.notenest.repository.BidRepository;
import com.notenest.domain.Music;
import com.notenest.repository.MusicRepository;
import com.notenest.domain.User;
import com.notenest.repository.PaymentRepository;
import com.notenest.repository.UserRepository;
import com.siot.IamportRestClient.exception.IamportResponseException;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class BidServiceImpl implements BidService {

    @Autowired
    private BidRepository bidRepository;

    @Autowired
    private MusicRepository musicRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private EmailService emailService;

    @Autowired
    private DownloadService downloadService;

    @Override
    public Bid createBid(CreateBidDTO createBidDTO, String loggedInUserEmail) {
        Music music = musicRepository.findById(createBidDTO.getMusicUuid())
                .orElseThrow(() -> new EntityNotFoundException("음악이 존재하지 않습니다."));
        User user = userRepository.findByEmail(loggedInUserEmail);
        if (user == null) {
            throw new IllegalArgumentException("로그인 후 이용 가능합니다.");
        }

        // 본인 곡 입찰 불가
        if (music.getUser().getUserUUID().equals(user.getUserUUID())) {
            throw new IllegalArgumentException("자신의 곡에는 입찰할 수 없습니다.");
        }

        // 시작 가격과 비교
        if (music.getStartingPrice() != null && createBidDTO.getPrice() < music.getStartingPrice()) {
            throw new IllegalArgumentException("시작 가격보다 높게 입찰해주십시오.");
        }

        // 현재 최고 입찰가와 비교
        if (music.getCurrentHighestBid() != null && createBidDTO.getPrice() <= music.getCurrentHighestBid()) {
            throw new IllegalArgumentException("현재 최고 입찰가보다 높게 입찰해주십시오.");
        }

        Bid bid = new Bid();
        bid.setMusic(music);
        bid.setUser(user);
        bid.setPrice(createBidDTO.getPrice());

        // 최고 입찰가 업데이트
        music.setCurrentHighestBid(createBidDTO.getPrice());

        return bidRepository.save(bid);
    }


    @Override
    public void deleteBid(UUID bidUuid, String loggedInUserEmail) {

        Bid bid = bidRepository.findById(bidUuid)
                .orElseThrow(() -> new IllegalArgumentException("해당 입찰을 찾을 수 없습니다."));

        if (!bid.getUser().getEmail().equals(loggedInUserEmail)) {
            throw new IllegalArgumentException("해당 입찰 작성자만 삭제할 수 있습니다.");
        }

        bidRepository.delete(bid);
    }

    @Override
    public Page<BidListDTO> getAllBidsByMusic(UUID musicUuid, Pageable pageable) {
        Music music = musicRepository.findById(musicUuid)
                .orElseThrow(() -> new EntityNotFoundException("음악이 존재하지 않습니다."));

        Page<Bid> bids;

        if (Boolean.TRUE.equals(music.getShowAllBids())) {
            bids = bidRepository.findByMusicOrderByPriceDescCreatedAtAsc(music, pageable);
        } else {
            Pageable top5Pageable = PageRequest.of(0, 5, Sort.by(Sort.Order.desc("price"), Sort.Order.asc("createdAt")));
            bids = bidRepository.findByMusicOrderByPriceDescCreatedAtAsc(music, top5Pageable);
        }

        return bids.map(bid -> {
            BidListDTO bidListDTO = new BidListDTO();
            bidListDTO.setBidUuid(bid.getBidUuid());
            bidListDTO.setPrice(bid.getPrice());
            bidListDTO.setCreatedAt(bid.getCreatedAt());
            return bidListDTO;
        });
    }


    public Page<Bid> getBidsByUser(Pageable pageable,String loggedInUserEmail) {

        User user = userRepository.findByEmail(loggedInUserEmail);
        return bidRepository.findByUser(user, pageable);
    }

    // 마이페이지 입찰내역
    @Override
    public Page<MyBidListDTO> getUserBids(String loggedInUserEmail, LocalDateTime from, LocalDateTime to,
                                              String searchTerm, String sortBy, Pageable pageable) {
        User user = userRepository.findByEmail(loggedInUserEmail);
        if (user == null) {
            throw new IllegalArgumentException("로그인 후 이용 가능합니다.");
        }

        Page<Bid> bids;
        if (sortBy.equals("popular")) { // 인기순
            bids = bidRepository.findOngoingBidsByUserAndDateRangeAndSearchTermOrderByLikeCount(user, from, to, searchTerm, pageable);
        } else if (sortBy.equals("price")) { // 가격순
            bids = bidRepository.findOngoingBidsByUserAndDateRangeAndSearchTermOrderByPrice(user, from, to, searchTerm, pageable);
        } else { // 최신순 (디폴트)
            bids = bidRepository.findOngoingBidsByUserAndDateRangeAndSearchTermOrderByCreatedAt(user, from, to, searchTerm, pageable);
        }

        return bids.map(bid -> {
            MyBidListDTO myBidListDTO = new MyBidListDTO();
            myBidListDTO.setBidUuid(bid.getBidUuid());
            myBidListDTO.setMusicUuid(bid.getMusic().getMusicUuid());
            myBidListDTO.setMusicImage(bid.getMusic().getImage());
            myBidListDTO.setMusicTitle(bid.getMusic().getTitle());
            myBidListDTO.setComposer(bid.getMusic().getUser().getNickname());
            myBidListDTO.setBidPrice(bid.getPrice());
            myBidListDTO.setAuctionEndTime(bid.getMusic().getAuctionEndTime());
            myBidListDTO.setBidCreatedAt(bid.getCreatedAt());
            myBidListDTO.setStatus(bid.getStatus());

            return myBidListDTO;
        });
    }


    // 낙찰 (bid status 업데이트) 및 이메일 발송
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processAuctionEnd(UUID musicUuid) throws IamportResponseException, IOException {
        Music music = musicRepository.findById(musicUuid)
                .orElseThrow(() -> new IllegalArgumentException("Invalid music UUID: " + musicUuid));

        User composer = music.getUser();

        // 현재 시간 구하기 (Instant -> LocalDateTime 변환)
        LocalDateTime now = LocalDateTime.ofInstant(Instant.now(), ZoneId.systemDefault());

        // 경매가 종료되었는지 확인
        if (music.getAuctionEndTime().isBefore(now)) {
            // 음악 상태 업데이트
            music.setStatus(1);
            musicRepository.save(music);

            List<Bid> highestBids = bidRepository.findByMusicOrderByPriceDescCreatedAtAsc(music);

            if (highestBids.size() > 0) { // 입찰자 1명 이상
                Bid highestBid = highestBids.get(0);

                // 첫번째 입찰자에게 낙찰 성공 이메일 발송. 노션의 1-(1), 2-(1)
                if (!highestBid.isBidderEmailSent()) {
                    emailService.sendBidSuccessToBidder(highestBid.getUser(), music);
                    highestBid.setBidderEmailSent(true);
                    bidRepository.save(highestBid);
                }

                // 첫번째 입찰자 bid status 업데이트 (낙찰내역-결제 대기)
                highestBid.setStatus("PENDING");
                bidRepository.save(highestBid);

                // 첫번째 입찰자를 제외한 나머지 입찰자 bid status 업데이트 (낙찰 실패)
                for (int i=1; i<highestBids.size(); i++) {
                    Bid otherBid = highestBids.get(i);
                    otherBid.setStatus("FAILED");
                    bidRepository.save(otherBid);
                }

                // 첫번째 입찰자 결제 기한 내 결제 여부 확인
                checkAndProcessNextBidder(music, highestBids);

            } else {
                // 입찰이 없었을 경우(경매 무산) 작곡가에게 이메일 발송. 노션의 3-(1)
                if (!music.isAuctionFailureEmailSent()) {
                    emailService.sendAuctionFailureToComposer(composer, music);
                    music.setAuctionFailureEmailSent(true);
                    musicRepository.save(music);
                }
            }

        }
    }

    // 낙찰자 결제 기한 체크 및 다음 낙찰자 처리
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void checkAndProcessNextBidder(Music music, List<Bid> highestBids) throws IamportResponseException, IOException{
        User composer = music.getUser();

        LocalDateTime now = LocalDateTime.ofInstant(Instant.now(), ZoneId.systemDefault());
        LocalDateTime paymentDeadline1 = music.getAuctionEndTime().plusDays(3); // 첫번째 입찰자 결제 기한
        LocalDateTime paymentDeadline2 = music.getAuctionEndTime().plusDays(6); // 두번째 입찰자 결제 기한

        Bid highestBid = highestBids.get(0);

        // 첫번째 입찰자 결제 기한 내 결제 여부 확인
        Payment firstPayment = paymentRepository.findByBid(highestBid);
        if (firstPayment == null || !firstPayment.getStatus().equals("PAID")) {
            if (paymentDeadline1.isBefore(now)) {
                // 첫번째 입찰자 기한 내 결제 X -> 다음 입찰자 처리

                // 첫번째 입찰자 bid status 업데이트 (결제 실패, 낙찰 실패)
                highestBid.setStatus("FAILED");
                bidRepository.save(highestBid);

                if (highestBids.size() > 1) { // 입찰자 2명 이상. 노션의 1-(1)-②
                    Bid secondBid = highestBids.get(1);

                    if (secondBid.getUser().equals(highestBid.getUser())) { // 첫번째 입찰자와 두번째 입찰자 같은 경우
                        if (!music.isAuctionFailureEmailSent()) {
                            emailService.sendAuctionFailureToComposer(composer, music);
                            music.setAuctionFailureEmailSent(true);
                            musicRepository.save(music);
                        }
                    } else { // 첫번째 입찰자와 두번째 입찰자 다른 경우
                        // 두번째 입찰자에게 낙찰 성공 이메일 발송. 노션의 1-(2)
                        if (!secondBid.isBidderEmailSent()) {
                            emailService.sendBidSuccessToBidder(secondBid.getUser(), music);
                            secondBid.setBidderEmailSent(true);
                            bidRepository.save(secondBid);
                        }

                        // 두번째 입찰자 bid status 업데이트 (결제 대기)
                        secondBid.setStatus("PENDING");
                        bidRepository.save(secondBid);

                        // 두번째 결제 기한 내 결제 여부 확인
                        Payment secondPayment = paymentRepository.findByBid(secondBid);
                        if (secondPayment == null || !secondPayment.getStatus().equals("PAID")) {
                            if (paymentDeadline2.isBefore(now)) {
                                // 두번째 입찰자 기한 내 결제 X -> 경매 무산. 노션의 1-(2)-②
                                if (!music.isAuctionFailureEmailSent()) {
                                    emailService.sendAuctionFailureToComposer(composer, music);
                                    music.setAuctionFailureEmailSent(true);
                                    musicRepository.save(music);
                                }

                                // 두번째 입찰자 bid status 업데이트 (결제 실패)
                                secondBid.setStatus("FAILED");
                                bidRepository.save(secondBid);
                            }
                        } else {
                            // 두번째 입찰자 결제 O 노션의 1-(2)-①
                            if (!secondBid.isComposerEmailSent()) {
                                emailService.sendBidSuccessToComposer(composer, music);
                                secondBid.setComposerEmailSent(true);
                                bidRepository.save(secondBid);
                            }


                            // 두번째 입찰자 bid status 업데이트 (결제 완료)
                            secondBid.setStatus("COMPLETED");
                            bidRepository.save(secondBid);
                        }
                    }

                } else { // 입찰자 1명
                    // 두번째 입찰자 없으면 경매 무산. 노션의 2-(1)-②
                    if (!music.isAuctionFailureEmailSent()) {
                        emailService.sendAuctionFailureToComposer(composer, music);
                        music.setAuctionFailureEmailSent(true);
                        musicRepository.save(music);
                    }
                }
            }
        } else {
            // 첫번째 입찰자 결제 O 노션의 1-(1)-①, 2-(1)-①
            if (!highestBid.isComposerEmailSent()) {
                emailService.sendBidSuccessToComposer(composer, music);
                highestBid.setComposerEmailSent(true);
                bidRepository.save(highestBid);
            }

            // 첫번째 입찰자 bid status 업데이트 (결제 완료)
            highestBid.setStatus("COMPLETED");
            bidRepository.save(highestBid);
        }
    }

    @Override
    @Scheduled(fixedRate = 10000) // 10초 간격으로 실행
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void checkAuctionEnd() throws IamportResponseException, IOException {
        List<Music> musics = musicRepository.findAll();
        for (Music music : musics) {
            if (music.getAuctionEndTime() == null) { // 경매 마감 기한 null인 경우 예외 처리
                continue;
            }

            if (music.getAuctionEndTime().isBefore(LocalDateTime.now())) {
                // 경매 종료 처리
                try {
                    processAuctionEnd(music.getMusicUuid());
                } catch (IamportResponseException | IOException e) {
                    log.error("Error processing auction end for music ID: {}", music.getMusicUuid(), e);
                }
            }
        }
    }

    // 마이페이지 낙찰내역 - 결제 대기
    @Override
    public Page<PendingBidDTO> getPendingBids(String loggedInUserEmail, LocalDateTime from, LocalDateTime to,
                                              String searchTerm, String sortBy, Pageable pageable) {
        User user = userRepository.findByEmail(loggedInUserEmail);
        if (user == null) {
            throw new IllegalArgumentException("로그인 후 이용 가능합니다.");
        }

        Page<Bid> bids;
        if (sortBy.equals("popular")) { // 인기순
            bids = bidRepository.findPendingBidsByUserAndDateRangeAndSearchTermOrderByLikeCount(user, from, to, searchTerm, pageable);
        } else if (sortBy.equals("price")) { // 가격순
            bids = bidRepository.findPendingBidsByUserAndDateRangeAndSearchTermOrderByPrice(user, from, to, searchTerm, pageable);
        } else { // 최신순 (디폴트)
            bids = bidRepository.findPendingBidsByUserAndDateRangeAndSearchTermOrderByCreatedAt(user, from, to, searchTerm, pageable);
        }

        return bids.map(bid -> {
            PendingBidDTO pendingBidDTO = new PendingBidDTO();
            pendingBidDTO.setBidUuid(bid.getBidUuid());
            pendingBidDTO.setMusicUuid(bid.getMusic().getMusicUuid());
            pendingBidDTO.setMusicImage(bid.getMusic().getImage());
            pendingBidDTO.setMusicTitle(bid.getMusic().getTitle());
            pendingBidDTO.setComposer(bid.getMusic().getUser().getNickname());
            pendingBidDTO.setBidPrice(bid.getPrice());
            pendingBidDTO.setAuctionEndTime(bid.getMusic().getAuctionEndTime());
            pendingBidDTO.setPaymentUrl("/api/payment/process"); // 결제 Url
            return pendingBidDTO;
        });
    }

    // 마이페이지 낙찰내역 - 결제 완료
    @Override
    public Page<CompletedBidDTO> getCompletedBids(String loggedInUserEmail, LocalDateTime from, LocalDateTime to,
                                                  String searchTerm, String sortBy, Pageable pageable) {
        User user = userRepository.findByEmail(loggedInUserEmail);
        if (user == null) {
            throw new IllegalArgumentException("로그인 후 이용 가능합니다.");
        }


        Page<Bid> bids;
        if (sortBy.equals("popular")) { // 인기순 -> 삭제 예정
            bids = bidRepository.findCompletedBidsByUserAndDateRangeAndSearchTermOrderByLikeCount(user, from, to, searchTerm, pageable);
        } else if (sortBy.equals("price")) { // 가격순
            bids = bidRepository.findCompletedBidsByUserAndDateRangeAndSearchTermOrderByPrice(user, from, to, searchTerm, pageable);
        } else { // 최신순 (디폴트)
            bids = bidRepository.findCompletedBidsByUserAndDateRangeAndSearchTermOrderByCreatedAt(user, from, to, searchTerm, pageable);
        }

        return bids.map(bid -> {
            CompletedBidDTO completedBidDTO = new CompletedBidDTO();
            completedBidDTO.setBidUuid(bid.getBidUuid());
            completedBidDTO.setMusicUuid(bid.getMusic().getMusicUuid());
            completedBidDTO.setMusicImage(bid.getMusic().getImage());
            completedBidDTO.setMusicTitle(bid.getMusic().getTitle());
            completedBidDTO.setComposer(bid.getMusic().getUser().getNickname());
            completedBidDTO.setBidPrice(bid.getPrice());
            completedBidDTO.setPaid(true);
            completedBidDTO.setDownloadUrl("/api/mypage/download/" + bid.getMusic().getMusicUuid()); // 음원 파일 다운로드 Url

            int downloadCount = downloadService.getDownloadCount(completedBidDTO.getMusicUuid(), loggedInUserEmail);
            completedBidDTO.setDownloadCount(downloadCount);

            return completedBidDTO;
        });
    }


}
