package com.store.inventory.adapter.out.notification;

import com.store.inventory.api.StockAlertListener;
import com.store.inventory.application.port.out.AlertChannel;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Distribuye una alerta a todos los canales, en orden. Un canal que falla nunca impide que se ejecuten los siguientes;
 * una vez probados todos, una única {@link AlertDeliveryException} reporta cada fallo. Los {@link Error} no se
 * capturan.
 *
 * <p>Agregar un canal (no requiere cambios en esta clase):
 * <ol>
 *   <li>Implementa {@link AlertChannel} en {@code adapter.out.notification}.</li>
 *   <li>Decláralo como {@code @Bean} en {@code bootstrap}.</li>
 *   <li>Opcionalmente habilítalo mediante una propiedad; el composite recoge todos los beans {@code AlertChannel}.</li>
 * </ol>
 */
public final class CompositeStockAlertListener implements StockAlertListener {

    private final List<AlertChannel> channels;

    public CompositeStockAlertListener(List<? extends AlertChannel> channels) {
        if (channels == null) {
            throw new IllegalArgumentException("channels must not be null");
        }
        Set<String> names = new HashSet<>();
        for (AlertChannel channel : channels) {
            if (channel == null) {
                throw new IllegalArgumentException("channels must not contain null");
            }
            if (!names.add(channel.name())) {
                throw new IllegalArgumentException("duplicate channel name: " + channel.name());
            }
        }
        this.channels = List.copyOf(channels);
    }

    @Override
    public void onLowStock(String sku, int availableUnits) {
        Map<String, Throwable> failures = new LinkedHashMap<>();
        for (AlertChannel channel : channels) {
            try {
                channel.onLowStock(sku, availableUnits);
            } catch (RuntimeException e) {
                failures.put(channel.name(), e);
            }
        }
        if (!failures.isEmpty()) {
            throw new AlertDeliveryException(failures);
        }
    }
}
