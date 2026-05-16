package com.example.orders;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Plain {@code JdbcTemplate} repository — no Spring Data JPA. */
@Repository
public class OrderRepository {

    private final JdbcTemplate jdbc;

    public OrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(Order order) {
        jdbc.update(
                "INSERT INTO orders (id, customer_id, amount) VALUES (?, ?, ?)",
                order.id(),
                order.customerId(),
                order.amount());
    }
}
