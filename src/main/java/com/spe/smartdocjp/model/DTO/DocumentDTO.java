package com.spe.smartdocjp.model.DTO;

import com.spe.smartdocjp.model.entity.Document;

import java.time.LocalDateTime;

public record DocumentDTO(
    Long id,
    String fileName,
    LocalDateTime uploadTime
) {
    public static DocumentDTO from(Document d) {
        return new DocumentDTO(
            d.getId(),
            d.getOriginalFilename(),
            d.getUpdatedAt()
        );
    }
}
