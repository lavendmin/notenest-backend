package com.notenest.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class Composer {

    @Id
    private String composer;

    private Long entryCount;

    private Boolean popular;

    private Boolean steadyWork;

    private Boolean hitSong;

}
