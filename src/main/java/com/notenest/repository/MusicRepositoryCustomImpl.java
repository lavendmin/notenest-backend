package com.notenest.repository;

import com.notenest.domain.QMusic;
import com.notenest.domain.QUser;
import com.notenest.dto.MusicSummaryDTO;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.util.StringUtils;

import java.util.List;

public class MusicRepositoryCustomImpl implements MusicRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    public MusicRepositoryCustomImpl(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    @Override
    public Page<MusicSummaryDTO> searchSummaries(
            String majorGenre, String hashtags, Double minPrice, Double maxPrice,
            String searchTerm, String sortBy, Pageable pageable) {

        QMusic m = QMusic.music;
        QUser u = QUser.user;

        BooleanBuilder where = buildWhere(m, u, majorGenre, hashtags, minPrice, maxPrice, searchTerm);

        // 페이지 본문 — audio 는 SELECT 절에 넣지 않는다(커버 image 만 포함).
        List<MusicSummaryDTO> rows = queryFactory
                .select(Projections.constructor(MusicSummaryDTO.class,
                        m.musicUuid, m.title, m.startingPrice, u.nickname,
                        m.currentHighestBid, m.auctionEndTime, m.likeCount, m.image))
                .from(m)
                .leftJoin(m.user, u)
                .where(where)
                .orderBy(toOrders(m, sortBy))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        // count — 동일 where 재사용(Criteria 처럼 술어를 다시 만들 필요 없음).
        Long total = queryFactory
                .select(m.count())
                .from(m)
                .leftJoin(m.user, u)
                .where(where)
                .fetchOne();

        return new PageImpl<>(rows, pageable, total == null ? 0L : total);
    }

    private BooleanBuilder buildWhere(QMusic m, QUser u, String majorGenre, String hashtags,
                                      Double minPrice, Double maxPrice, String searchTerm) {
        BooleanBuilder where = new BooleanBuilder();
        where.and(m.status.eq(0)); // 진행 중인 곡만

        if (StringUtils.hasText(searchTerm)) {
            String kw = searchTerm.trim();
            where.and(m.title.contains(kw)
                    .or(m.subtitle.contains(kw))
                    .or(m.majorGenre.contains(kw))
                    .or(m.hashtag.contains(kw))
                    .or(u.nickname.contains(kw)));
        }

        if (StringUtils.hasText(majorGenre)) {
            where.and(m.majorGenre.eq(majorGenre));
        }

        if (StringUtils.hasText(hashtags)) {
            for (String tag : hashtags.split(",")) {
                if (StringUtils.hasText(tag)) {
                    where.and(m.hashtag.contains(tag.trim()));
                }
            }
        }

        // 가격은 최고 입찰가(없으면 시작가)로 판단
        NumberExpression<Double> price = m.currentHighestBid.coalesce(m.startingPrice);
        if (minPrice != null && maxPrice != null) {
            where.and(price.between(minPrice, maxPrice));
        } else if (minPrice != null) {
            where.and(price.goe(minPrice));
        } else if (maxPrice != null) {
            where.and(price.loe(maxPrice));
        }

        return where;
    }

    // 정렬: 최신순(기본), 가격순(최고가 desc, null 마지막 → 시작가 desc), 좋아요순
    private OrderSpecifier<?>[] toOrders(QMusic m, String sortBy) {
        if ("price".equals(sortBy)) {
            return new OrderSpecifier<?>[]{ m.currentHighestBid.desc().nullsLast(), m.startingPrice.desc() };
        }
        if ("like".equals(sortBy)) {
            return new OrderSpecifier<?>[]{ m.likeCount.desc() };
        }
        return new OrderSpecifier<?>[]{ m.createdAt.desc() };
    }
}
