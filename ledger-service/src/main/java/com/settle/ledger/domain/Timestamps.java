package com.settle.ledger.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * PostgreSQL TIMESTAMPTZ stores microseconds, but Instant.now() can carry
 * nanoseconds (it does on Linux). Without truncating, the value returned to the
 * client right after an insert differs from what is read back later.
 */
public final class Timestamps {

    private Timestamps() {
    }

    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
