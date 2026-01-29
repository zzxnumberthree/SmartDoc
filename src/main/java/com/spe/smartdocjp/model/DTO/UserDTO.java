package com.spe.smartdocjp.model.DTO;

import com.spe.smartdocjp.model.entity.User;

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
