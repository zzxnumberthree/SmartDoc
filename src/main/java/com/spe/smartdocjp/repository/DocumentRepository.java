package com.spe.smartdocjp.repository;

import com.spe.smartdocjp.model.entity.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository // zeng
public interface DocumentRepository extends JpaRepository<Document, Long>{

    // 查找某用户所有未删除的文件
    List<Document> findByUserId(Long userId);

    Optional<Document> findByIdAndUserId(Long id, Long userId);

    List<Document> findByUserIdOrderByCreatedAtDesc(Long userId);

    Page<Document> findByUserId(Long userId, Pageable pageable);

    @Query("SELECT d FROM Document d WHERE d.user.id = :userId")
    List<Document> findUploadedDocumentsByUserId(@Param("userId") Long userId);

    // Native queries intentionally bypass the entity-level soft-delete restriction
    // so the recycle-bin endpoints can load deleted rows.
    @Query(value = "SELECT * FROM documents WHERE is_deleted = true ORDER BY created_at DESC", nativeQuery = true)
    List<Document> findAllDeletedDocuments();

    @Query(value = "SELECT * FROM documents WHERE is_deleted = true AND user_id = :userId ORDER BY created_at DESC", nativeQuery = true)
    List<Document> findDeletedDocumentsByUserId(@Param("userId") Long userId);

}
