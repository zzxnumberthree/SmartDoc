package com.spe.smartdocjp.support;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Test-only vector store that keeps the production implementation semantics while
 * preventing filesystem persistence and cross-test state leakage.
 */
public final class ResettableVectorStore implements VectorStore {

    private final EmbeddingModel embeddingModel;
    private final AtomicBoolean failAdds = new AtomicBoolean(false);
    private volatile SimpleVectorStore delegate;

    public ResettableVectorStore(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
        reset();
    }

    public void reset() {
        failAdds.set(false);
        delegate = SimpleVectorStore.builder(embeddingModel).build();
    }

    public void failAdds() {
        failAdds.set(true);
    }

    @Override
    public void add(List<Document> documents) {
        if (failAdds.get()) {
            throw new IllegalStateException("deterministic vector-store add failure");
        }
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
