package com.notenest.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Getter
@Setter
@Table(name = "comments")
public class Comments extends BaseTime{
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "comment_uuid")
    private UUID commentUuid;

    @ManyToOne
    @JoinColumn(name = "board_uuid")
    private Board board;

    @ManyToOne
    @JoinColumn(name = "user_uuid")
    private User user;

    @Column(name = "is_composer")
    private boolean isComposer;

    @Column(name = "content")
    private String content;
}
