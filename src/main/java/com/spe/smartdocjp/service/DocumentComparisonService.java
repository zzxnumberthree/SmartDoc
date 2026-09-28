package com.spe.smartdocjp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spe.smartdocjp.exception.DocumentNotFoundException;
import com.spe.smartdocjp.model.DTO.DocumentComparisonDTO;
import com.spe.smartdocjp.model.entity.Document;
import com.spe.smartdocjp.model.entity.DocumentChunk;
import com.spe.smartdocjp.repository.DocumentChunkRepository;
import com.spe.smartdocjp.repository.DocumentRepository;
import com.spe.smartdocjp.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Compares normalized persisted text lines and retains the chunk/page source for every difference. */
@Service
@RequiredArgsConstructor
public class DocumentComparisonService {

    private static final int MAX_SNIPPETS = 20;
    private static final int MAX_TEXT_LENGTH = 500;
    private static final int CHUNK_PAGE_SIZE = 100;
    private static final long MAX_DOCUMENT_CHARS = 2_000_000;
    private static final int MAX_DOCUMENT_LINES = 50_000;
    private static final int MAX_UNIQUE_UNITS = 20_000;
    private static final Pattern LINE_BREAK = Pattern.compile("\\R");

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public DocumentComparisonDTO compare(Long documentIdA, Long documentIdB) {
        return compareForUser(documentIdA, documentIdB,
                SecurityUtils.requireCurrentUserId(), SecurityUtils.isCurrentUserAdmin());
    }

    /** Compares documents for a server-authenticated caller such as an Agent ToolContext. */
    @Transactional(readOnly = true)
    public DocumentComparisonDTO compareForUser(Long documentIdA, Long documentIdB, Long userId, boolean admin) {
        if (documentIdA == null || documentIdB == null || documentIdA <= 0 || documentIdB <= 0) {
            throw new IllegalArgumentException("文書 ID は正の数で指定してください。");
        }
        if (documentIdA.equals(documentIdB)) {
            throw new IllegalArgumentException("異なる文書 ID を指定してください。");
        }
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("有効なユーザー ID が必要です。");
        }

        Document documentA = (admin
                ? documentRepository.findById(documentIdA)
                : documentRepository.findByIdAndUserId(documentIdA, userId))
                .orElseThrow(DocumentNotFoundException::new);
        Document documentB = (admin
                ? documentRepository.findById(documentIdB)
                : documentRepository.findByIdAndUserId(documentIdB, userId))
                .orElseThrow(DocumentNotFoundException::new);

        // Chunk reads intentionally happen only after both requested documents are authorized.
        Map<String, DocumentComparisonDTO.Snippet> unitsA = readUnits(documentA);
        Map<String, DocumentComparisonDTO.Snippet> unitsB = readUnits(documentB);

        Set<String> shared = new LinkedHashSet<>(unitsA.keySet());
        shared.retainAll(unitsB.keySet());
        List<DocumentComparisonDTO.Snippet> onlyA = difference(unitsA, unitsB);
        List<DocumentComparisonDTO.Snippet> onlyB = difference(unitsB, unitsA);

        return new DocumentComparisonDTO(
                new DocumentComparisonDTO.DocumentSummary(documentA.getId(), documentA.getTitle()),
                new DocumentComparisonDTO.DocumentSummary(documentB.getId(), documentB.getTitle()),
                shared.size(), onlyA.size(), onlyB.size(),
                limit(onlyA), limit(onlyB),
                onlyA.size() > MAX_SNIPPETS, onlyB.size() > MAX_SNIPPETS);
    }

    private Map<String, DocumentComparisonDTO.Snippet> readUnits(Document document) {
        Map<String, DocumentComparisonDTO.Snippet> units = new LinkedHashMap<>();
        long totalCharacters = 0;
        int totalLines = 0;
        int page = 0;
        Slice<DocumentChunk> chunkPage;
        do {
            chunkPage = documentChunkRepository.findByDocumentIdOrderByChunkIndexAscIdAsc(
                    document.getId(), PageRequest.of(page++, CHUNK_PAGE_SIZE));
            for (DocumentChunk chunk : chunkPage.getContent()) {
                String content = chunk.getContent();
                if (content == null || content.isEmpty()) continue;
                totalCharacters += content.length();
                if (totalCharacters > MAX_DOCUMENT_CHARS) {
                    throw comparisonLimitExceeded("文字数");
                }
                Integer sourcePage = pageNumber(chunk.getMetadata());
                Matcher breaks = LINE_BREAK.matcher(content);
                int lineStart = 0;
                while (breaks.find()) {
                    totalLines = addLine(document, units, chunk, sourcePage, content, lineStart, breaks.start(), totalLines);
                    lineStart = breaks.end();
                }
                totalLines = addLine(document, units, chunk, sourcePage, content, lineStart, content.length(), totalLines);
            }
        } while (chunkPage.hasNext());
        return units;
    }

    private int addLine(Document document, Map<String, DocumentComparisonDTO.Snippet> units,
                        DocumentChunk chunk, Integer pageNumber, String content,
                        int start, int end, int totalLines) {
        totalLines++;
        if (totalLines > MAX_DOCUMENT_LINES) {
            throw comparisonLimitExceeded("行数");
        }
        String displayText = content.substring(start, end).trim();
        if (displayText.isEmpty()) return totalLines;
        String normalized = normalize(displayText);
        if (!units.containsKey(normalized)) {
            if (units.size() >= MAX_UNIQUE_UNITS) {
                throw comparisonLimitExceeded("異なるテキスト単位数");
            }
            units.put(normalized, new DocumentComparisonDTO.Snippet(
                    document.getId(), chunk.getChunkIndex(), pageNumber, bound(displayText)));
        }
        return totalLines;
    }

    private IllegalArgumentException comparisonLimitExceeded(String limitName) {
        return new IllegalArgumentException("文書の比較対象が上限を超えています (" + limitName + ")。");
    }

    private String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private Integer pageNumber(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> values = objectMapper.readValue(metadata, new TypeReference<>() {});
            Integer page = parsePage(values.get(PagePdfDocumentReader.METADATA_START_PAGE_NUMBER));
            if (page != null) return page;
            page = parsePage(values.get("pageNumber"));
            if (page != null) return page;
            return parsePage(values.get("page_number"));
        } catch (Exception ignored) {
            // Metadata may be a non-JSON string; source text remains useful without page evidence.
            return null;
        }
    }

    private Integer parsePage(Object value) {
        if (value instanceof Number number) {
            return number.intValue() > 0 ? number.intValue() : null;
        }
        if (value instanceof String string) {
            try {
                int parsed = Integer.parseInt(string.trim());
                return parsed > 0 ? parsed : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private List<DocumentComparisonDTO.Snippet> difference(
            Map<String, DocumentComparisonDTO.Snippet> left,
            Map<String, DocumentComparisonDTO.Snippet> right) {
        List<DocumentComparisonDTO.Snippet> result = new ArrayList<>();
        left.forEach((normalized, snippet) -> {
            if (!right.containsKey(normalized)) result.add(snippet);
        });
        return result;
    }

    private List<DocumentComparisonDTO.Snippet> limit(List<DocumentComparisonDTO.Snippet> snippets) {
        return snippets.stream().limit(MAX_SNIPPETS).toList();
    }

    private String bound(String text) {
        if (text.length() <= MAX_TEXT_LENGTH) return text;
        String prefix = text.substring(0, MAX_TEXT_LENGTH - 1);
        if (!prefix.isEmpty() && Character.isHighSurrogate(prefix.charAt(prefix.length() - 1))) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix + "…";
    }
}
