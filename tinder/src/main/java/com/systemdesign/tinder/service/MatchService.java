package com.systemdesign.tinder.service;

import com.systemdesign.tinder.dto.MatchResponse;
import com.systemdesign.tinder.dto.ProfileResponse;
import com.systemdesign.tinder.model.Match;
import com.systemdesign.tinder.model.User;
import com.systemdesign.tinder.repository.MatchRepository;
import com.systemdesign.tinder.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class MatchService {

    private final MatchRepository matchRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<MatchResponse> getUserMatches(String userId) {
        List<Match> matches = matchRepository.findActiveMatchesByUserId(userId);

        return matches.stream()
            .map(match -> {
                String otherUserId = match.getUser1Id().equals(userId)
                    ? match.getUser2Id()
                    : match.getUser1Id();

                User otherUser = userRepository.findById(otherUserId).orElse(null);
                ProfileResponse otherProfile = otherUser != null
                    ? ProfileResponse.fromUser(otherUser)
                    : null;

                return MatchResponse.fromMatch(match, userId, otherProfile);
            })
            .collect(Collectors.toList());
    }
}
