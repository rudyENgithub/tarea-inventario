# Decisiones

## Supuestos

| # | Tema | Decisión |
| --- | --- | --- |
| SRS 1 | Reintento de `reserve` | Idempotente por `orderId` (mismo sku/qty) |
| SRS 2 | Mismo `orderId` con otros datos | `IllegalArgumentException` (D6, D8) |
| SRS 3 | `confirm` repetido | No-op (RF-C3) |
| SRS 4 | Límite FLASH_SALE | Por pedido/`orderId` |
| SRS 5 | Umbral | `available <= 5`, configurable |
| SRS 6 | Rearme | Solo `addStock` que deja `available > umbral` |
| SRS 7 | Alerta sobre | `available`, no `onHand` |
| SRS 8 | `reserve` tras expirar | Reserva nueva |
| D1 | Alcance | Postgres, REST, email y Compose incluidos |
| D2 | Java | 25 (`release=25`); estilo compatible con 21 |
| D3 | Paquetes | Hexagonal, no `internal/` |
| D4 | Flanco de alerta | Tras reserva nueva si `armed && available <= umbral` |
| D5 | Entrega | Outbox transaccional; síncrona en memoria, asíncrona en Postgres |
| D9 | Tiempo | `expiresAt` truncado a microsegundos |
| D10 | Expirada | `!now.isBefore(expiresAt)` |
| impl | Límite "sin límite" | `Integer.MAX_VALUE` |
| impl | SKU/`orderId` | ≤ 64 caracteres |
| impl | Entrega | at-least-once (posible duplicado de aviso) |

## Decisiones de arquitectura

Ver AD-1..AD-7 en `SRS.md` §9. Respecto al SRS: la atomicidad productiva es **bloqueo de fila** (`SELECT … FOR UPDATE`), no un `UPDATE` condicional de un contador; las alertas usan **outbox** además del `Safe` decorator.

Spring Boot 4.1.1 (starters `webmvc`, `jdbc`, `flyway`, `mail`, `actuator`). Testcontainers 2 (`testcontainers-postgresql`). Relación de pool: `maximum-pool-size × instancias ≤ max_connections − margen` (20 × 2 = 40 es el punto de partida local).

## Lo que se dejó fuera

Autenticación/autorización, rate limiting, retención/archivado de `CONFIRMED`/`DELIVERED`/`FAILED`, reproceso automatizado de alertas muertas, canales SMS/push, métricas/trazas, ventanas/límites por categoría desde config, i18n de correos, reabastecimiento automático.

## Qué cambiaría antes de producción

Observabilidad (Micrometer + OpenTelemetry), job de retención/particionado, circuit breaker SMTP, DLQ con endpoint de reproceso, OAuth2/mTLS y secretos en vault, PgBouncer, HA/backups de Postgres, límites de tamaño de petición, umbral por SKU, pruebas de carga con metas de latencia, revisar si expirar debe rearmar la alerta, `Idempotency-Key` si la API se abre a terceros.

Si `Composite` reintenta email, el canal log (Safe) puede duplicar una línea: aceptado.

## Riesgos conocidos

Duplicados de alerta (at-least-once); `lock_timeout` ⇒ 503; crecimiento de reservas `CONFIRMED`; alinear versiones de Boot/Testcontainers al actualizar.

## Cómo se verificó

`docs/TRACEABILITY.md`, `mvn test`, `mvn verify`, `docker compose up --build` + `scripts/smoke-test.ps1`.
