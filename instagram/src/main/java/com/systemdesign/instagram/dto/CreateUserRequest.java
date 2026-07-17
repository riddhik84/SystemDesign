package com.systemdesign.instagram.dto;

import lombok.Data;

@Data
public class CreateUserRequest {
    private String username;
    private String displayName;
    private String email;
    private String bio;
}
