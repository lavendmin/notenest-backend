package com.notenest.service;

import com.notenest.search.MusicSearchEvents;
import com.notenest.domain.Bid;
import com.notenest.domain.Payment;
import com.notenest.payment.KrwAmounts;
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
import com.notenest.storage.MediaUrlIssuer;
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
import java.time.Clock;
import java.time.LocalDateTime;
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
    private MediaUrlIssuer mediaUrlIssuer;

    @Autowired
    private MusicSearchEvents musicSearchEvents;

    // 시간 소스 — 운영은 시스템 시계, 테스트는 고정 Clock 주입(반복 실행 멱등성 검증용)
    @Autowired
    private Clock clock;

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

        // 입찰가는 결제 가능한 원 단위 범위여야 한다(결제 검증 상·하한과 일치, NB2 금액 계약).
        KrwAmounts.requireWonInRange(createBidDTO.getPrice(), "입찰가");

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

        Bid saved = bidRepository.save(bid);
        // [NB5] 현재가가 바뀌었다 — 입찰 저장 커밋 뒤(트랜잭션 없는 경로라 즉시) 검색 문서 동기화
        musicSearchEvents.changed(music.getMusicUuid(), "bid");
        return saved;
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
            myBidListDTO.setMusicCoverUrl(mediaUrlIssuer.coverUrl(bid.getMusic().getCover()));
            myBidListDTO.setMusicTitle(bid.getMusic().getTitle());
            myBidListDTO.setComposer(bid.getMusic().getUser().getNickname());
            myBidListDTO.setBidPrice(bid.getPrice());
            myBidListDTO.setAuctionEndTime(bid.getMusic().getAuctionEndTime());
            myBidListDTO.setBidCreatedAt(bid.getCreatedAt());
            myBidListDTO.setStatus(bid.getStatus());

            return myBidListDTO;
        });
    }


    // [경매 마감 작업] 종료 시각이 지난 status=0 곡의 최초 낙찰자 선정과 상태 전이를 "한 번만" 수행한다.
    // 결제 기한·차순위 승계는 여기서 하지 않는다(→ processPaymentFollowUp). 마감 잡은 status=0 만
    // 대상으로 하므로 한 곡당 한 번만 실행되고, 이후 결제 후속 잡과 대상이 겹치지 않는다.
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processAuctionEnd(UUID musicUuid) throws IamportResponseException, IOException {
        Music music = musicRepository.findById(musicUuid)
                .orElseThrow(() -> new IllegalArgumentException("Invalid music UUID: " + musicUuid));

        User composer = music.getUser();

        // 이미 마감된 곡이면 재실행하지 않는다(멱등). 마감 잡은 status=0 만 조회하므로 정상 경로에선
        // 걸리지 않지만, 직접 호출·중복 호출에도 최초 마감이 한 번만 일어나도록 방어한다.
        if (music.getStatus() != 0) {
            return;
        }

        LocalDateTime now = LocalDateTime.now(clock);

        // 경매가 종료되었는지 확인
        if (music.getAuctionEndTime().isBefore(now)) {
            // 음악 상태 업데이트 (0→1, 마감 잡 대상에서 빠짐)
            music.setStatus(1);
            musicRepository.save(music);
            // [NB5] 경매 종료 → 검색 노출 제외. 스케줄러 사이클 트랜잭션이 커밋된 뒤 동기화된다(롤백되면 하지 않음).
            musicSearchEvents.changed(musicUuid, "auction-end");

            List<Bid> highestBids = bidRepository.findByMusicOrderByPriceDescCreatedAtAsc(music);

            if (highestBids.size() > 0) { // 입찰자 1명 이상
                Bid highestBid = highestBids.get(0);

                // 최고가 고유 사용자(1순위)에게 낙찰 성공 이메일 발송. 노션의 1-(1), 2-(1)
                if (!highestBid.isBidderEmailSent()) {
                    emailService.sendBidSuccessToBidder(highestBid.getUser(), music);
                    highestBid.setBidderEmailSent(true);
                    bidRepository.save(highestBid);
                }

                // 1순위 bid status 업데이트 (낙찰내역-결제 대기)
                highestBid.setStatus("PENDING");
                bidRepository.save(highestBid);

                // 1순위를 제외한 나머지 입찰(동일인 하위 입찰 포함) status 업데이트 (낙찰 실패)
                for (int i=1; i<highestBids.size(); i++) {
                    Bid otherBid = highestBids.get(i);
                    otherBid.setStatus("FAILED");
                    bidRepository.save(otherBid);
                }

            } else {
                // 입찰이 없었을 경우(경매 무산) 작곡가에게 이메일 발송. 노션의 3-(1)
                failAuction(music, composer);
            }
        }
    }

    // [결제 후속 작업] 결제 대기(PENDING) 입찰이 있는 곡의 정산·차순위 승계를 처리한다. 매 사이클마다
    // "현재 PENDING 대상"을 기준으로 한 단계씩 전이하므로 반복 실행에 안전하다.
    //  - 결제 완료(PAID): PENDING→COMPLETED, 작곡가 성공 메일
    //  - 미결제 & 기한 만료: 현재 대기자 FAILED 후, 1순위였다면 다음 고유 사용자로 승계(하이브리드,
    //    최대 2명), 이미 2순위(마지막 후보)였다면 경매 무산
    //  - 기한 전: 아무 것도 하지 않음(다음 사이클 재검사)
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processPaymentFollowUp(UUID musicUuid) throws IamportResponseException, IOException {
        Music music = musicRepository.findById(musicUuid)
                .orElseThrow(() -> new IllegalArgumentException("Invalid music UUID: " + musicUuid));
        User composer = music.getUser();

        List<Bid> bids = bidRepository.findByMusicOrderByPriceDescCreatedAtAsc(music);
        if (bids.isEmpty()) {
            return;
        }

        // 현재 결제 대기 중인 입찰 (없으면 정산할 것이 없음)
        Bid pending = bids.stream()
                .filter(b -> "PENDING".equals(b.getStatus()))
                .findFirst()
                .orElse(null);
        if (pending == null) {
            return;
        }

        LocalDateTime now = LocalDateTime.now(clock);
        // 현재 대기자가 최고가(1순위)면 D+3, 승계된 다음 고유 사용자(2순위)면 D+6.
        boolean isFirstCandidate = pending.getBidUuid().equals(bids.get(0).getBidUuid());
        LocalDateTime deadline = isFirstCandidate
                ? music.getAuctionEndTime().plusDays(3)
                : music.getAuctionEndTime().plusDays(6);

        Payment payment = paymentRepository.findByBid(pending);
        boolean paid = payment != null && "PAID".equals(payment.getStatus());

        if (paid) {
            // 결제 완료 노션의 1-(1)-①, 1-(2)-①, 2-(1)-①
            if (!pending.isComposerEmailSent()) {
                emailService.sendBidSuccessToComposer(composer, music);
                pending.setComposerEmailSent(true);
            }
            pending.setStatus("COMPLETED");
            bidRepository.save(pending);
            return;
        }

        // 미결제 상태에서 기한이 지났을 때만 다음 단계로 전이
        if (deadline.isBefore(now)) {
            pending.setStatus("FAILED");
            bidRepository.save(pending);

            if (isFirstCandidate) {
                // [하이브리드 정책] 1순위와 "다른 사용자"의 최고 입찰을 차순위로 승계. 가격 내림차순이라
                // 조건을 만족하는 첫 항목이 곧 차순위 고유 사용자의 최고가. 고유 사용자를 하나만 더
                // 보므로 "최대 2명" 상한이 내재된다.
                Bid next = bids.stream()
                        .filter(b -> !b.getUser().getUserUUID().equals(pending.getUser().getUserUUID()))
                        .findFirst()
                        .orElse(null);

                if (next != null) { // 다음 고유 사용자 승계. 노션의 1-(2)
                    if (!next.isBidderEmailSent()) {
                        emailService.sendBidSuccessToBidder(next.getUser(), music);
                        next.setBidderEmailSent(true);
                    }
                    next.setStatus("PENDING");
                    bidRepository.save(next);
                    // next 의 D+6 기한은 다음 사이클에서 검사한다.
                } else { // 1순위와 다른 사용자가 없음(동일인 입찰뿐) → 경매 무산. 노션의 2-(1)-②
                    failAuction(music, composer);
                }
            } else { // 이미 2순위(마지막 후보)까지 실패 → 경매 무산. 노션의 1-(2)-②
                failAuction(music, composer);
            }
        }
    }

    // 경매 무산 처리 — 작곡가에게 실패 메일 1회(멱등)
    private void failAuction(Music music, User composer) {
        if (!music.isAuctionFailureEmailSent()) {
            emailService.sendAuctionFailureToComposer(composer, music);
            music.setAuctionFailureEmailSent(true);
            musicRepository.save(music);
        }
    }

    // [경매 마감 잡] status=0 이면서 종료 시각이 지난 곡만 대상으로 최초 마감을 수행한다.
    //
    // [트랜잭션 경계 주의 — N5] 아래 루프의 processAuctionEnd(uuid)는 "같은 빈의 자기 호출"이라
    // Spring AOP 프록시를 거치지 않는다. 따라서 processAuctionEnd 에 붙은 @Transactional(REQUIRES_NEW)는
    // 이 경로에서 무시되고, 스케줄러 메서드 하나가 사이클 전체의 단일 트랜잭션이 된다(곡별 격리 아님).
    // 한 곡의 런타임 예외는 사이클 전체를 되돌리고 루프를 중단시킬 수 있다. 곡별 실패 격리는 N5에서
    // self-invocation 제거(별도 빈/트랜잭션)로 해결한다. ※ REQUIRES_NEW 는 PaymentController가
    // 프록시로 외부 호출하는 processPaymentFollowUp 경로에서는 정상 적용된다.
    //
    // [failed 집계 한계] 아래 catch 는 IamportResponseException·IOException 만 센다. 그 외 런타임
    // 예외(DB 오류·IllegalArgumentException·NPE 등)는 잡히지 않아 failed 에 반영되지 않고 요약 로그도
    // 남지 않을 수 있다 → "실패 건수 관측 완성"이라고 주장하지 않는다.
    @Override
    @Scheduled(fixedRate = 10000) // 10초 간격으로 실행
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void checkAuctionEnd() throws IamportResponseException, IOException {
        long startMs = System.currentTimeMillis();
        Runtime rt = Runtime.getRuntime();
        long heapBeforeMb = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
        // 종료 대상 = status=0 AND 마감 시각 경과. 이미 마감(status=1)된 곡은 다시 선정되지 않는다.
        List<UUID> targets = musicRepository.findUuidsToClose(LocalDateTime.now(clock));
        int processed = 0;
        int failed = 0;
        for (UUID musicUuid : targets) {
            try {
                processAuctionEnd(musicUuid);
                processed++;
            } catch (IamportResponseException | IOException e) {
                failed++;
                log.error("Error closing auction for music ID: {}", musicUuid, e);
            }
        }
        long heapAfterMb = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
        // [BATCH] 대상/처리/실패/소요/힙 — Step 5 성능 스냅샷의 측정 근거.
        log.info("[BATCH] job=auction-close targets={} processed={} failed={} elapsedMs={} heapBeforeMb={} heapAfterMb={}",
                targets.size(), processed, failed, System.currentTimeMillis() - startMs, heapBeforeMb, heapAfterMb);
    }

    // [결제 후속 잡] 결제 대기(PENDING) 입찰이 있는 곡만 대상으로 정산·승계를 처리한다.
    // 트랜잭션 경계·failed 집계 한계는 checkAuctionEnd 주석 참고(자기 호출로 곡별 REQUIRES_NEW 무효, N5).
    @Override
    @Scheduled(fixedRate = 10000) // 10초 간격으로 실행
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void checkPendingPayments() throws IamportResponseException, IOException {
        long startMs = System.currentTimeMillis();
        Runtime rt = Runtime.getRuntime();
        long heapBeforeMb = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
        List<UUID> targets = bidRepository.findMusicUuidsWithPendingBid();
        int processed = 0;
        int failed = 0;
        for (UUID musicUuid : targets) {
            try {
                processPaymentFollowUp(musicUuid);
                processed++;
            } catch (IamportResponseException | IOException e) {
                failed++;
                log.error("Error processing payment follow-up for music ID: {}", musicUuid, e);
            }
        }
        long heapAfterMb = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
        // [BATCH] 대상/처리/실패/소요/힙 — Step 5 성능 스냅샷의 측정 근거.
        log.info("[BATCH] job=payment-followup targets={} processed={} failed={} elapsedMs={} heapBeforeMb={} heapAfterMb={}",
                targets.size(), processed, failed, System.currentTimeMillis() - startMs, heapBeforeMb, heapAfterMb);
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
            pendingBidDTO.setMusicCoverUrl(mediaUrlIssuer.coverUrl(bid.getMusic().getCover()));
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
            completedBidDTO.setMusicCoverUrl(mediaUrlIssuer.coverUrl(bid.getMusic().getCover()));
            completedBidDTO.setMusicTitle(bid.getMusic().getTitle());
            completedBidDTO.setComposer(bid.getMusic().getUser().getNickname());
            completedBidDTO.setBidPrice(bid.getPrice());
            completedBidDTO.setPaid(true);
            completedBidDTO.setDownloadUrl("/api/mypage/download/" + bid.getMusic().getMusicUuid()); // 음원 파일 다운로드 Url

            return completedBidDTO;
        });
    }


}
