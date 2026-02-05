package com.spe.smartdocjp.model.DTO;

import com.spe.smartdocjp.model.entity.User;

/**
 Data Transfer Object (DTO) for user information.
 @param id The unique identifier of the user.
 @param userName The username of the user.
 @param email The email address of the user.
 */
public record UserDTO(
        Long id,
        String userName,
        String email
) {
    public static UserDTO from(User u) {
        return new UserDTO(
                u.getId(),
                u.getUsername(),
                u.getEmail()
        );
    }

}
