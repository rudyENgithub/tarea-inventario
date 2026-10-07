/**
 * Agregado de stock: stock físico, reservas, idempotencia y expiración perezosa.
 * No es thread-safe por diseño; la exclusión mutua es responsabilidad del repositorio.
 */
package com.store.inventory.domain.stock;
