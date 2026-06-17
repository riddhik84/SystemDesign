package com.systemdesign.googlenews.service;

import com.systemdesign.googlenews.model.Topic;
import com.systemdesign.googlenews.repository.TopicRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TopicClassifierService {

    private final TopicRepository topicRepository;

    private static final Map<String, Set<String>> TOPIC_KEYWORDS = Map.of(
        "Technology", Set.of("ai", "software", "hardware", "tech", "startup", "app", "digital", "cyber", "data", "cloud"),
        "Politics", Set.of("election", "government", "president", "policy", "congress", "senate", "vote", "political", "legislation"),
        "Sports", Set.of("football", "basketball", "soccer", "olympics", "nfl", "nba", "mlb", "game", "player", "team", "championship"),
        "Business", Set.of("market", "stock", "economy", "finance", "company", "revenue", "profit", "trade", "investment", "startup"),
        "Entertainment", Set.of("movie", "film", "music", "celebrity", "actor", "actress", "show", "series", "entertainment", "award"),
        "Health", Set.of("health", "medical", "disease", "hospital", "doctor", "vaccine", "treatment", "patient", "medicine"),
        "Science", Set.of("research", "scientist", "study", "discovery", "space", "physics", "biology", "chemistry", "experiment"),
        "World", Set.of("international", "global", "country", "nation", "foreign", "war", "peace", "crisis", "diplomatic")
    );

    public Set<Topic> classifyArticle(String title, String content) {
        String text = (title + " " + content).toLowerCase();

        return TOPIC_KEYWORDS.entrySet().stream()
            .filter(entry -> containsKeywords(text, entry.getValue()))
            .map(entry -> topicRepository.findByName(entry.getKey()))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .collect(Collectors.toSet());
    }

    private boolean containsKeywords(String text, Set<String> keywords) {
        return keywords.stream().anyMatch(text::contains);
    }
}
