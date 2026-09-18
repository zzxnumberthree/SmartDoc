package com.spe.smartdocjp.support;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Observable controls for the deterministic AI boundary used by integration tests.
 */
public final class DeterministicAiProbe {

    public static final String PROVIDER_FAILURE_SENTINEL =
            "AIza-SENTINEL jdbc:mysql://db:3306/smartdoc C:\\private\\uploads\\secret.pdf";

    private volatile CountDownLatch summaryStarted = new CountDownLatch(1);
    private volatile CountDownLatch summaryRelease = new CountDownLatch(0);
    private final AtomicBoolean failPdfSummary = new AtomicBoolean(false);
    private final AtomicInteger pdfSummaryCalls = new AtomicInteger();
    private volatile String summaryThreadName;

    public void reset() {
        summaryStarted = new CountDownLatch(1);
        summaryRelease = new CountDownLatch(0);
        failPdfSummary.set(false);
        pdfSummaryCalls.set(0);
        summaryThreadName = null;
    }

    public void blockPdfSummary() {
        summaryStarted = new CountDownLatch(1);
        summaryRelease = new CountDownLatch(1);
    }

    public void failPdfSummary() {
        failPdfSummary.set(true);
    }

    public void onPdfSummaryCall() {
        pdfSummaryCalls.incrementAndGet();
        summaryThreadName = Thread.currentThread().getName();
        summaryStarted.countDown();

        if (failPdfSummary.get()) {
            throw new IllegalStateException(PROVIDER_FAILURE_SENTINEL);
        }

        try {
            if (!summaryRelease.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("deterministic summary release timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("deterministic summary interrupted", e);
        }
    }

    public boolean awaitSummaryStarted(Duration timeout) throws InterruptedException {
        return summaryStarted.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void releasePdfSummary() {
        summaryRelease.countDown();
    }

    public int pdfSummaryCalls() {
        return pdfSummaryCalls.get();
    }

    public String summaryThreadName() {
        return summaryThreadName;
    }
}
