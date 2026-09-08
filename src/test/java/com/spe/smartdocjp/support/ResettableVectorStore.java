package com.spe.smartdocjp.support;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.List;

/**
 * Test-only vector store that keeps the production implementation semantics while
 * preventing filesystem persistence and cross-test state leakage.
 */
public final class ResettableVectorStore implements VectorStore {

    private final EmbeddingModel embeddingModel;
    private volatile SimpleVectorStore delegate;

    public ResettableVectorStore(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
        reset();
    }

    public void reset() {
        delegate = SimpleVectorStore.builder(embeddingModel).build();
    }

    @Override
    public void add(List<Document> documents) {
        delegate.add(documents);
    }

    @Override
    public void delete(List<String> idList) {
        delegate.delete(idList);
    }

    @Override
    public void delete(Filter.Expression filterExpression) {
        delegate.delete(filterExpression);
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        return delegate.similaritySearch(request);
    }
}
