package com.notenest.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Entity
@Getter
@Setter
@Table(name = "board")
public class Board extends BaseTime{
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "board_uuid")
    private UUID boardUuid;

    @ManyToOne
    @JoinColumn(name = "music_uuid")
    private Music music;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_uuid")
    private User user;

    @Column(name = "title")
    private String title;

    @Column(name = "content")
    private String content;

    @Column(name = "hits")
    private int hits;

    @OneToMany(mappedBy = "board", fetch = FetchType.EAGER, cascade = CascadeType.REMOVE)
    @OrderBy("createdTime asc")
    private List<Comments> comments;

    // 조회수 증가 메소드
    public void increaseHits() {
        this.hits++;
    }
}
