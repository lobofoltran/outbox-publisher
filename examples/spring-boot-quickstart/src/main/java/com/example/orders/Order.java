package com.example.orders;

import java.math.BigDecimal;
import java.util.UUID;

/** Trivial domain record for the example. */
public record Order(UUID id, String customerId, BigDecimal amount) {}
