package com.store.inventory.adapter.out.persistence.jdbc;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/** Único lugar donde se convierte {@link Instant} desde y hacia {@code timestamptz} de PostgreSQL (microsegundos). */
final class SqlTime {

    private SqlTime() {
    }

    static OffsetDateTime toDb(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }

    static Instant fromDb(OffsetDateTime value) {
        return value.toInstant();
    }
}
