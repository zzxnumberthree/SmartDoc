package com.spe.smartdocjp.model.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

/**
 * Entity representing a chunk of a document for vector search and RAG.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "document_chunks")
@Entity
@SuperBuilder
@SQLDelete(sql = "UPDATE document_chunks SET is_deleted = true WHERE id = ?")
@SQLRestriction("is_deleted = false")
public class DocumentChunk extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    @Column(name = "vector_id")
    private String vectorId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(columnDefinition = "TEXT")
    private String metadata; // JSON or stringified metadata
}
