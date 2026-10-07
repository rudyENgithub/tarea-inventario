package com.store.inventory.application.port.out;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Cola durable de alertas de stock bajo pendientes de entrega. Ciclo de vida de una entrada:
 * {@code PENDING -> IN_PROGRESS -> DELIVERED | PENDING (reintento) | FAILED (terminal)}.
 *
 * <p>Escribir no forma parte de este puerto a propósito: las alertas las encola el {@link InventoryRepository} en la
 * misma sección crítica (o transacción) que la reserva que las originó. Este puerto cubre el lado consumidor.
 *
 * <p>La entrega es al menos una vez: si un consumidor muere sosteniendo una entrada {@code IN_PROGRESS}, otro puede
 * reclamarla cuando venza su lease, por lo que la misma alerta puede llegar más de una vez a un canal. El contenido
 * de la alerta (SKU + unidades disponibles) es naturalmente idempotente. El tiempo siempre se recibe como parámetro;
 * las implementaciones nunca leen un reloj.
 */
public interface AlertOutbox {

    /**
     * Reclama hasta {@code limit} entradas vencidas en orden FIFO por id. Vencida significa {@code PENDING} con
     * {@code nextAttemptAt <= now}, o {@code IN_PROGRESS} con {@code leaseUntil <= now}. Las entradas reclamadas pasan
     * a {@code IN_PROGRESS} con {@code leaseUntil = now + lease} y {@code attempts += 1}.
     *
     * <p>Dos consumidores concurrentes nunca reciben la misma entrada mientras su lease sea válido.
     *
     * @throws IllegalArgumentException si {@code limit < 1} o {@code lease} es cero o negativo
     */
    List<PendingAlert> claimDue(Instant now, int limit, Duration lease);

    /** Marca la entrada como entregada. No hace nada si ya es terminal (idempotente). */
    void markDelivered(long id, Instant now);

    /**
     * Devuelve la entrada a {@code PENDING} para reintentarla en {@code nextAttemptAt}. No hace nada si ya es terminal.
     */
    void markRetry(long id, Instant nextAttemptAt, String error);

    /** Marca la entrada como fallida de forma permanente. No hace nada si ya es terminal. */
    void markFailed(long id, String error);
}
