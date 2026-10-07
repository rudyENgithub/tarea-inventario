package com.store.inventory.testsupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import javax.sql.DataSource;

/** Verifica los invariantes contables del stock contra el esquema PostgreSQL real. */
public final class InvariantChecker {

    private InvariantChecker() {
    }

    public static void assertConsistent(DataSource dataSource, Instant now) {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT p.sku, p.on_hand,
                           p.on_hand - COALESCE((
                               SELECT SUM(r.quantity) FROM reservations r
                               WHERE r.sku = p.sku AND r.status = 'ACTIVE' AND r.expires_at > ?
                           ), 0) AS available
                    FROM products p
                    """)) {
                statement.setObject(1, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        String sku = rs.getString("sku");
                        int onHand = rs.getInt("on_hand");
                        int available = rs.getInt("available");
                        if (onHand < 0 || available < 0) {
                            throw new AssertionError("Invariant broken for " + sku + ": on_hand=" + onHand
                                    + " available=" + available);
                        }
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT count(*) FROM reservations WHERE quantity <= 0");
                    ResultSet rs = statement.executeQuery()) {
                rs.next();
                if (rs.getInt(1) != 0) {
                    throw new AssertionError("Reservations with non-positive quantity exist");
                }
            }
        } catch (AssertionError e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError("Could not check invariants", e);
        }
    }

    public static void assertNoInProgress(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT count(*) FROM alert_outbox WHERE status = 'IN_PROGRESS'");
                ResultSet rs = statement.executeQuery()) {
            rs.next();
            if (rs.getInt(1) != 0) {
                throw new AssertionError("alert_outbox still has IN_PROGRESS rows");
            }
        } catch (AssertionError e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError("Could not check outbox", e);
        }
    }
}
