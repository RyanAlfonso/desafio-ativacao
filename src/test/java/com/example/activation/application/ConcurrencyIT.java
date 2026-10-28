package com.example.activation.application;

import com.example.activation.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/** Concorrência contra o PostgreSQL real: é aqui que constraint única e SKIP LOCKED provam seu valor. */
class ConcurrencyIT extends IntegrationTest {

    private static final int THREADS = 16;

    @Autowired
    private ActivationRequestService service;

    @Autowired
    private ActivationDispatcher dispatcher;

    @Test
    void simultaneousRequestsWithSameExternalIdCreateExactlyOneRecord() throws Exception {
        var command = new NewActivationRequest("EXT-RACE", "CUST-1", "INTERNET-500");

        List<CreationResult> results = runConcurrently(() -> service.create(command, "erp", "trace"));

        assertThat(results).filteredOn(CreationResult::created).hasSize(1);
        assertThat(results).extracting(result -> result.request().getId()).containsOnly(results.getFirst().request().getId());
        assertThat(repository.count()).isOne();
    }

    @Test
    void concurrentDispatchersNeverSendTheSameRequestTwice() throws Exception {
        PROVIDER.stubFor(post("/provider/v1/activate").willReturn(ok().withFixedDelay(20)));
        for (int i = 0; i < 40; i++) {
            service.create(new NewActivationRequest("EXT-" + i, "CUST-1", "INTERNET-500"), "erp", "trace");
        }

        List<Integer> processed = runConcurrently(dispatcher::dispatchDue);

        assertThat(processed.stream().mapToInt(Integer::intValue).sum()).isEqualTo(40);
        PROVIDER.verify(40, postRequestedFor(urlEqualTo("/provider/v1/activate")));
    }

    private static <T> List<T> runConcurrently(Callable<T> task) throws Exception {
        var start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(THREADS)) {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }
}
