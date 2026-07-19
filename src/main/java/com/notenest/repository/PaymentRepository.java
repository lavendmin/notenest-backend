package com.notenest.repository;

import com.notenest.domain.Bid;
import com.notenest.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    Payment findByBid(Bid firstBid);
}
