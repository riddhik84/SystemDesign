package com.systemdesign.newsfeed.event;

import com.systemdesign.newsfeed.model.Post;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Event published after a post is successfully created and committed to the database.
 * Used to trigger async fanout to follower feeds without creating read-before-commit races.
 */
@Getter
public class PostCreatedEvent extends ApplicationEvent {

    private final Post post;

    public PostCreatedEvent(Object source, Post post) {
        super(source);
        this.post = post;
    }
}
