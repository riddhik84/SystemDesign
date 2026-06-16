package com.systemdesign.gopuff.repository;

import com.systemdesign.gopuff.model.DistributionCenter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DistributionCenterRepository extends JpaRepository<DistributionCenter, Long> {

    List<DistributionCenter> findByActiveTrue();

    Optional<DistributionCenter> findByDcId(String dcId);
}
