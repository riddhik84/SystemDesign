package com.systemdesign.googlenews.service;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import com.systemdesign.googlenews.model.Source;
import com.systemdesign.googlenews.repository.SourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URL;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class FeedCrawlerService {

    private final SourceRepository sourceRepository;
    private final ArticleProcessorService articleProcessorService;

    @Scheduled(fixedRate = 60000)
    @Transactional
    public void crawlFeeds() {
        log.info("Starting scheduled feed crawl");

        List<Source> sourcesToCrawl = sourceRepository
            .findByIsActiveTrueAndNextCrawlTimeBefore(Instant.now());

        log.info("Found {} sources to crawl", sourcesToCrawl.size());

        sourcesToCrawl.parallelStream()
            .forEach(this::crawlSource);
    }

    private void crawlSource(Source source) {
        log.debug("Crawling source: {} ({})", source.getName(), source.getFeedUrl());

        try {
            SyndFeed feed = fetchFeed(source.getFeedUrl());

            int processedCount = 0;
            for (SyndEntry entry : feed.getEntries()) {
                try {
                    articleProcessorService.processEntry(entry, source);
                    processedCount++;
                } catch (Exception e) {
                    log.error("Failed to process entry from {}: {}", source.getName(), e.getMessage());
                }
            }

            source.setLastCrawledAt(Instant.now());
            source.setNextCrawlTime(
                Instant.now().plus(source.getCrawlIntervalMinutes(), ChronoUnit.MINUTES)
            );
            source.setConsecutiveFailures(0);
            sourceRepository.save(source);

            log.info("Successfully crawled {}: {} articles processed", source.getName(), processedCount);

        } catch (Exception e) {
            log.error("Failed to crawl source {}: {}", source.getName(), e.getMessage());
            handleCrawlFailure(source);
        }
    }

    private SyndFeed fetchFeed(String feedUrl) throws Exception {
        SyndFeedInput input = new SyndFeedInput();
        return input.build(new XmlReader(new URL(feedUrl)));
    }

    private void handleCrawlFailure(Source source) {
        source.setConsecutiveFailures(source.getConsecutiveFailures() + 1);

        if (source.getConsecutiveFailures() >= 5) {
            log.warn("Source {} has failed {} times, marking as inactive",
                source.getName(), source.getConsecutiveFailures());
            source.setIsActive(false);
        } else {
            int backoffMinutes = (int) Math.pow(2, source.getConsecutiveFailures()) * 5;
            source.setNextCrawlTime(
                Instant.now().plus(backoffMinutes, ChronoUnit.MINUTES)
            );
        }

        sourceRepository.save(source);
    }
}
