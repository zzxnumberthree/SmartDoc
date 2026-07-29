package com.spe.smartdocjp.controller;

import com.spe.smartdocjp.common.ApiResponse;
import com.spe.smartdocjp.model.DTO.SearchDTOs.*;
import com.spe.smartdocjp.service.RagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for semantic search and RAG intelligent question-answering over documents.
 */
@Tag(name = "智能搜索", description = "基于向量数据库的语义检索与 RAG 问答")
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
@Slf4j
public class SearchController {

    private final RagService ragService;

    /**
     * Performs semantic similarity search against stored document chunks.
     * @param request The search query request containing query string, topK, and threshold.
     * @return List of relevant document chunks with similarity scores.
     */
    @Operation(summary = "向量检索", description = "根据输入的自然语言问题，从文档切片库中检索相似度最高的内容片段")
    @PostMapping("/query")
    public ResponseEntity<ApiResponse<List<SearchResultResponse>>> search(@Valid @RequestBody SearchQueryRequest request) {
        log.info("REST request for vector search: {}", request.query());
        List<SearchResultResponse> results = ragService.search(
                request.query(),
                request.getEffectiveTopK(),
                request.getEffectiveThreshold()
        );
        return ResponseEntity.ok(ApiResponse.success(results, "检索成功"));
    }

    /**
     * Answers a user question intelligently using RAG and cites source document chunks.
     * @param request The ask request containing question and topK context limit.
     * @return AskResponse containing AI-generated answer in Japanese and cited sources.
     */
    @Operation(summary = "智能问答", description = "基于 RAG（检索增强生成）回答问题，提供引用来源")
    @PostMapping("/ask")
    public ResponseEntity<ApiResponse<AskResponse>> ask(@Valid @RequestBody AskRequest request) {
        log.info("REST request for RAG ask: {}", request.question());
        AskResponse response = ragService.ask(
                request.question(),
                request.getEffectiveTopK()
        );
        return ResponseEntity.ok(ApiResponse.success(response, "问答成功"));
    }
}
