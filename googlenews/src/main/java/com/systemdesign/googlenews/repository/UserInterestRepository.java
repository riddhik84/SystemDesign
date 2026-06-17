package com.systemdesign.googlenews.repository;

import com.systemdesign.googlenews.model.UserInterest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserInterestRepository extends JpaRepository<UserInterest, String> {

    List<UserInterest> findByUserId(String userId);

    void deleteByUserIdAndId(String userId, String interestId);
}
