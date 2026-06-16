package com.systemdesign.gopuff.repository;

import com.systemdesign.gopuff.model.Item;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ItemRepository extends JpaRepository<Item, Long> {

    Optional<Item> findByItemId(String itemId);

    List<Item> findByItemIdIn(List<String> itemIds);
}
