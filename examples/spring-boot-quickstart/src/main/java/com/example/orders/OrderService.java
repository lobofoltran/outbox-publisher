package com.example.orders;

import java.nio.charset.StandardCharsets;

import io.github.lobofoltran.outbox.Outbox;
import io.github.lobofoltran.outbox.OutboxEvent;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Demonstrates the canonical outbox-publisher use case: a single
 * {@code @Transactional} method that performs a business write and an
 * {@link Outbox#publish(OutboxEvent)} call. Both succeed together or roll
 * back together — the Spring transaction manager binds the same JDBC
 * connection to both writes.
 */
@Service
public class OrderService {

    private final OrderRepository repository;
    private final Outbox outbox;

    public OrderService(OrderRepository repository, Outbox outbox) {
        this.repository = repository;
        this.outbox = outbox;
    }

    @Transactional
    public void place(Order order) {
        repository.save(order);

        String json =
                "{\"id\":\"" + order.id() + "\",\"customer\":\"" + order.customerId()
                        + "\",\"amount\":\"" + order.amount() + "\"}";

        outbox.publish(
                OutboxEvent.builder()
                        .aggregateType("Order")
                        .aggregateId(order.id().toString())
                        .eventType("OrderPlaced")
                        .destination("orders.events")
                        .contentType("application/json")
                        .payload(json.getBytes(StandardCharsets.UTF_8))
                        .header("customer-id", order.customerId())
                        .build());
    }
}
