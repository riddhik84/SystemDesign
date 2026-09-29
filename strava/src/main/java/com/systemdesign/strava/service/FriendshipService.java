package com.systemdesign.strava.service;

import com.systemdesign.strava.model.Friendship;
import com.systemdesign.strava.repository.FriendshipRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Social-graph service resolving a user's friends.
 *
 * <p>Strava's social model is symmetric (mutual "following"): when Alice and Bob become friends
 * we store BOTH directions in the {@code friendships} table, so a single-column lookup on
 * {@code user_id} yields all of a user's friends. Feed and live-activity reads fan out from this
 * friend set, so it is deliberately kept small and cheap to query (indexed on {@code user_id}).
 */
@Service
public class FriendshipService {

    private static final Logger log = LoggerFactory.getLogger(FriendshipService.class);

    private final FriendshipRepository friendshipRepository;

    public FriendshipService(FriendshipRepository friendshipRepository) {
        this.friendshipRepository = friendshipRepository;
    }

    /**
     * Return the ids of every user that {@code userId} is friends with.
     *
     * @param userId the user whose friends to resolve
     * @return list of friend user ids (empty if the user has no friends)
     */
    public List<String> getFriendIds(String userId) {
        List<String> friendIds = friendshipRepository.findByUserId(userId).stream()
            .map(Friendship::getFriendId)
            .collect(Collectors.toList());
        log.debug("Resolved {} friend(s) for userId={}", friendIds.size(), userId);
        return friendIds;
    }
}
