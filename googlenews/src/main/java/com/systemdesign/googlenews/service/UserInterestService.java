package com.systemdesign.googlenews.service;

import com.systemdesign.googlenews.dto.InterestRequest;
import com.systemdesign.googlenews.model.Source;
import com.systemdesign.googlenews.model.Topic;
import com.systemdesign.googlenews.model.UserInterest;
import com.systemdesign.googlenews.repository.SourceRepository;
import com.systemdesign.googlenews.repository.TopicRepository;
import com.systemdesign.googlenews.repository.UserInterestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserInterestService {

    private final UserInterestRepository userInterestRepository;
    private final TopicRepository topicRepository;
    private final SourceRepository sourceRepository;

    @Transactional(readOnly = true)
    public List<UserInterest> getUserInterests(String userId) {
        return userInterestRepository.findByUserId(userId);
    }

    @Transactional
    @CacheEvict(value = "feed", key = "#userId + '_*'")
    public UserInterest addInterest(String userId, InterestRequest request) {
        UserInterest.UserInterestBuilder builder = UserInterest.builder()
            .id(UUID.randomUUID().toString())
            .userId(userId)
            .interestType(request.getType());

        switch (request.getType()) {
            case TOPIC:
                Topic topic = topicRepository.findById(request.getValue())
                    .orElseThrow(() -> new IllegalArgumentException("Topic not found: " + request.getValue()));
                builder.topic(topic);
                break;
            case SOURCE:
                Source source = sourceRepository.findById(request.getValue())
                    .orElseThrow(() -> new IllegalArgumentException("Source not found: " + request.getValue()));
                builder.source(source);
                break;
            case KEYWORD:
                builder.keyword(request.getValue());
                break;
        }

        UserInterest interest = builder.build();
        return userInterestRepository.save(interest);
    }

    @Transactional
    @CacheEvict(value = "feed", key = "#userId + '_*'")
    public void removeInterest(String userId, String interestId) {
        userInterestRepository.deleteByUserIdAndId(userId, interestId);
    }
}
