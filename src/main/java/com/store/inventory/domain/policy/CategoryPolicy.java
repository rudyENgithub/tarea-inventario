package com.store.inventory.domain.policy;

import com.store.inventory.api.ProductCategory;
import java.time.Duration;

/**
 * Reglas de negocio asociadas a una {@link ProductCategory}. Solo datos: sin reloj, sin estado.
 * El instante de expiración lo calcula el agregado de stock usando el reloj inyectado.
 */
public interface CategoryPolicy {

    /** Marcador para "sin límite por orden". */
    int UNLIMITED = Integer.MAX_VALUE;

    ProductCategory category();

    /** Tiempo que tiene el cliente para pagar; siempre positivo. */
    Duration paymentWindow();

    /** Máximo de unidades por orden; al menos 1, {@link #UNLIMITED} cuando no hay límite. */
    int maxUnitsPerOrder();
}
