package com.spe.smartdocjp.model.DTO;

import com.spe.smartdocjp.model.entity.Document;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 Data Transfer Object (DTO) for document information.
 @param id The unique identifier of the document.
 @param fileName The original filename of the document.
 @param uploadTime The timestamp when the document was last updated.
 */
public record DocumentDTO(

    Long id,

    @NotBlank(message = "タイトルは必須です")
    @Size(max = 100, message = "タイトルは100文字以内で入力してください")
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
