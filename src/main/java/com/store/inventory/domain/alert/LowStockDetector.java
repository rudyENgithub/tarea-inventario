package com.store.inventory.domain.alert;

import com.store.inventory.domain.stock.ProductStock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Decide cuándo se debe emitir o rearmar una alerta de stock bajo. Sin estado: el latch vive en
 * {@link ProductStock#alertArmed()}.
 *
 * <p>Activado por flanco: tras una nueva reserva, si el latch está armado y {@code available <= threshold}, la alerta
 * se emite una vez y el latch se desarma. Solo una reposición que deje {@code available > threshold} lo rearma;
 * la expiración de reservas nunca lo hace.
 */
public final class LowStockDetector {

    private final LowStockThreshold threshold;

    public LowStockDetector(LowStockThreshold threshold) {
        this.threshold = Objects.requireNonNull(threshold, "threshold");
    }

    public Optional<LowStockAlert> afterNewReservation(ProductStock product, Instant now) {
        if (!product.alertArmed()) {
            return Optional.empty();
        }
        int available = product.availableAt(now);
        if (!threshold.isLow(available)) {
            return Optional.empty();
        }
        product.disarmAlert();
        return Optional.of(new LowStockAlert(product.sku(), available, now));
    }

    public void afterRestock(ProductStock product, Instant now) {
        if (!product.alertArmed() && product.availableAt(now) > threshold.units()) {
            product.armAlert();
        }
    }
}
