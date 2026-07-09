package com.spe.smartdocjp.controller;

import com.spe.smartdocjp.model.DTO.SearchDTOs.*;
import com.spe.smartdocjp.service.RagService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for semantic search and RAG intelligent question-answering over documents.
 */
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
    @PostMapping("/query")
    public ResponseEntity<List<SearchResultResponse>> search(@Valid @RequestBody SearchQueryRequest request) {
        log.info("REST request for vector search: {}", request.query());
        List<SearchResultResponse> results = ragService.search(
                request.query(),
                request.getEffectiveTopK(),
                request.getEffectiveThreshold()
        );
        return ResponseEntity.ok(results);
    }

    /**
     * Answers a user question intelligently using RAG and cites source document chunks.
     * @param request The ask request containing question and topK context limit.
     * @return AskResponse containing AI-generated answer in Japanese and cited sources.
     */
    @PostMapping("/ask")
    public ResponseEntity<AskResponse> ask(@Valid @RequestBody AskRequest request) {
        log.info("REST request for RAG ask: {}", request.question());
        AskResponse response = ragService.ask(
                request.question(),
                request.getEffectiveTopK()
        );
        return ResponseEntity.ok(response);
    }
}
