package com.systemdesign.googlenews.repository;

import com.systemdesign.googlenews.model.Topic;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TopicRepository extends JpaRepository<Topic, String> {

    Optional<Topic> findByName(String name);

    Optional<Topic> findBySlug(String slug);
}
