package com.systemdesign.googlenews.config;

import com.systemdesign.googlenews.model.Source;
import com.systemdesign.googlenews.model.Topic;
import com.systemdesign.googlenews.repository.SourceRepository;
import com.systemdesign.googlenews.repository.TopicRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final TopicRepository topicRepository;
    private final SourceRepository sourceRepository;

    @Override
    public void run(String... args) {
        initializeTopics();
        initializeSources();
    }

    private void initializeTopics() {
        if (topicRepository.count() > 0) {
            log.info("Topics already initialized");
            return;
        }

        List<Topic> topics = List.of(
            Topic.builder().id(UUID.randomUUID().toString()).name("Technology").slug("technology").followerCount(0).build(),
            Topic.builder().id(UUID.randomUUID().toString()).name("Politics").slug("politics").followerCount(0).build(),
            Topic.builder().id(UUID.randomUUID().toString()).name("Sports").slug("sports").followerCount(0).build(),
            Topic.builder().id(UUID.randomUUID().toString()).name("Business").slug("business").followerCount(0).build(),
            Topic.builder().id(UUID.randomUUID().toString()).name("Entertainment").slug("entertainment").followerCount(0).build(),
            Topic.builder().id(UUID.randomUUID().toString()).name("Health").slug("health").followerCount(0).build(),
            Topic.builder().id(UUID.randomUUID().toString()).name("Science").slug("science").followerCount(0).build(),
            Topic.builder().id(UUID.randomUUID().toString()).name("World").slug("world").followerCount(0).build()
        );

        topicRepository.saveAll(topics);
        log.info("Initialized {} topics", topics.size());
    }

    private void initializeSources() {
        if (sourceRepository.count() > 0) {
            log.info("Sources already initialized");
            return;
        }

        List<Source> sources = List.of(
            Source.builder()
                .id(UUID.randomUUID().toString())
                .name("TechCrunch")
                .feedUrl("https://techcrunch.com/feed/")
                .domain("techcrunch.com")
                .feedType(Source.FeedType.RSS)
                .crawlIntervalMinutes(10)
                .trustScore(85)
                .isActive(true)
                .nextCrawlTime(Instant.now())
                .consecutiveFailures(0)
                .build(),
            Source.builder()
                .id(UUID.randomUUID().toString())
                .name("BBC News")
                .feedUrl("http://feeds.bbci.co.uk/news/rss.xml")
                .domain("bbc.com")
                .feedType(Source.FeedType.RSS)
                .crawlIntervalMinutes(5)
                .trustScore(95)
                .isActive(true)
                .nextCrawlTime(Instant.now())
                .consecutiveFailures(0)
                .build(),
            Source.builder()
                .id(UUID.randomUUID().toString())
                .name("The Verge")
                .feedUrl("https://www.theverge.com/rss/index.xml")
                .domain("theverge.com")
                .feedType(Source.FeedType.RSS)
                .crawlIntervalMinutes(10)
                .trustScore(80)
                .isActive(true)
                .nextCrawlTime(Instant.now())
                .consecutiveFailures(0)
                .build(),
            Source.builder()
                .id(UUID.randomUUID().toString())
                .name("Hacker News")
                .feedUrl("https://news.ycombinator.com/rss")
                .domain("news.ycombinator.com")
                .feedType(Source.FeedType.RSS)
                .crawlIntervalMinutes(15)
                .trustScore(75)
                .isActive(true)
                .nextCrawlTime(Instant.now())
                .consecutiveFailures(0)
                .build()
        );

        sourceRepository.saveAll(sources);
        log.info("Initialized {} sources", sources.size());
    }
}
