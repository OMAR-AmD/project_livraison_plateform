package com.delivery.backend.modules.delivery;

import com.delivery.backend.modules.auth.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DeliveryRepository extends JpaRepository<Delivery, UUID> {
    List<Delivery> findByClientOrderByCreatedAtDesc(User client);
    List<Delivery> findByCourierOrderByCreatedAtDesc(User courier);
    List<Delivery> findTop10ByOrderByUpdatedAtDesc();

    long countByStatus(DeliveryStatus status);
    
    @org.springframework.data.jpa.repository.Query("SELECT COUNT(d) FROM Delivery d WHERE d.status = :status AND d.updatedAt >= :date")
    long countByStatusAndUpdatedAtAfter(@org.springframework.data.repository.query.Param("status") DeliveryStatus status, @org.springframework.data.repository.query.Param("date") java.time.LocalDateTime date);

    @org.springframework.data.jpa.repository.Query("SELECT AVG(d.rating) FROM Delivery d WHERE d.rating IS NOT NULL")
    Double getAverageRating();

    /**
     * Revenue recognised by the platform.
     *
     * <p>Only {@code PAID} counts, and that state is now set when payment is captured
     * rather than when the order is created. Orders awaiting payment, voided orders
     * and refunds are therefore correctly excluded.
     */
    @org.springframework.data.jpa.repository.Query("SELECT SUM(d.price) FROM Delivery d WHERE d.paymentStatus = 'PAID'")
    Double getTotalRevenue();
}
