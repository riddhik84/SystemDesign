package com.systemdesign.googlenews.repository;

import com.systemdesign.googlenews.model.Source;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface SourceRepository extends JpaRepository<Source, String> {

    List<Source> findByIsActiveTrueAndNextCrawlTimeBefore(Instant time);

    List<Source> findByIsActiveTrue();
}
