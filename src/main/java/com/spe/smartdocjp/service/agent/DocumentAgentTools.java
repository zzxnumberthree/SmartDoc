package com.spe.smartdocjp.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spe.smartdocjp.model.DTO.SearchDTOs.SearchResultResponse;
import com.spe.smartdocjp.model.entity.Document;
import com.spe.smartdocjp.repository.DocumentRepository;
import com.spe.smartdocjp.service.DocumentService;
import com.spe.smartdocjp.service.RagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Provides read-only tools (functions) for the AI Agent to autonomously interact with the SmartDoc-JP document repository.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentAgentTools {

    private final RagService ragService;
    private final DocumentService documentService;
    private final DocumentRepository documentRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Performs semantic similarity search against all stored documents and RAG vector chunks.
     * @param query The natural language search query string.
     * @return Formatted string containing relevant document fragments and citations.
     */
    @Tool(description = "根据自然语言关键词或问题进行 RAG 向量检索，返回与查询最相关的前几个文档切片内容和出处。用于回答关于文档的具体内容细节。")
    public String searchDocuments(@ToolParam(description = "检索用的自然语言关键词或完整问题") String query) {
        log.info("Agent Tool executed: searchDocuments(query = '{}')", query);
        try {
            List<SearchResultResponse> results = ragService.search(query, 5, 0.40);
            if (results.isEmpty()) {
                return "未检索到与 '" + query + "' 相关的任何文档片段。请尝试缩短关键词或换个表达方式。";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("找到以下相关文档切片：\n");
            for (int i = 0; i < results.size(); i++) {
                SearchResultResponse res = results.get(i);
                sb.append(String.format("-%d. [来源: %s, Chunk #%s (相似度: %.2f)]\n%s\n\n",
                        i + 1,
                        res.documentTitle() != null ? res.documentTitle() : "未知文档",
                        res.chunkIndex() != null ? res.chunkIndex() : "?",
                        res.score() != null ? res.score() : 0.0,
                        res.content()));
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("Error executing searchDocuments tool", e);
            return "检索工具执行发生异常：" + e.getMessage();
        }
    }

    /**
     * Retrieves comprehensive details and the AI summary of a specific document by its unique ID.
     * @param documentId The ID of the document.
     * @return Formatted string with full metadata and AI summary.
     */
    @Tool(description = "根据具体文档 ID (documentId) 查询特定文档的完整元数据信息和 AI 智能摘要。")
    public String getDocumentById(@ToolParam(description = "待查询的特定文档 ID 数字") Long documentId) {
        log.info("Agent Tool executed: getDocumentById(documentId = {})", documentId);
        try {
            if (documentId == null) {
                return "错误：提供的 documentId 为空。";
            }
            Optional<Document> docOpt = documentRepository.findById(documentId);
            if (docOpt.isEmpty() || Boolean.TRUE.equals(docOpt.get().getIsDeleted())) {
                return "未找到 ID 为 " + documentId + " 的已存文档。";
            }
            Document doc = docOpt.get();
            Map<String, Object> info = new HashMap<>();
            info.put("id", doc.getId());
            info.put("title", doc.getTitle());
            info.put("originalFilename", doc.getOriginalFilename());
            info.put("status", doc.getStatus() != null ? doc.getStatus().name() : "N/A");
            info.put("embeddingStatus", doc.getEmbeddingStatus() != null ? doc.getEmbeddingStatus().name() : "N/A");
            info.put("chunkCount", doc.getChunkCount());
            info.put("summary", doc.getSummary());
            info.put("createdAt", doc.getCreatedAt() != null ? doc.getCreatedAt().toString() : "N/A");
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(info);
        } catch (Exception e) {
            log.error("Error executing getDocumentById tool", e);
            return "获取文档详情发生异常：" + e.getMessage();
        }
    }

    /**
     * Lists the most recently uploaded documents in the system.
     * @param limit The maximum number of documents to return (default 5).
     * @return Formatted list of recent documents.
     */
    @Tool(description = "获取系统最近上传或创建的最新几份文档的基本信息列表（包含文档 ID、名称、状态与摘要概览）。")
    public String listRecentDocuments(@ToolParam(description = "返回最新文档数量上限，如 5 或 10") Integer limit) {
        int actualLimit = (limit == null || limit <= 0 || limit > 20) ? 5 : limit;
        log.info("Agent Tool executed: listRecentDocuments(limit = {})", actualLimit);
        try {
            List<Document> all = documentService.getAllDocumentsForView();
            List<Document> recent = all.stream()
                    .filter(d -> !Boolean.TRUE.equals(d.getIsDeleted()))
                    .limit(actualLimit)
                    .toList();
            if (recent.isEmpty()) {
                return "目前系统内没有任何上传的文档记录。";
            }
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("系统最近上传的 %d 份文档：\n", recent.size()));
            for (Document doc : recent) {
                sb.append(String.format("- [ID: %d] 标题: %s | 状态: %s | 分块数: %d\n  摘要概览: %s\n\n",
                        doc.getId(),
                        doc.getTitle() != null ? doc.getTitle() : doc.getOriginalFilename(),
                        doc.getStatus() != null ? doc.getStatus().name() : "unknown",
                        doc.getChunkCount() != null ? doc.getChunkCount() : 0,
                        doc.getSummary() != null ? (doc.getSummary().length() > 80 ? doc.getSummary().substring(0, 80) + "..." : doc.getSummary()) : "暂无摘要"));
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("Error executing listRecentDocuments tool", e);
            return "获取最近文档列表发生异常：" + e.getMessage();
        }
    }

    /**
     * Returns general statistical information about all documents stored in the system.
     * @return Formatted statistics string.
     */
    @Tool(description = "返回系统的整体文档库统计信息（如文档总数量、各种处理状态汇总、RAG 分块总计数量等）。")
    public String getDocumentStats() {
        log.info("Agent Tool executed: getDocumentStats()");
        try {
            List<Document> all = documentRepository.findAll().stream()
                    .filter(d -> !Boolean.TRUE.equals(d.getIsDeleted()))
                    .toList();
            int totalDocs = all.size();
            long completed = all.stream().filter(d -> Document.DocStatus.completed == d.getStatus()).count();
            long processing = all.stream().filter(d -> Document.DocStatus.processing == d.getStatus() || Document.DocStatus.uploaded == d.getStatus()).count();
            long failed = all.stream().filter(d -> Document.DocStatus.failed == d.getStatus()).count();
            int totalChunks = all.stream().mapToInt(d -> d.getChunkCount() != null ? d.getChunkCount() : 0).sum();

            return String.format("""
                    系统全局文档统计概况：
                    - 有效文档总数：%d
                    - 处理已完成数量：%d
                    - 正在处理/解析数量：%d
                    - 处理异常数量：%d
                    - RAG 向量分块总数：%d
                    """, totalDocs, completed, processing, failed, totalChunks);
        } catch (Exception e) {
            log.error("Error executing getDocumentStats tool", e);
            return "统计查询发生异常：" + e.getMessage();
        }
    }

    /**
     * Compares two documents by their IDs to highlight their differences or content overlaps based on summaries and metadata.
     * @param docId1 The ID of the first document.
     * @param docId2 The ID of the second document.
     * @return Formatted comparison string.
     */
    @Tool(description = "根据两个指定的文档 ID (docId1, docId2) 对比两份文档的内容差异、摘要要点和元数据。")
    public String compareDocuments(@ToolParam(description = "第一份待对比文档的 ID") Long docId1,
                                   @ToolParam(description = "第二份待对比文档的 ID") Long docId2) {
        log.info("Agent Tool executed: compareDocuments(docId1 = {}, docId2 = {})", docId1, docId2);
        try {
            if (docId1 == null || docId2 == null) {
                return "错误：必须同时提供两个文档的 ID 才能进行对比。";
            }
            Optional<Document> opt1 = documentRepository.findById(docId1);
            Optional<Document> opt2 = documentRepository.findById(docId2);

            if (opt1.isEmpty() || Boolean.TRUE.equals(opt1.get().getIsDeleted())) {
                return "未找到 ID 为 " + docId1 + " 的第一份文档。";
            }
            if (opt2.isEmpty() || Boolean.TRUE.equals(opt2.get().getIsDeleted())) {
                return "未找到 ID 为 " + docId2 + " 的第二份文档。";
            }

            Document d1 = opt1.get();
            Document d2 = opt2.get();

            return String.format("""
                    两份文档信息对比表：
                    【文档 A - ID: %d】
                    - 标题：%s
                    - 文件名：%s
                    - 分块数量：%d
                    - AI 摘要要点：%s
                    
                    -----------------------------------
                    【文档 B - ID: %d】
                    - 标题：%s
                    - 文件名：%s
                    - 分块数量：%d
                    - AI 摘要要点：%s
                    """,
                    d1.getId(), d1.getTitle() != null ? d1.getTitle() : "N/A", d1.getOriginalFilename(), d1.getChunkCount() != null ? d1.getChunkCount() : 0, d1.getSummary() != null ? d1.getSummary() : "暂无",
                    d2.getId(), d2.getTitle() != null ? d2.getTitle() : "N/A", d2.getOriginalFilename(), d2.getChunkCount() != null ? d2.getChunkCount() : 0, d2.getSummary() != null ? d2.getSummary() : "暂无"
            );
        } catch (Exception e) {
            log.error("Error executing compareDocuments tool", e);
            return "文档对比执行发生异常：" + e.getMessage();
        }
    }
}
