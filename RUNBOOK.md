# Runbook local

Requisitos: JDK 25, Maven 3.9, Docker 29 / Compose v5 (solo para ITs y el stack).

## Comandos

```bash
mvn test                  # unitarias (*Test), sin Docker
mvn verify                # + integración (*IT, Testcontainers; requiere Docker)
docker compose up --build # app + postgres + mailpit
docker compose up -d postgres mailpit   # infra; luego mvn spring-boot:run
pwsh scripts/smoke-test.ps1
# bash scripts/smoke-test.sh
```

PowerShell: `mvn test` y `mvn verify` iguales. Smoke: `pwsh scripts/smoke-test.ps1`.

## HTTP

> Colección completa de `curl` para todos los casos de uso: [`CURL-CASOS-DE-USO.md`](CURL-CASOS-DE-USO.md). Compose publica la app en el rango `8080-8090`; si 8080 está ocupado, usa `docker compose port app 8080` para ver el puerto real.

```bash
curl -X PUT http://localhost:8080/api/v1/products/SKU-1 -H "Content-Type: application/json" -d "{\"category\":\"STANDARD\"}"
curl -X POST http://localhost:8080/api/v1/products/SKU-1/stock -H "Content-Type: application/json" -d "{\"quantity\":10}"
curl http://localhost:8080/api/v1/products/SKU-1/availability
curl -X POST http://localhost:8080/api/v1/reservations -H "Content-Type: application/json" -d "{\"orderId\":\"O1\",\"sku\":\"SKU-1\",\"quantity\":5}"
curl -X POST http://localhost:8080/api/v1/reservations/O1/confirm
```

```powershell
Invoke-RestMethod -Method Put -Uri http://localhost:8080/api/v1/products/SKU-1 -ContentType application/json -Body '{"category":"STANDARD"}'
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/products/SKU-1/stock -ContentType application/json -Body '{"quantity":10}'
Invoke-RestMethod http://localhost:8080/api/v1/products/SKU-1/availability
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/reservations -ContentType application/json -Body '{"orderId":"O1","sku":"SKU-1","quantity":5}'
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/reservations/O1/confirm
```

Correos: Mailpit en http://localhost:8025 (`GET /api/v1/messages`).

## Variables

| Variable | Uso |
| --- | --- |
| `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` | Postgres |
| `SPRING_MAIL_HOST/PORT` | SMTP (Mailpit 1025) |
| `INVENTORY_ALERTS_EMAIL_ENABLED/FROM/TO_0` | Canal email |
| `INVENTORY_LOW_STOCK_THRESHOLD` | Umbral (default 5) |
| `inventory.alerts.relay.poll-interval` | Polling del relay (5s) |
| `inventory.database.lock-timeout` | `lock_timeout` SQL (5s) |

## SQL útil

```sql
SELECT status, count(*) FROM alert_outbox GROUP BY status;
SELECT * FROM reservations WHERE status = 'ACTIVE';
UPDATE alert_outbox SET status = 'PENDING', next_attempt_at = now(), lease_until = NULL WHERE status = 'FAILED';
```

## Troubleshooting

- Puerto 8080 ocupado: cambiar `server.port` o el mapeo de Compose (`8080-8090:8080`).
- Docker no disponible: `mvn test` sigue en verde; los `*IT` se omiten (`disabledWithoutDocker`).
- 503 `TEMPORARILY_UNAVAILABLE`: `lock_timeout` al contender por un SKU; reintentar (`Retry-After: 1`).
- App no arranca con `threshold=-1`: validación fail-fast de `InventoryProperties`.
- Alertas que no llegan: revisar `SELECT status, count(*) FROM alert_outbox GROUP BY status;`. `PENDING` con `next_attempt_at` futuro = reintento con backoff; `FAILED` = agotó `max-attempts` (ver `last_error`).

---

# Guía de uso detallada

## Cómo funciona (modelo mental)

1. **Registrar** un producto con su categoría (`PUT /products/{sku}`). Es obligatorio antes de agregar stock.
2. **Agregar stock** físico (`POST /products/{sku}/stock`). Es acumulativo.
3. **Reservar** unidades para un pedido (`POST /reservations`). Las unidades reservadas dejan de estar disponibles de inmediato.
4. **Confirmar** cuando el pago se aprueba (`POST /reservations/{orderId}/confirm`). Las unidades quedan vendidas y no vuelven al stock.
5. Si el cliente **no paga a tiempo**, la reserva expira sola (según la categoría) y las unidades vuelven a estar disponibles. No hay job de limpieza: la expiración se calcula al consultar o al operar (expiración perezosa).

Fórmula: `disponible = stock - sum(reservas ACTIVE no expiradas)`. Las reservas confirmadas ya descontaron el stock físico.

### Reglas por categoría

| Categoría | Tiempo para pagar | Límite por pedido |
| --- | --- | --- |
| `STANDARD` | 15 min | sin límite |
| `PRE_ORDER` | 24 h | sin límite |
| `FLASH_SALE` | 5 min | 2 unidades |

### Idempotencia

`orderId` es la llave de idempotencia. Reenviar el mismo `POST /reservations` (misma orden, SKU y cantidad), por ejemplo porque la app móvil reintentó, devuelve `200` con la **misma** reserva y no descuenta stock dos veces. Un `orderId` ya ligado a otro SKU responde `400`. Los ids de pedido son únicos globalmente.

## Endpoints y respuestas

Base: `http://localhost:8080/api/v1`. Los errores usan `application/problem+json` (RFC 9457) con el campo estable `errorCode`.

| Operación | Éxito | Errores posibles (`errorCode`) |
| --- | --- | --- |
| `PUT /products/{sku}` body `{"category":"STANDARD"}` | `204` | `400 INVALID_REQUEST` (categoría desconocida, o SKU ya registrado con otra categoría) |
| `POST /products/{sku}/stock` body `{"quantity":10}` | `200 {"sku","available"}` | `400 INVALID_REQUEST` (cantidad <= 0 o producto no registrado) |
| `GET /products/{sku}/availability` | `200 {"sku","available"}` (0 si no existe) | — |
| `POST /reservations` body `{"orderId","sku","quantity"}` | `200 {"orderId","sku","quantity","expiresAt"}` | `400 INVALID_REQUEST`, `422 ORDER_LIMIT_EXCEEDED`, `409 INSUFFICIENT_STOCK` |
| `POST /reservations/{orderId}/confirm` | `204` | `409 NO_ACTIVE_RESERVATION` (no existe, ya expiró) |

Errores transversales: `503 TEMPORARILY_UNAVAILABLE` (con `Retry-After: 1`, contención de lock por SKU), `404`, `405`, `415`, `500 INTERNAL_ERROR`. Límites de entrada: `sku` y `orderId` no vacíos y de máximo 64 caracteres.

Confirmar una orden ya confirmada es idempotente (`204`). Confirmar una reserva expirada falla con `409`.

## Ejemplo completo de punta a punta

```bash
# 1. Registrar y cargar stock (umbral de alerta = 5)
curl -X PUT  http://localhost:8080/api/v1/products/SKU-9 -H "Content-Type: application/json" -d "{\"category\":\"FLASH_SALE\"}"
curl -X POST http://localhost:8080/api/v1/products/SKU-9/stock -H "Content-Type: application/json" -d "{\"quantity\":10}"

# 2. Reservar 2 (máximo de FLASH_SALE); quedan 8 disponibles
curl -X POST http://localhost:8080/api/v1/reservations -H "Content-Type: application/json" -d "{\"orderId\":\"A1\",\"sku\":\"SKU-9\",\"quantity\":2}"

# 3. Pedir 3 en FLASH_SALE -> 422 ORDER_LIMIT_EXCEEDED
curl -i -X POST http://localhost:8080/api/v1/reservations -H "Content-Type: application/json" -d "{\"orderId\":\"A2\",\"sku\":\"SKU-9\",\"quantity\":3}"

# 4. Bajar a <= 5 disponibles dispara UNA alerta (Mailpit en http://localhost:8025)
curl -X POST http://localhost:8080/api/v1/reservations -H "Content-Type: application/json" -d "{\"orderId\":\"A3\",\"sku\":\"SKU-9\",\"quantity\":2}"
curl -X POST http://localhost:8080/api/v1/reservations -H "Content-Type: application/json" -d "{\"orderId\":\"A4\",\"sku\":\"SKU-9\",\"quantity\":2}"

# 5. Confirmar el pago de A1
curl -X POST http://localhost:8080/api/v1/reservations/A1/confirm
```

## Alertas de stock bajo

- Se emite una alerta cuando, tras reservar, `disponible <= INVENTORY_LOW_STOCK_THRESHOLD` (default 5).
- Es **activada por flanco** (latch): no se repite mientras el producto no se reabastezca. Solo un `addStock` que deje `disponible > umbral` rearma la alerta; la expiración de reservas **no** la rearma.
- La alerta se guarda en la tabla `alert_outbox` en la **misma transacción** que la reserva (no se pierde si la app cae). Un relay en segundo plano la entrega a todos los canales con reintentos (entrega *al menos una vez*, así que un canal puede recibir duplicados).
- Ciclo de vida: `PENDING -> IN_PROGRESS -> DELIVERED | PENDING (reintento) | FAILED`.
- Backoff: `min(base-delay * 2^(intento-1), max-delay)`; tras `max-attempts` queda `FAILED`. Para reintentarlas a mano usa el `UPDATE` de la sección *SQL útil*.
- Canales por defecto: log (siempre) y email (si `INVENTORY_ALERTS_EMAIL_ENABLED=true`).

## Configuración completa

| Propiedad (`application.yaml`) | Default | Descripción |
| --- | --- | --- |
| `inventory.low-stock.threshold` | `5` | Umbral de la alerta (>= 0) |
| `inventory.alerts.relay.poll-interval` | `5s` | Cada cuánto el relay revisa el outbox aunque no lo despierten |
| `inventory.alerts.dispatch.batch-size` | `50` | Alertas reclamadas por pasada |
| `inventory.alerts.dispatch.lease` | `2m` | Tiempo que una instancia "posee" una alerta; si cae, otra la retoma al vencer |
| `inventory.alerts.dispatch.max-attempts` | `5` | Intentos antes de marcarla `FAILED` |
| `inventory.alerts.dispatch.base-delay` / `max-delay` | `30s` / `15m` | Backoff exponencial (`max-delay >= base-delay`) |
| `inventory.alerts.email.enabled/from/to/subject-prefix` | `false` | Canal de email (con `enabled=true`, `from` y `to` son obligatorios) |
| `inventory.database.lock-timeout` | `5s` | Espera máxima por el lock de un SKU antes de responder `503` |

Cualquier propiedad se puede sobrescribir con variable de entorno (`inventory.low-stock.threshold` -> `INVENTORY_LOW_STOCK_THRESHOLD`; las listas usan índice: `INVENTORY_ALERTS_EMAIL_TO_0`, `_TO_1`, ...). Valores inválidos hacen fallar el arranque (fail-fast).

## Ejecutar pruebas específicas

```bash
mvn test                                   # unitarias, arquitectura (ArchUnit) y trazabilidad
mvn -q test -Dgroups=concurrency           # solo pruebas de estrés en memoria
mvn -q verify -Dit.test=PostgresConcurrencyIT   # dos instancias contra un mismo PostgreSQL
mvn -q verify -Dit.test=JdbcInventoryRepositoryIT
```

Las colecciones Postman (`postman/*.json`, entorno `inventario-local`) cubren salud, registro, stock, reservas, reglas por categoría, confirmación, expiración, alerta, validación, concurrencia y multi-instancia.

## Salud y operación

- `GET /actuator/health` (liveness/readiness en `/actuator/health/liveness` y `/readiness`).
- El apagado es *graceful*: el relay termina la entrega en curso antes de cerrar (`timeout-per-shutdown-phase: 20s`).
- Varias instancias contra la misma base son seguras: la exclusión por SKU es un `SELECT ... FOR UPDATE` y el outbox se reclama con `FOR UPDATE SKIP LOCKED`. Con `docker compose up --scale app=2` verás el mapeo de puertos con `docker compose port --index 2 app 8080`.
- El esquema lo gestiona Flyway. **Nunca edites** una migración ya aplicada (`V1__...`): todo cambio va en un `V2__*.sql` nuevo.

---

# Guía de extensión

> Esta sección solo describe los pasos; no incluye implementación. La arquitectura es hexagonal (reglas verificadas por `HexagonalArchitectureTest`): `domain` y `application` no pueden depender de Spring, JDBC ni de adaptadores; los adaptadores no se conocen entre sí; nadie depende de `bootstrap`.

## 1. Agregar un canal de notificación (ejemplo: push con Firebase Cloud Messaging)

El fan-out ya existe: `CompositeStockAlertListener` recibe `List<AlertChannel>` y recoge **todos** los beans `AlertChannel`. No se toca el dominio, el servicio ni el composite.

1. **Dependencia**: agregar el SDK de Firebase Admin (`com.google.firebase:firebase-admin`) al `pom.xml`. Usar solo en el adaptador, nunca en `domain`/`application` (ArchUnit lo bloquea).
2. **Propiedades**: crear un record `PushProperties` en `adapter.out.notification` con `@ConfigurationProperties("inventory.alerts.push")`, siguiendo el patrón de `EmailProperties` (campos `enabled`, `topic` o lista de tokens, ruta/credenciales). Validar **solo cuando `enabled=true`** para que despliegues sin push no necesiten configuración.
3. **Adaptador**: crear `FirebasePushAlertChannel implements AlertChannel` en `adapter.out.notification`:
   - `name()` devuelve un identificador estable (`"push"`), usado en logs y en `AlertDeliveryException`.
   - `onLowStock(sku, availableUnits)` arma el mensaje (título/cuerpo con SKU y unidades) y lo envía con `FirebaseMessaging`.
   - Definir timeouts del cliente HTTP; el canal no debe bloquear indefinidamente el hilo del relay.
4. **Decidir la semántica de fallos** (importante):
   - Si el push es **crítico**, deja que la excepción se propague: el despachador reintenta con backoff. Ojo: el reintento reenvía a **todos** los canales (también email), por lo que pueden llegar duplicados; el contenido de la alerta es idempotente.
   - Si es **mejor esfuerzo**, envuélvelo en `SafeStockAlertListener` (como hace `loggingAlertChannel` en `InventoryConfiguration`): el fallo se registra y no provoca reintentos.
5. **Wiring**: en `bootstrap/NotificationConfiguration` agregar un `@Bean AlertChannel` con `@ConditionalOnProperty(prefix = "inventory.alerts.push", name = "enabled", havingValue = "true")`, igual que el canal de email, y añadir `@EnableConfigurationProperties(PushProperties.class)`.
6. **Credenciales**: inicializar `FirebaseApp` con credenciales de servicio vía variable (`GOOGLE_APPLICATION_CREDENTIALS` o un secreto montado). Nunca versionar el JSON. Agregar las variables a `compose.yaml` y a la tabla de *Variables* de este runbook.
7. **Configuración**: declarar `inventory.alerts.push.enabled: false` (y demás claves) en `application.yaml`.
8. **Pruebas**:
   - Unitaria del canal con el cliente de Firebase simulado (patrón de `EmailAlertChannelTest`): mensaje correcto y propagación de errores.
   - Prueba de propiedades (patrón de `EmailPropertiesTest`): valida solo si está habilitado.
   - Prueba del composite con un canal que falla, para confirmar que no bloquea al resto (`CompositeStockAlertListenerTest` ya cubre el comportamiento genérico).
   - Usar `RecordingAlertChannel` (en `testsupport`) para pruebas de integración sin Firebase real.
9. **Documentación**: actualizar este runbook, `DECISIONS.md` y la tabla de variables.

Verificación: `mvn test`, luego levantar con `INVENTORY_ALERTS_PUSH_ENABLED=true` y provocar una alerta (ver *Alertas de stock bajo*); confirmar la entrega en el log y el estado `DELIVERED` en `alert_outbox`.

## 2. Agregar una nueva regla de negocio (ejemplo: categoría `BLACK_FRIDAY`)

Las reglas por categoría son estrategias (`CategoryPolicy`) resueltas por `CategoryPolicyRegistry`. Una categoría = tiempo para pagar + máximo de unidades por orden.

1. **Definir la regla con negocio**: ventana de pago (p. ej. 10 min) y límite por orden (p. ej. 3). Recordar que `CategoryPolicy.UNLIMITED` representa "sin límite".
2. **Nuevo valor del enum**: agregar `BLACK_FRIDAY` a `ProductCategory` (paquete `api`). Atención: el enum pertenece al **contrato compartido** (`com.store.inventory.api`), marcado como "no modificar" en el README para esta entrega. En un entorno real hay que acordar el cambio con el equipo de la app antes de publicarlo; el servicio rechaza categorías desconocidas, así que la app solo podrá usar la nueva cuando se despliegue.
3. **Nueva política**: crear `BlackFridayCategoryPolicy implements CategoryPolicy` en `domain.policy`, siguiendo `FlashSaleCategoryPolicy` (constantes de duración y límite; sin reloj ni estado).
4. **Registrarla**: añadirla a `DefaultCategoryPolicies.all()`. Es el único lugar donde se enumeran. Si olvidas este paso, la app **falla al arrancar** (`CategoryPolicyRegistry` exige una política por cada valor del enum), por lo que no puede pasar inadvertido.
5. **Base de datos**: no requiere migración; `products.category` es `varchar(32)` sin `CHECK`. Los nombres deben caber en 32 caracteres.
6. **REST**: no requiere cambios; el DTO usa el enum y el mensaje de "categoría desconocida" lista los valores automáticamente.
7. **Pruebas**:
   - `CategoryPolicyTest`: ventana de pago y límite de la nueva política.
   - `CategoryPolicyRegistryTest`: el registro resuelve la nueva categoría.
   - `DefaultInventoryServiceTest`: reservar respeta el límite (`ORDER_LIMIT_EXCEEDED`) y la reserva expira a la hora esperada (usar `MutableClock` para avanzar el tiempo).
   - Prueba de API (`InventoryApiIT`) y colección Postman `05-reglas-por-categoria`.
8. **Documentación**: agregar la fila a la tabla del `README.md` y de este runbook.

Notas:
- Cambiar la regla de una categoría **existente** (p. ej. subir el límite de `FLASH_SALE`) solo afecta a reservas **nuevas**; las ya creadas conservan su `expiresAt`.
- Si la regla es **temporal** (solo durante el Black Friday) y no una categoría nueva, evalúa lo contrario: no inventar un valor de enum por temporada, sino parametrizar la política (por configuración) con acuerdo del equipo. Es una decisión de diseño que conviene dejar registrada en `DECISIONS.md`.
- Una regla que no es "por categoría" (p. ej. un límite global por cliente) no encaja en `CategoryPolicy`: evalúa si va en `ProductStock` (invariantes del dominio) o en un nuevo puerto.

## 3. Agregar un repository con JPA (especificación de pasos)

Objetivo: ofrecer una implementación alternativa del puerto `InventoryRepository` (y, si se quiere, de `AlertOutbox`) usando Spring Data JPA/Hibernate en lugar de `JdbcClient`. Hoy existen dos adaptadores: `adapter.out.persistence.memory` y `adapter.out.persistence.jdbc`.

1. **Dependencia**: agregar `spring-boot-starter-data-jpa`. Mantener Flyway como dueño del esquema y configurar `spring.jpa.hibernate.ddl-auto=validate` (Hibernate solo valida, nunca genera DDL).
2. **Paquete nuevo**: `adapter.out.persistence.jpa` (más su `package-info.java`). Las entidades JPA viven **solo aquí**; el dominio (`ProductStock`, `StockReservation`) debe seguir libre de anotaciones JPA (ArchUnit prohíbe `jakarta..` en `domain`/`application`).
3. **Entidades y mapeo**: `ProductEntity` (tabla `products`) y `ReservationEntity` (tabla `reservations`) alineadas con `V1__inventory_schema.sql`. La categoría se guarda como texto (`@Enumerated(EnumType.STRING)`). Un mapper convierte entidad <-> dominio (`ProductStock.rehydrate(...)`, `StockReservation`, `StockChanges`).
4. **Repositorios Spring Data**: interfaces con consulta de producto con **bloqueo pesimista** (`@Lock(PESSIMISTIC_WRITE)`, equivalente a `SELECT ... FOR UPDATE`) y consultas de reservas activas / por `orderId`.
5. **Adaptador**: clase `JpaInventoryRepository implements InventoryRepository`. Debe cumplir el mismo comportamiento que el JDBC:
   - `registerIfAbsent`: idempotente y atómico; devuelve la categoría ya existente si el SKU estaba registrado.
   - `availableUnits(sku, now)`: `stock - sum(ACTIVE con expiresAt > now)`, 0 si no existe; lectura consistente.
   - `executeOnProduct(...)`: una transacción corta **READ COMMITTED**: bloquea la fila del producto, carga las reservas `ACTIVE` (más la de `orderId` si aplica), ejecuta la `StockOperation`, persiste estado + cambios de reservas (primero bajas, luego upserts) + alertas **en la misma transacción**. Si la operación lanza una excepción, se revierte todo y se propaga **sin envolver**. Si el SKU no existe, devuelve vacío sin ejecutar la operación.
   - Un `orderId` ligado a otro SKU debe lanzar `OrderIdConflictException` sin persistir nada (la PK de `reservations.order_id` y el error `23505` lo detectan).
6. **Lock timeout**: aplicar `lock_timeout` de `inventory.database.lock-timeout` (hint `jakarta.persistence.lock.timeout` o `set_config('lock_timeout', ..., true)` dentro de la transacción) y traducir el SQLSTATE `55P03` a `CannotAcquireLockException`, para que `GlobalExceptionHandler` responda `503 TEMPORARILY_UNAVAILABLE`.
7. **Tiempo**: nunca leer el reloj de la base ni de la JVM dentro del adaptador; la hora llega por parámetro (`now`, `expiresAt`). Cuidado con `@UpdateTimestamp`/`@CreationTimestamp`: `created_at/updated_at` son solo informativos. Convertir `Instant` <-> `timestamptz` con precisión de microsegundos (ver `SqlTime`).
8. **Alertas (outbox)**: el repositorio debe **insertar** las alertas de `OperationResult.alerts()` en `alert_outbox` dentro de la misma transacción. El consumo (`AlertOutbox.claimDue` con `FOR UPDATE SKIP LOCKED`, `markDelivered`, `markRetry`, `markFailed`) puede seguir con `JdbcAlertOutbox`; si se reimplementa en JPA, necesita una consulta nativa con `SKIP LOCKED` y sin transacción abierta durante la entrega.
9. **Transacciones**: `JpaTransactionManager` también gestiona `JdbcClient` sobre el mismo `DataSource`, por lo que ambos adaptadores pueden convivir en la misma transacción.
10. **Wiring**: en `bootstrap/InventoryConfiguration` declarar el bean `InventoryRepository` del adaptador JPA en lugar del JDBC, o elegir entre ambos con una propiedad (`@ConditionalOnProperty`, p. ej. `inventory.persistence=jpa|jdbc`). Dos beans `InventoryRepository` activos a la vez causan ambigüedad.
11. **Pruebas**: crear `JpaInventoryRepositoryIT` que **extienda `InventoryRepositoryContractTest`** (igual que `JdbcInventoryRepositoryIT`), con `PostgresTestDatabase` y Testcontainers. Pasar el contrato completo (registro, reservas, atomicidad, vínculo de `orderId`, concurrencia) es el criterio de aceptación. Añadir también el equivalente a `JdbcInventoryRepositorySqlIT` (locking y SQL específico) y correr `PostgresConcurrencyIT` con el nuevo adaptador.
12. **Trampas conocidas**: el contexto de persistencia de Hibernate puede devolver entidades en caché (usar transacciones cortas y no compartir `EntityManager` entre operaciones); el `flush` debe ocurrir antes de salir de la sección crítica; las excepciones de Hibernate (`PessimisticLockException`, `ConstraintViolationException`) deben traducirse a las del puerto.
13. **Documentación**: actualizar este runbook y `DECISIONS.md` (por qué JPA, cuál es el adaptador por defecto).