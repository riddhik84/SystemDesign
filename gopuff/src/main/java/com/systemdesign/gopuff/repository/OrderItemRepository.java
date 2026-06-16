package com.systemdesign.gopuff.repository;

import com.systemdesign.gopuff.model.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    List<OrderItem> findByOrderId(String orderId);
}
