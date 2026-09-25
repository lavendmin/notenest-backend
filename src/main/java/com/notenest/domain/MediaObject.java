package com.notenest.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 객체 저장소에 올린 곡 미디어 한 건의 참조(키)와 메타데이터. 바이트는 DB 에 두지 않는다.
 *
 * Music 이 커버·미리듣기·전체 데모 세 자산에 대해 컬럼 접두사(cover_·preview_·full_demo_)만 바꿔 임베드한다.
 * 네 컬럼이 모두 NULL 이면 Hibernate 는 필드를 null 로 읽는다 — 자산이 없다(백필 전, 또는 기존 곡의 미리듣기).
 * 컬럼 정의는 scripts/migration/nb1-02-add-media-object-columns.sql 과 일치해야 한다.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class MediaObject {

    @Column(name = "object_key", length = 512)
    private String objectKey;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "size")
    private Long size;

    @Column(name = "original_name", length = 255)
    private String originalName;
}
