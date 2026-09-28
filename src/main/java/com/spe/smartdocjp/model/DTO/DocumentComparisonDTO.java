package com.spe.smartdocjp.model.DTO;

import java.util.List;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** Evidence-backed exact text comparison between two persisted documents. */
public record DocumentComparisonDTO(
        DocumentSummary documentA,
        DocumentSummary documentB,
        int sharedCount,
        int onlyACount,
        int onlyBCount,
        List<Snippet> onlyA,
        List<Snippet> onlyB,
        boolean onlyATruncated,
        boolean onlyBTruncated
) {
    public record Request(
            @NotNull(message = "documentIdA は必須です") @Positive(message = "documentIdA は正の数で指定してください") Long documentIdA,
            @NotNull(message = "documentIdB は必須です") @Positive(message = "documentIdB は正の数で指定してください") Long documentIdB
    ) {}

    public record DocumentSummary(Long id, String title) {}

    public record Snippet(Long documentId, Integer chunkIndex, Integer pageNumber, String text) {}
}
