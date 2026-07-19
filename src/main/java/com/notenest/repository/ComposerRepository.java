package com.notenest.repository;

import com.notenest.domain.Composer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ComposerRepository extends JpaRepository<Composer, UUID> {
}
