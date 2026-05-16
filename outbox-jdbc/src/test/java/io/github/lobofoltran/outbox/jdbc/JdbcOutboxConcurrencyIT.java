package io.github.lobofoltran.outbox.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.github.lobofoltran.outbox.OutboxEvent;

import org.junit.jupiter.api.Test;

class JdbcOutboxConcurrencyIT extends AbstractPostgresIT {

    private static final int THREADS = 8;
    private static final int EVENTS_PER_THREAD = 25;

    @Test
    void parallel_writers_produce_distinct_rows_without_deadlock() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);

        try {
            for (int t = 0; t < THREADS; t++) {
                executor.submit(
                        () -> {
                            try {
                                start.await();
                                for (int i = 0; i < EVENTS_PER_THREAD; i++) {
                                    try (Connection connection = openConnection()) {
                                        connection.setAutoCommit(false);
                                        JdbcOutbox.builder()
                                                .connectionSupplier(() -> connection)
                                                .build()
                                                .publish(eventForCurrentThread(i));
                                        connection.commit();
                                    }
                                }
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(ex);
                            } catch (SQLException ex) {
                                throw new IllegalStateException(ex);
                            } finally {
                                done.countDown();
                            }
                        });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        int expected = THREADS * EVENTS_PER_THREAD;
        Set<UUID> ids = collectIds();
        assertThat(ids).hasSize(expected);
        for (UUID id : ids) {
            assertThat(id.version()).isEqualTo(7);
        }
    }

    private static OutboxEvent eventForCurrentThread(int sequence) {
        return OutboxEvent.builder()
                .aggregateType("Order")
                .aggregateId(Thread.currentThread().getName() + "-" + sequence)
                .eventType("OrderPlaced")
                .contentType("application/json")
                .payload(new byte[] {1})
                .occurredAt(Instant.now())
                .build();
    }

    private static Set<UUID> collectIds() throws Exception {
        Set<UUID> ids = new HashSet<>();
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT id FROM outbox");
                ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                ids.add(rs.getObject("id", UUID.class));
            }
        }
        return ids;
    }
}
