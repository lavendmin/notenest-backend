package com.notenest.repository;

import com.notenest.domain.Bid;
import com.notenest.domain.Music;
import com.notenest.domain.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface BidRepository extends JpaRepository<Bid, UUID> {

    // 특정 곡에 대한 입찰들을 가격이 높은 순으로 조회, 가격이 같으면 먼저 데이터베이스에 들어온 순으로
    Page<Bid> findByMusicOrderByPriceDescCreatedAtAsc(Music music, Pageable pageable);
    List<Bid> findByMusicOrderByPriceDescCreatedAtAsc(Music music);

    Page<Bid> findByUser(User user, Pageable pageable);

    // 마이페이지 입찰내역 - 인기순 정렬 & 검색 (진행 중인 것만)
    @Query("SELECT b FROM Bid b WHERE b.user = :user " +
            "AND b.status IS NULL " +  // 경매 진행 중인 입찰만 조회
            "AND (:fromDate IS NULL OR b.createdAt >= :fromDate) " +
            "AND (:toDate IS NULL OR b.createdAt <= :toDate) " +
            "AND (:searchTerm IS NULL OR b.music.title LIKE %:searchTerm% OR b.music.user.nickname LIKE %:searchTerm%) " +
            "ORDER BY b.music.likeCount DESC")
    Page<Bid> findOngoingBidsByUserAndDateRangeAndSearchTermOrderByLikeCount(User user, LocalDateTime fromDate, LocalDateTime toDate, String searchTerm, Pageable pageable);

    // 마이페이지 입찰내역 - 가격순 정렬 & 검색 (진행 중인 것만)
    @Query("SELECT b FROM Bid b WHERE b.user = :user " +
            "AND b.status IS NULL " +  // 경매 진행 중인 입찰만 조회
            "AND (:fromDate IS NULL OR b.createdAt >= :fromDate) " +
            "AND (:toDate IS NULL OR b.createdAt <= :toDate) " +
            "AND (:searchTerm IS NULL OR b.music.title LIKE %:searchTerm% OR b.music.user.nickname LIKE %:searchTerm%) " +
            "ORDER BY b.price DESC")
    Page<Bid> findOngoingBidsByUserAndDateRangeAndSearchTermOrderByPrice(User user, LocalDateTime fromDate, LocalDateTime toDate, String searchTerm, Pageable pageable);
    
    // 마이페이지 입찰내역 - 최신순 정렬 & 검색 (진행 중인 것만)
    @Query("SELECT b FROM Bid b WHERE b.user = :user " +
            "AND b.status IS NULL " +  // 경매 진행 중인 입찰만 조회
            "AND (:fromDate IS NULL OR b.createdAt >= :fromDate) " +
            "AND (:toDate IS NULL OR b.createdAt <= :toDate) " +
            "AND (:searchTerm IS NULL OR b.music.title LIKE %:searchTerm% OR b.music.user.nickname LIKE %:searchTerm%) " +
            "ORDER BY b.createdAt DESC")
    Page<Bid> findOngoingBidsByUserAndDateRangeAndSearchTermOrderByCreatedAt(User user, LocalDateTime fromDate, LocalDateTime toDate, String searchTerm, Pageable pageable);


    // 마이페이지 낙찰내역-결제 대기 : 인기순 정렬 & 검색
    @Query("SELECT b FROM Bid b WHERE b.user = :user AND b.status = 'PENDING' " +
            "AND (:fromDate IS NULL OR b.createdAt >= :fromDate) " +
            "AND (:toDate IS NULL OR b.createdAt <= :toDate) " +
            "AND (:searchTerm IS NULL OR b.music.title LIKE %:searchTerm% OR b.music.user.nickname LIKE %:searchTerm%) " +
            "ORDER BY b.music.likeCount DESC")
    Page<Bid> findPendingBidsByUserAndDateRangeAndSearchTermOrderByLikeCount(User user, LocalDateTime fromDate, LocalDateTime toDate, String searchTerm, Pageable pageable);

    // 마이페이지 낙찰내역-결제 대기 : 가격순 정렬 & 검색 -> 삭제 예정
    @Query("SELECT b FROM Bid b WHERE b.user = :user AND b.status = 'PENDING' " +
            "AND (:fromDate IS NULL OR b.createdAt >= :fromDate) " +
            "AND (:toDate IS NULL OR b.createdAt <= :toDate) " +
            "AND (:searchTerm IS NULL OR b.music.title LIKE %:searchTerm% OR b.music.user.nickname LIKE %:searchTerm%) " +
            "ORDER BY b.price DESC")
    Page<Bid> findPendingBidsByUserAndDateRangeAndSearchTermOrderByPrice(User user, LocalDateTime fromDate, LocalDateTime toDate, String searchTerm, Pageable pageable);

    // 마이페이지 낙찰내역-결제 대기 : 최신순 정렬 & 검색
    @Query("SELECT b FROM Bid b WHERE b.user = :user AND b.status = 'PENDING' " +
            "AND (:fromDate IS NULL OR b.createdAt >= :fromDate) " +
            "AND (:toDate IS NULL OR b.createdAt <= :toDate) " +
            "AND (:searchTerm IS NULL OR b.music.title LIKE %:searchTerm% OR b.music.user.nickname LIKE %:searchTerm%) " +
            "ORDER BY b.createdAt DESC")
    Page<Bid> findPendingBidsByUserAndDateRangeAndSearchTermOrderByCreatedAt(User user, LocalDateTime fromDate, LocalDateTime toDate, String searchTerm, Pageable pageable);

    // 마이페이지 낙찰내역-결제 완료 : 인기순 정렬 & 검색 -> 삭제 예정
    @Query("SELECT b FROM Bid b WHERE b.user = :user AND b.status = 'COMPLETED' " +
            "AND (:fromDate IS NULL OR b.createdAt >= :fromDate) " +
            "AND (:toDate IS NULL OR b.createdAt <= :toDate) " +
            "AND (:searchTerm IS NULL OR b.music.title LIKE %:searchTerm% OR b.music.user.nickname LIKE %:searchTerm%) " +
            "ORDER BY b.music.likeCount DESC")
    Page<Bid> findCompletedBidsByUserAndDateRangeAndSearchTermOrderByLikeCount(User user, LocalDateTime fromDate, LocalDateTime toDate, String searchTerm, Pageable pageable);

    // 마이페이지 낙찰내역-결제 완료 : 가격순 정렬 & 검색
    @Query("SELECT b FROM Bid b WHERE b.user = :user AND b.status = 'COMPLETED' " +
            "AND (:fromDate IS NULL OR b.createdAt >= :fromDate) " +
            "AND (:toDate IS NULL OR b.createdAt <= :toDate) " +
            "AND (:searchTerm IS NULL OR b.music.title LIKE %:searchTerm% OR b.music.user.nickname LIKE %:searchTerm%) " +
            "ORDER BY b.price DESC")
    Page<Bid> findCompletedBidsByUserAndDateRangeAndSearchTermOrderByPrice(User user, LocalDateTime fromDate, LocalDateTime toDate, String searchTerm, Pageable pageable);

    // 마이페이지 낙찰내역-결제 완료 : 최신순 정렬 & 검색 (Payment의 createdAt 기준)
    @Query("SELECT b FROM Bid b JOIN Payment p ON b.bidUuid = p.bid.bidUuid WHERE b.user = :user AND b.status = 'COMPLETED' " +
            "AND (:fromDate IS NULL OR p.createdAt >= :fromDate) " +
            "AND (:toDate IS NULL OR p.createdAt <= :toDate) " +
            "AND (:searchTerm IS NULL OR b.music.title LIKE %:searchTerm% OR b.music.user.nickname LIKE %:searchTerm%) " +
            "ORDER BY p.createdAt DESC")
    Page<Bid> findCompletedBidsByUserAndDateRangeAndSearchTermOrderByCreatedAt(User user, LocalDateTime fromDate, LocalDateTime toDate, String searchTerm, Pageable pageable);
}
