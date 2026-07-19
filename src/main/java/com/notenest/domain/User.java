package com.notenest.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Getter
@Setter
@Table (name = "user")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "user_uuid")
    private UUID userUUID;

    @Column(name = "email", unique = true)
    private String email;
    @Column(name = "password")
    private String password;

    @Transient
    private String passwordCheck;

    @Column(name = "name")
    private String name;
    @Column(name = "nickname", unique = true)
    private String nickname;
    @Column(name = "phone_no")
    private String phoneNo;

    @Column(name = "email_verified")
    private boolean emailVerified;
    
    @Column(name = "agreement")
    private boolean agreement;

    private String role;
}
