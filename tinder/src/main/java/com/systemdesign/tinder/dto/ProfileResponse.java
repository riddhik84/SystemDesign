package com.systemdesign.tinder.dto;

import com.systemdesign.tinder.model.User;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProfileResponse {
    private String id;
    private String name;
    private Integer age;
    private User.Gender gender;
    private String bio;
    private Double latitude;
    private Double longitude;
    private Double distance;

    public static ProfileResponse fromUser(User user) {
        return new ProfileResponse(
            user.getId(),
            user.getName(),
            user.getAge(),
            user.getGender(),
            user.getBio(),
            user.getLatitude(),
            user.getLongitude(),
            null
        );
    }

    public static ProfileResponse fromUserWithDistance(User user, Double distance) {
        return new ProfileResponse(
            user.getId(),
            user.getName(),
            user.getAge(),
            user.getGender(),
            user.getBio(),
            user.getLatitude(),
            user.getLongitude(),
            distance
        );
    }
}
