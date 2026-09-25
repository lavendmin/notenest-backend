package com.notenest.service;

import com.notenest.domain.Music;
import com.notenest.domain.User;
import com.notenest.repository.BidRepository;
import org.springframework.stereotype.Component;

/**
 * 전체 데모 접근 정책.
 *
 * 전체 데모는 판매자 본인 또는 이 곡의 결제 완료(Bid.status=COMPLETED) 낙찰자만 받는다.
 * 저장 방식(DB LOB·S3)과 전달 방식(바이트·presigned URL)과 분리해 두어, 이후 승인받은 구매자의
 * 거래 전 검토 같은 규칙을 이 컴포넌트에만 추가할 수 있게 한다.
 *
 * 이 접근 권한은 시스템의 파일 다운로드 권한일 뿐, 곡의 저작재산권 양도나 이용허락을 뜻하지 않는다.
 */
@Component
public class FullDemoAccessPolicy {

    static final String PAID_BID_STATUS = "COMPLETED";

    private final BidRepository bidRepository;

    public FullDemoAccessPolicy(BidRepository bidRepository) {
        this.bidRepository = bidRepository;
    }

    public boolean canAccess(Music music, User user) {
        if (music.getUser().getUserUUID().equals(user.getUserUUID())) {
            return true;
        }
        return bidRepository.existsByMusicAndUserAndStatus(music, user, PAID_BID_STATUS);
    }
}
