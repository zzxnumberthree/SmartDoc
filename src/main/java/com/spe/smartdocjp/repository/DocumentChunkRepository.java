package com.spe.smartdocjp.repository;

import com.spe.smartdocjp.model.entity.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, Long> {

    /**
     * Finds all chunks for a specific document, ordered by their index.
     * @param documentId The ID of the document.
     * @return A list of ordered document chunks.
     */
    List<DocumentChunk> findByDocumentIdOrderByChunkIndexAsc(Long documentId);

    /**
     * Deletes (logically via SQLDelete) all chunks belonging to a document.
     * @param documentId The ID of the document whose chunks should be deleted.
     */
    @Modifying
    @Query("DELETE FROM DocumentChunk c WHERE c.document.id = :documentId")
    void deleteByDocumentId(@Param("documentId") Long documentId);
}
