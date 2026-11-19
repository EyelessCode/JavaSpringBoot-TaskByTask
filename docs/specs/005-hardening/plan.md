# Plan Técnico de Implementación: 005-hardening (Endurecimiento de Producción, Contrato OpenAPI y Verificación Final del MVP)

**Fase:** 005-hardening  
**Estado:** completed  
**Fecha:** 2026-11-18  
**Autor:** SDD Planner Agent  
**Referencias:**
- `docs/specs/005-hardening/spec.md` (HRD-REQ-01 a HRD-REQ-23, EC-01 a EC-08)
- `docs/specs/000-foundation/spec.md` y `plan.md` (`SecurityConfig`, `RateLimitService`, `GlobalExceptionHandler`, `JacksonConfig`, `PasswordEncoderConfig`)
- `docs/specs/001-auth-and-users/spec.md` y `plan.md` (`AuthController`, `AuthService`, `UserController`)
- `docs/specs/002-groups/spec.md` y `plan.md` (`GroupController`)
- `docs/specs/003-tasks-and-tags/spec.md` y `plan.md` (`TaskController`, `TagController`, `UpdateTaskRequest`)
- `docs/specs/004-notifications/spec.md` y `plan.md` (`NotificationController`, `NotificationSchedulerConfig`, `VapidConfigurationState`)
- `docs/constitution.md` (Principios 1 a 6)
- `AGENTS.md` (Convenciones Pure Java 21, localización española RFC 7807, verificación)
- `src/main/resources/db/migration/V1__init_schema.sql` (Esquema inmutable de referencia)

---

## 1. Resumen Ejecutivo y Enfoque de Diseño

La fase **005-hardening** culmina el ciclo MVP del backend de **Task by Task** sin agregar funcionalidades Posteriores ni nuevas dependencias en `pom.xml`, ni modificar el esquema SQL de `V1__init_schema.sql`. Su diseño técnico se organiza en seis ejes concretos verificados contra el stack real del proyecto (Spring Boot `4.1.2`, Spring Security `7.1.2`, Jackson 3 `tools.jackson.databind`, `springdoc-openapi` `3.2.0`, HikariCP, Spring Boot Actuator y `db-scheduler` `15.0.0`):

1. **Frontera de Proxy de Confianza y Anti-Spoofing (`TrustedProxyResolver` + `TrustedForwardedHeaderFilter`):**
   Se implementa un matcher puro IPv4/IPv6 por CIDR (`TrustedProxyResolver`) con rangos de confianza configurables en `app.security.trusted-proxies` (por defecto loopback y redes privadas RFC 1918/ULA cuando opera tras proxy inverso). Un filtro servlet ordenado (`TrustedForwardedHeaderFilter`, ejecutado antes de `SessionRepositoryFilter` y Spring Security) delega en `ForwardedHeaderFilter` únicamente cuando `remoteAddr` pertenece a un proxy confiable, y envuelve la petición eliminando `X-Forwarded-*`, `Forwarded` y `X-Real-IP` cuando llega desde una IP pública no confiable. `RateLimitService.resolveClientIp` reutiliza el mismo `TrustedProxyResolver`, cerrando la suplantación directa de IP (`HRD-REQ-03`, `EC-02`) mientras preserva compatibilidad con pruebas unitarias e integración existentes (`10.0.0.99`, `127.0.0.1`).
2. **CORS, Cabeceras HTTP Defensivas, Bootstrap CSRF (`GET /api/v1/auth/csrf`) y Política Unificada de Cookies:**
   Siguiendo la aprobación de la **Opción A**, se agrega `GET /api/v1/auth/csrf` como endpoint público en `AuthController` (exponiendo `CsrfTokenResponse(token, headerName, parameterName)` y emitiendo la cookie `XSRF-TOKEN`), manteniendo la validación CSRF activa en `POST /api/v1/auth/{register,login,recover-password}` y en todas las mutaciones autenticadas. `SecurityConfig` incorpora `CorsConfigurationSource` parametrizada por `app.security.cors` (prohibiendo `*` con `allowCredentials = true`, autorizando `GET, POST, PATCH, PUT, DELETE, OPTIONS` y exponiendo `Retry-After`), cabeceras defensivas (`X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy`, `Permissions-Policy`, `Strict-Transport-Security` y `Content-Security-Policy` diferenciada por `RequestMatcher` entre rutas API y `/swagger-ui/**`), y unifica la política de cookies (`SameSite=Lax` por defecto, `Domain` opcional y manejo `Secure` sensible tanto a `app.security.cookie.secure` como a `request.isSecure()`).
3. **Actualización Transparente de Hashes (`FND-REQ-12`, `HRD-REQ-06`) y Jackson 3 Estricto (`HRD-REQ-07`):**
   - `AuthService.login` verifica `passwordEncoder.upgradeEncoding(user.getPasswordHash())` tras autenticar exitosamente y re-hashea la contraseña en la misma transacción.
   - En `JacksonConfig`, además del `ObjectMapper` Jackson 2 usado por `KeysetCursorCodec`, se registra un `JsonMapperBuilderCustomizer` para el `JsonMapper` Jackson 3 de Spring Boot 4 (`tools.jackson.databind`) deshabilitando `FAIL_ON_UNKNOWN_PROPERTIES` y aplicando `CoercionAction.Fail` sobre `LogicalType.Textual`, `LogicalType.Boolean`, `LogicalType.Integer` y `LogicalType.DateTime` para rechazar coerciones escalares incompatibles en los 6 módulos, reforzado con `@JsonIgnoreProperties(ignoreUnknown = true)` en todos los DTOs de entrada y unificando `RestAuthenticationEntryPoint` / `RestAccessDeniedHandler` sobre Jackson 3.
4. **Completitud RFC 7807 en Borde HTTP (`GlobalExceptionHandler`):**
   Se incorporan manejadores explícitos para `NoResourceFoundException` y `NoHandlerFoundException` (`404 Recurso no encontrado`), `HttpRequestMethodNotSupportedException` (`405 Método no permitido` con cabecera `Allow`), `HttpMediaTypeNotSupportedException` (`415 Tipo de medio no soportado`), `HttpMediaTypeNotAcceptableException` (`406 Formato no aceptable`) y `ServletRequestBindingException` (`400 Solicitud inválida`), manteniendo `401` en `RestAuthenticationEntryPoint` para rutas protegidas no autenticadas (`HRD-REQ-08` a `HRD-REQ-11`).
5. **Perfil de Producción, Actuator Endurecido, HikariCP y Apagado Elegante:**
   Se crea `application-prod.yml`, se limpia `application.yml` (log base `INFO`, pool HikariCP acotado `maximum-pool-size: 10`, `minimum-idle: 2`, `connection-timeout: 5000`, `idle-timeout: 300000`, `max-lifetime: 1500000`, `server.shutdown: graceful`, `spring.lifecycle.timeout-per-shutdown-phase: 25s`, `server.forward-headers-strategy: none` porque lo gobierna `TrustedForwardedHeaderFilter`), se exponen únicamente `/actuator/health`, `/actuator/health/liveness` y `/actuator/health/readiness` (`show-details: never`, `management.endpoints.access.default: none`, `management.endpoint.health.access: read-only`) y se crea `.env.example` documentado sin secretos reales.
6. **Pulido OpenAPI 3 y Verificación E2E Final del MVP:**
   `OpenApiConfig` define metadatos en español, esquemas de seguridad (`SESSION` cookie y `X-XSRF-TOKEN` header) y un `OpenApiCustomizer` que expone esquemas RFC 7807 (`ProblemDetail`, `ValidationErrorDetail`, `RateLimitProblemDetail`) y reemplaza `UpdateTaskRequest` por un esquema plano exacto (`title` no anulable, `description`/`dueDate`/`dueTime`/`tags` anulables, eliminando cualquier `PatchField*`). Todos los controladores y DTOs quedan anotados homogéneamente, con toggle por entorno (`springdoc.api-docs.enabled` y `springdoc.swagger-ui.enabled`), y una suite E2E multi-módulo sobre PostgreSQL real verifica el MVP completo de extremo a extremo.

---

## 2. Inventario de Archivos Afectados

```
JavaSpringBoot-TaskByTask/
├── .env.example                                                                 # [CREAR] Plantilla documentada de variables de entorno sin secretos
├── src/main/resources/
│   ├── application.yml                                                          # [MODIFICAR] Defaults seguros prod-ready, Hikari, Actuator, Jackson, CORS/Proxy/Cookies, graceful shutdown
│   ├── application-dev.yml                                                      # [MODIFICAR] DEBUG dev, CORS localhost, cookie.secure=false, Swagger habilitado
│   └── application-prod.yml                                                     # [CREAR] Perfil prod estricto (INFO logs, cookie.secure=true, Swagger off por defecto)
├── src/test/resources/
│   └── application-test.yml                                                     # [MODIFICAR] Ajustar defaults de test (cookie.secure=false, CORS test, Swagger on para contrato)
├── src/main/java/com/eyelesscode/task_by_task/
│   ├── shared/
│   │   ├── config/
│   │   │   ├── JacksonConfig.java                                               # [MODIFICAR] Añadir JsonMapperBuilderCustomizer (Jackson 3 estricto: anti-coerción + ignoreUnknown)
│   │   │   └── OpenApiConfig.java                                               # [CREAR] Bean OpenAPI + OpenApiCustomizer (seguridad cookie/CSRF, ProblemDetail, UpdateTaskRequest plano)
│   │   ├── error/
│   │   │   ├── GlobalExceptionHandler.java                                      # [MODIFICAR] Añadir 404/405/406/415 y ServletRequestBindingException -> 400
│   │   │   └── ValidationErrorDetail.java                                       # [MODIFICAR] Añadir @Schema descriptivo en español
│   │   ├── pagination/
│   │   │   └── KeysetPage.java                                                  # [MODIFICAR] Añadir @Schema descriptivo en español
│   │   ├── ratelimit/
│   │   │   └── RateLimitService.java                                            # [MODIFICAR] Validar TrustedProxyResolver antes de leer X-Forwarded-For / X-Real-IP
│   │   └── security/
│   │       ├── SecurityProperties.java                                          # [CREAR] @ConfigurationProperties(prefix = "app.security") con CORS, cookie y trusted-proxies
│   │       ├── TrustedProxyResolver.java                                        # [CREAR] Utilidad pura CIDR IPv4/IPv6 para proxies de confianza
│   │       ├── TrustedForwardedHeaderFilter.java                                # [CREAR] Filtro ordenado anti-spoofing X-Forwarded-* / X-Real-IP
│   │       ├── SecurityConfig.java                                              # [MODIFICAR] CORS, CSP/Headers, CookieCsrfTokenRepository policy, /auth/csrf, /actuator/health/**
│   │       ├── RestAuthenticationEntryPoint.java                                # [MODIFICAR] Usar Jackson 3 ObjectMapper de Spring Boot 4 (con fallback seguro)
│   │       └── RestAccessDeniedHandler.java                                     # [MODIFICAR] Usar Jackson 3 ObjectMapper de Spring Boot 4 (con fallback seguro)
│   ├── auth/
│   │   ├── AuthController.java                                                  # [MODIFICAR] Añadir GET /api/v1/auth/csrf y anotaciones OpenAPI (@Tag, @Operation, @ApiResponses)
│   │   ├── AuthService.java                                                     # [MODIFICAR] Añadir re-hashing transparente (passwordEncoder.upgradeEncoding) en login
│   │   └── dto/
│   │       ├── CsrfTokenResponse.java                                           # [CREAR] record(String token, String headerName, String parameterName) + @Schema
│   │       ├── LoginRequest.java                                                # [MODIFICAR] Añadir @JsonIgnoreProperties(ignoreUnknown = true) y @Schema
│   │       ├── LogoutRequest.java                                               # [MODIFICAR] Añadir @JsonIgnoreProperties(ignoreUnknown = true) y @Schema
│   │       ├── MessageResponse.java                                             # [MODIFICAR] Añadir @Schema
│   │       ├── RecoverPasswordRequest.java                                      # [MODIFICAR] Añadir @JsonIgnoreProperties(ignoreUnknown = true) y @Schema
│   │       ├── RegisterRequest.java                                             # [MODIFICAR] Añadir @JsonIgnoreProperties(ignoreUnknown = true) y @Schema
│   │       └── RegisterResponse.java                                            # [MODIFICAR] Añadir @Schema
│   ├── user/
│   │   ├── UserController.java                                                  # [MODIFICAR] Añadir anotaciones OpenAPI completas
│   │   └── dto/
│   │       ├── ChangePasswordRequest.java                                       # [MODIFICAR] Añadir @JsonIgnoreProperties(ignoreUnknown = true) y @Schema
│   │       ├── DeleteAccountRequest.java                                        # [MODIFICAR] Añadir @JsonIgnoreProperties(ignoreUnknown = true) y @Schema
│   │       ├── RecoveryCodesResponse.java                                       # [MODIFICAR] Añadir @Schema
│   │       ├── RegenerateRecoveryCodesRequest.java                              # [MODIFICAR] Añadir @JsonIgnoreProperties(ignoreUnknown = true) y @Schema
│   │       ├── UpdateProfileRequest.java                                        # [MODIFICAR] Añadir @JsonIgnoreProperties(ignoreUnknown = true) y @Schema
│   │       └── UserResponse.java                                                # [MODIFICAR] Añadir @Schema
│   ├── group/
│   │   ├── GroupController.java                                                 # [MODIFICAR] Añadir anotaciones OpenAPI completas
│   │   └── dto/
│   │       ├── CreateGroupRequest.java                                          # [MODIFICAR] Añadir @JsonIgnoreProperties(ignoreUnknown = true) y @Schema
│   │       ├── GroupResponse.java                                               # [MODIFICAR] Añadir @Schema
│   │       └── UpdateGroupRequest.java                                          # [MODIFICAR] Añadir @JsonIgnoreProperties(ignoreUnknown = true) y @Schema
│   ├── tag/
│   │   ├── TagController.java                                                   # [MODIFICAR] Añadir anotaciones OpenAPI completas
│   │   └── dto/
│   │       ├── CreateTagRequest.java                                            # [MODIFICAR] Añadir @JsonIgnoreProperties(ignoreUnknown = true) y @Schema
│   │       └── TagResponse.java                                                 # [MODIFICAR] Añadir @Schema
│   ├── task/
│   │   ├── TaskController.java                                                  # [MODIFICAR] Pulir @Operation/@Parameter/@ApiResponses/@RequestBody(schema plano PATCH)
│   │   └── dto/
│   │       ├── CreateTaskRequest.java                                           # [MODIFICAR] Añadir @Schema descriptivo
│   │       ├── TaskResponse.java                                                # [MODIFICAR] Añadir @Schema descriptivo
│   │       ├── TaskTargetGroupRequest.java                                      # [MODIFICAR] Añadir @Schema descriptivo
│   │       └── UpdateTaskCompletionRequest.java                                 # [MODIFICAR] Añadir @Schema descriptivo
│   └── notification/
│       ├── NotificationController.java                                          # [MODIFICAR] Pulir @Operation/@Parameter/@ApiResponses
│       └── dto/
│           ├── MarkAllReadResponse.java                                         # [MODIFICAR] Añadir @Schema descriptivo
│           ├── NotificationResponse.java                                        # [MODIFICAR] Añadir @Schema descriptivo
│           ├── PushSubscriptionResponse.java                                    # [MODIFICAR] Añadir @Schema descriptivo
│           ├── RegisterPushSubscriptionRequest.java                             # [MODIFICAR] Añadir @Schema descriptivo
│           ├── UnreadCountResponse.java                                         # [MODIFICAR] Añadir @Schema descriptivo
│           └── VapidPublicKeyResponse.java                                      # [MODIFICAR] Añadir @Schema descriptivo
└── src/test/java/com/eyelesscode/task_by_task/
    ├── auth/
    │   └── AuthLoginLogoutIntegrationTest.java                                  # [MODIFICAR] Verificar GET /csrf y re-hashing transparente (upgradeEncoding)
    ├── shared/
    │   ├── config/
    │   │   └── JacksonStrictCoercionTest.java                                   # [CREAR] Unit/MockMvc: rechazo de coerción escalar e ignorado de desconocidos
    │   ├── error/
    │   │   └── GlobalExceptionHandlerTest.java                                  # [MODIFICAR] Verificar 404, 405+Allow, 415, 406, ServletRequestBindingException
    │   ├── ratelimit/
    │   │   └── RateLimitServiceTest.java                                        # [MODIFICAR] Verificar confianza en proxy privado vs rechazo spoofing IP pública
    │   └── security/
    │       ├── TrustedProxyResolverTest.java                                    # [CREAR] Unit: matching puro IPv4/IPv6 CIDR y bordes inválidos
    │       ├── TrustedForwardedHeaderFilterTest.java                            # [CREAR] Unit: preservación proxy confiable vs stripping IP pública no confiable
    │       ├── HardeningSecurityIntegrationTest.java                            # [CREAR] IT: CORS, headers, CSP, CSRF, proxy HTTPS, Actuator liveness/readiness/denegación, borde 401/404/405/415/406
    │       ├── ProdProfileIntegrationTest.java                                  # [CREAR] IT: arranque perfil prod (pool Hikari, Swagger deshabilitado 404, Actuator seguro)
    │       └── OpenApiContractIntegrationTest.java                              # [CREAR] IT: /v3/api-docs completo, UpdateTaskRequest plano, cero endpoints Posteriores
    └── mvp/
        └── MvpEndToEndJourneyIntegrationTest.java                               # [CREAR] IT E2E: flujo completo multi-módulo sobre Testcontainers PostgreSQL + HttpServer + db-scheduler
```

---

## 3. Decisiones de Diseño y Alternativas Descartadas

| Decisión de Diseño | Opción Elegida | Alternativa Descartada | Justificación Técnica |
| :--- | :--- | :--- | :--- |
| **Bootstrap CSRF (`HRD-REQ-05`, Opción A)** | Endpoint público `GET /api/v1/auth/csrf` + `CsrfTokenArgumentResolver` manteniendo CSRF en `/auth/{register,login,recover-password}` | Eximir a `/auth/{register,login,recover-password}` de CSRF o depender de `GET /actuator/health` | Protege también el registro, login y recuperación frente a Login-CSRF, provee contrato explícito en OpenAPI para SPAs (`token`, `headerName`, `parameterName`) y mantiene intactos los tests `Auth*IntegrationTest` que ya envían CSRF. |
| **Política unificada de cookies (`FND-REQ-10`, Opción A)** | `SecurityProperties.Cookie` compartido por `SESSION` (`DefaultCookieSerializer`) y `XSRF-TOKEN` (`CookieCsrfTokenRepository`), con `SameSite=Lax` por defecto y `Domain`/política configurable (`Lax`/`Strict`/`None`) | Fijar `SameSite=Lax` y `Secure=false` rígido en pruebas | Cuando `cookie.secure=true` (prod), fuerza `Secure=true`; cuando `cookie.secure=false` (dev/test), delega en `request.isSecure()`, permitiendo HTTP local y a la vez `Secure=true` automático tras `X-Forwarded-Proto: https` confiable (`EC-02`). Valida al arranque que `SameSite=None` exija `cookie.secure=true`. |
| **Proxy de confianza (`HRD-REQ-03`, `EC-02`)** | `TrustedProxyResolver` + `TrustedForwardedHeaderFilter` (con `server.forward-headers-strategy: none`) + misma verificación en `RateLimitService` | Usar `server.forward-headers-strategy: framework` sin restricción de IP | `ForwardedHeaderFilter` puro confía en `X-Forwarded-*` de cualquier cliente directo, mientras Tomcat `NATIVE` no corre en `MockMvc`. Con `TrustedForwardedHeaderFilter` el límite de confianza aplica idéntico en Tomcat real y `MockMvc`, eliminando el spoofing de `X-Forwarded-For` y `X-Forwarded-Proto`. |
| **Jackson 3 estricto (`HRD-REQ-07`)** | `JsonMapperBuilderCustomizer` en `JacksonConfig` aplicando `CoercionAction.Fail` en `Textual`, `Boolean`, `Integer`, `DateTime` + `@JsonIgnoreProperties(ignoreUnknown = true)` | Reemplazar todos los DTOs con deserializadores manuales | Spring Boot 4 usa `tools.jackson.databind.json.JsonMapper` (Jackson 3) para MVC mientras el `ObjectMapper` Jackson 2 previo no afectaba endpoints HTTP. Configurar `JsonMapperBuilderCustomizer` unifica el rechazo de coerción escalar en los 6 módulos sin duplicar código. |
| **Esquema plano de `PATCH /tasks/{id}` en OpenAPI (`HRD-REQ-19`)** | `@Schema(implementation = UpdateTaskPatchSchema.class)` en `TaskController.updateTask` + limpieza en `OpenApiCustomizer` | Dejar que `springdoc` introspecte `PatchField<T>` | `PatchField<T>` es un detalle interno de Java para distinguir omitido vs `null`. Exponer `UpdateTaskPatchSchema` en el contrato OpenAPI refleja el JSON real en cable (`title` no anulable; `description`, `dueDate`, `dueTime`, `tags` anulables) sin alterar la deserialización runtime. |
| **Exposición Swagger y Actuator en prod (`HRD-REQ-12`, `13`, `20`)** | Mantener `/v3/api-docs/**` y `/swagger-ui/**` en `permitAll()` pero deshabilitar `springdoc.*` en `application-prod.yml`; exponer solo `health` (`liveness`, `readiness`) y bloquear `/actuator/**` | Bloquear Swagger en `SecurityFilterChain` devolviendo `401` | Si Swagger se deniega con `401`, el cliente percibe una ruta protegida; al dejar la ruta en `permitAll()` y deshabilitar los beans de `springdoc` en `prod`, Spring MVC lanza `NoResourceFoundException` -> `404 Recurso no encontrado` RFC 7807 en español (`EC-06`). En Actuator, `access.default: none` + `denyAll()` bloquea cualquier endpoint interno. |
| **Cabecera `Content-Security-Policy` (`HRD-REQ-04`)** | `DelegatingRequestMatcherHeaderWriter` diferenciando rutas `/swagger-ui/**` (CSP acotada para UI cuando esté habilitado) y el resto (`default-src 'none'; frame-ancestors 'none'`) | Relajar CSP globalmente en toda la API | Protege estrictamente todas las rutas JSON de la API sin romper la interfaz Swagger UI en desarrollo ni pruebas de contrato (`ApplicationIntegrationTest`). |

---

## 4. Diseño Detallado de Componentes

### 4.1 Propiedades de Seguridad y Frontera de Proxy de Confianza (`shared.security`, `shared.ratelimit`)

#### 4.1.1 `SecurityProperties` (`@ConfigurationProperties(prefix = "app.security")`)
Record de configuración inmutable validado en arranque:
- `Cors(List<String> allowedOrigins, long maxAgeSeconds)`:
  - `allowedOrigins` por defecto vacío en base/prod (inyectado vía `${CORS_ALLOWED_ORIGINS:}`), y `http://localhost:3000,http://localhost:5173` en `application-dev.yml` / `http://localhost:3000` en `application-test.yml`.
  - Validación en constructor compacto: si existe algún origen con `"*"` (p. ej. `*`), lanzar `IllegalStateException` al arranque porque `allowCredentials = true` prohíbe comodines (`HRD-REQ-02`).
- `Cookie(boolean secure, String sameSite, String domain)`:
  - Normaliza `sameSite` (`Lax` por defecto; solo `Lax`, `Strict` o `None` permitidos). Si `"None".equalsIgnoreCase(sameSite) && !secure`, lanzar `IllegalStateException` al arranque.
  - `domain`: `trim()`, normalizado a `null` si queda vacío.
- `List<String> trustedProxies`:
  - Lista de CIDRs IPv4/IPv6 confiables (por defecto `127.0.0.1/32, ::1/128, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, fc00::/7`).

#### 4.1.2 `TrustedProxyResolver` (Funciones Puras de CIDR) y `TrustedForwardedHeaderFilter`
- **`TrustedProxyResolver`:**
  - Precompila en construcción una lista inmutable de rangos `IpRange(byte[] networkAddress, int prefixLength)` usando `InetAddress.getByName(...)` (únicamente literales IPv4/IPv6/CIDR, sin búsquedas DNS en runtime).
  - Función pura `boolean isTrustedProxy(String remoteAddr)`: valida nulidad/blanco, resuelve dirección numérica (`InetAddress.getByAddress` o parser literal) y evalúa coincidencia de bits por máscara de prefijo (maneja coincidencia IPv4 e IPv4-mapped IPv6 `::ffff:x.x.x.x`).
  - Función pura `String extractClientIp(String remoteAddr, String xForwardedForHeader, String xRealIpHeader)`:
    - Si `!isTrustedProxy(remoteAddr)`, retorna `remoteAddr` (o `"UNKNOWN"` si vacío) ignorando por completo `X-Forwarded-For` y `X-Real-IP`.
    - Si es proxy confiable: toma la primera IP válida no vacía de `X-Forwarded-For` (o `X-Real-IP` como respaldo, o `remoteAddr`).
- **`TrustedForwardedHeaderFilter` (`OncePerRequestFilter` registrado como bean con `@Order(Ordered.HIGHEST_PRECEDENCE)`):**
  - En cada petición HTTP:
    - Si `trustedProxyResolver.isTrustedProxy(request.getRemoteAddr())`: delega en un `ForwardedHeaderFilter` interno para aplicar `X-Forwarded-Proto`, `X-Forwarded-Host`, `X-Forwarded-Port` y `X-Forwarded-For` (actualizando `request.isSecure()`, esquema y host en Tomcat y `MockMvc`), preservando el comportamiento de `RateLimitService` gracias al orden o resolviendo la IP cliente en la cadena. *(Nota de implementación clave: como `ForwardedHeaderFilter` reescribe `request.getRemoteAddr()` con la IP del cliente extraída de `X-Forwarded-For` y elimina las cabeceras `X-Forwarded-*`, `RateLimitService.resolveClientIp(request)` obtiene directamente la IP real ya depurada por el filtro cuando este se ejecutó, o evalúa `trustedProxyResolver.extractClientIp(...)` cuando se invoca de forma unitaria sin filtro como en `RateLimitServiceTest`!)*.
    - Si `!trustedProxyResolver.isTrustedProxy(request.getRemoteAddr())`: envuelve el `HttpServletRequest` con un `HttpServletRequestWrapper` que oculta (`return null` / `Collections.emptyEnumeration()`) las cabeceras `Forwarded`, `X-Forwarded-Proto`, `X-Forwarded-Host`, `X-Forwarded-Port`, `X-Forwarded-For` y `X-Real-IP`, garantizando que ni Tomcat/Spring, ni `SecurityConfig`, ni `RateLimitService` acepten datos reenviados falsificados.

#### 4.1.3 `SecurityConfig` (`CORS`, `Headers`, `CSRF` y `CookieSerializer`)
- **Autorización de rutas:**
  - `GET /api/v1/auth/csrf`, `POST /api/v1/auth/register`, `POST /api/v1/auth/login`, `POST /api/v1/auth/recover-password` -> `.permitAll()`.
  - `GET /actuator/health`, `GET /actuator/health/liveness`, `GET /actuator/health/readiness` -> `.permitAll()`.
  - `/actuator/**` -> `.denyAll()`.
  - `/v3/api-docs/**`, `/swagger-ui/**`, `/swagger-ui.html` -> `.permitAll()` (cuando `springdoc` está deshabilitado en `prod`, Spring MVC devuelve `404` RFC 7807 en lugar de `401` o `500`, cumpliendo `HRD-REQ-08`/`HRD-REQ-20`/`EC-06`).
  - `anyRequest()` -> `.authenticated()`.
- **CORS (`http.cors(Customizer.withDefaults())` + `@Bean CorsConfigurationSource`):**
  - Construye `CorsConfiguration` con `allowedOrigins` filtrando blancos (si está vacío, no registra orígenes permitidos -> rechaza toda petición cross-origin), `allowCredentials = true`, `allowedMethods = List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS")`, `allowedHeaders = List.of("Content-Type", "Accept", "X-XSRF-TOKEN", "X-Requested-With")`, `exposedHeaders = List.of("Retry-After")`, `maxAge = securityProperties.cors().maxAgeSeconds()` (p. ej. `3600L`).
- **Cabeceras de seguridad (`http.headers(...)`):**
  - `contentTypeOptions(Customizer.withDefaults())` (`X-Content-Type-Options: nosniff`).
  - `frameOptions(frame -> frame.deny())` (`X-Frame-Options: DENY`).
  - `referrerPolicy(ref -> ref.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))`.
  - `permissionsPolicyHeader(pp -> pp.policy("camera=(), microphone=(), geolocation=(), payment=()"))`.
  - `httpStrictTransportSecurity(hsts -> hsts.maxAgeInSeconds(31536000).includeSubDomains(true))` (Spring Security lo emite automáticamente cuando `request.isSecure()` es `true`).
  - `addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(...))` para `Content-Security-Policy`:
    - Si `springdoc.swagger-ui.enabled` es `true` y la ruta coincide con `/swagger-ui/**`, `/swagger-ui.html` o `/v3/api-docs/**`: `default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'`.
    - Para todo el resto (y siempre en `prod` cuando Swagger está deshabilitado): `default-src 'none'; frame-ancestors 'none'`.
- **Política unificada de cookies (`SESSION` y `XSRF-TOKEN`):**
  - `CookieCsrfTokenRepository`: `withHttpOnlyFalse()`, `setCookieCustomizer(builder -> { builder.sameSite(cookie.sameSite()); if (cookie.secure()) builder.secure(true); if (cookie.domain() != null) builder.domain(cookie.domain()); })` (cuando `cookie.secure()` es `false`, `CookieCsrfTokenRepository` mantiene `request.isSecure()` por defecto).
  - `DefaultCookieSerializer`: `cookieName = "SESSION"`, `useHttpOnlyCookie = true`, `sameSite = cookie.sameSite()`, `domainName = cookie.domain()` (si no es nulo), y solo llama `setUseSecureCookie(true)` cuando `cookie.secure()` es `true` (cuando es `false`, deja `useSecureCookie` como `null` para que `DefaultCookieSerializer.isSecureCookie(request)` evalúe `request.isSecure()` automáticamente).

---

### 4.2 Bootstrap CSRF (`GET /api/v1/auth/csrf`) y Re-hashing en Login (`auth/`)

- **`CsrfTokenResponse`:** `public record CsrfTokenResponse(String token, String headerName, String parameterName)` con anotaciones `@Schema`.
- **`AuthController.csrf(CsrfToken csrfToken)`:** `GET /api/v1/auth/csrf` retorna `200 OK` con `CsrfTokenResponse`.
- **`AuthService.login(...)` (`HRD-REQ-06`):**
  Tras verificar `passwordEncoder.matches(request.password(), user.getPasswordHash())`:
  - Evalúa `if (passwordEncoder.upgradeEncoding(user.getPasswordHash()))` -> re-codifica `user.setPasswordHash(passwordEncoder.encode(request.password()))` y marca actualización.
  - Si cambió el hash o la zona horaria, actualiza `user.setUpdatedAt(dateTimeProvider.now())` y persiste `userRepository.save(user)` dentro de la misma transacción `@Transactional`.

---

### 4.3 Jackson 3 Estricto (`shared.config.JacksonConfig` + DTOs) y Errores de Borde RFC 7807 (`shared.error.GlobalExceptionHandler`)

- **`JacksonConfig`:**
  - Mantiene el bean `com.fasterxml.jackson.databind.ObjectMapper` (Jackson 2) para compatibilidad de `KeysetCursorCodec`.
  - Añade `@Bean JsonMapperBuilderCustomizer strictJsonMapperCustomizer()`:
    - `builder.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)`
    - Configura `CoercionAction.Fail` en `LogicalType.Textual` (rechaza `Integer`, `Float`, `Boolean`), `LogicalType.Boolean` (rechaza `Integer`, `Float`, `String`, `EmptyString`), `LogicalType.Integer` (rechaza `String`, `Float`, `Boolean`, `EmptyString`) y `LogicalType.DateTime` (rechaza `Integer`, `Float`, `Boolean`, `EmptyString`).
  - Agrega `@JsonIgnoreProperties(ignoreUnknown = true)` en los DTOs de `auth/dto`, `user/dto`, `group/dto` y `tag/dto` que aún no lo tenían explícito.
  - Migra `RestAuthenticationEntryPoint` y `RestAccessDeniedHandler` a `tools.jackson.databind.ObjectMapper` (conservando constructor sin argumentos respaldado por `JsonMapper.builder().build()`).
- **`GlobalExceptionHandler`:**
  Mantiene intactos los handlers actuales (`MethodArgumentNotValidException`, `ConstraintViolationException`, `FieldValidationException`, `BadRequestException`, `HttpMessageNotReadableException`, `MethodArgumentTypeMismatchException`, `MissingServletRequestParameterException`, `ResourceNotFoundException`, `ResourceConflictException`, `BadCredentialsException`, `AccessDeniedException`, `RateLimitException`, `Exception`) y añade:
  - `@ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})` -> `404 Not Found` (`title: "Recurso no encontrado"`, `detail: "El recurso solicitado no existe"`).
  - `@ExceptionHandler(HttpRequestMethodNotSupportedException.class)` -> `405 Method Not Allowed` (`title: "Método no permitido"`, `detail: "El método HTTP no está soportado para este recurso"`, preservando cabecera `Allow` cuando existan métodos soportados).
  - `@ExceptionHandler(HttpMediaTypeNotSupportedException.class)` -> `415 Unsupported Media Type` (`title: "Tipo de medio no soportado"`, `detail: "El tipo de contenido de la solicitud no es compatible; utilice application/json"`).
  - `@ExceptionHandler(HttpMediaTypeNotAcceptableException.class)` -> `406 Not Acceptable` (`title: "Formato no aceptable"`, `detail: "El formato de respuesta solicitado no está soportado"`, retornando `Content-Type: application/problem+json` explícito para evitar fallo de negociación secundario).
  - `@ExceptionHandler(ServletRequestBindingException.class)` -> `400 Bad Request` (`title: "Solicitud inválida"`, `detail: "Faltan cabeceras, parámetros o variables obligatorias en la solicitud"`).

---

### 4.4 Configuración Productiva, Actuator, HikariCP y `.env.example`

- **`application.yml` (base productiva segura):**
  - `server.forward-headers-strategy: none` (delegado a `TrustedForwardedHeaderFilter`).
  - `server.shutdown: graceful` y `spring.lifecycle.timeout-per-shutdown-phase: 25s`.
  - `server.error.include-message: never`, `include-binding-errors: never`, `include-stacktrace: never`, `include-exception: false`.
  - `server.compression.enabled: true` (mime-types JSON y OpenAPI).
  - `spring.datasource.hikari`:
    - `maximum-pool-size: ${DB_POOL_MAX_SIZE:10}`
    - `minimum-idle: ${DB_POOL_MIN_IDLE:2}`
    - `connection-timeout: ${DB_POOL_CONNECTION_TIMEOUT_MS:5000}`
    - `idle-timeout: ${DB_POOL_IDLE_TIMEOUT_MS:300000}`
    - `max-lifetime: ${DB_POOL_MAX_LIFETIME_MS:1500000}`
    - `pool-name: task-by-task-hikari`
  - `management`:
    - `endpoints.access.default: none`
    - `endpoints.web.exposure.include: health`
    - `endpoint.health.access: read-only`, `show-details: never`, `probes.enabled: true`, `group.readiness.include: readinessState,db`
    - `health.livenessstate.enabled: true`, `health.readinessstate.enabled: true`
  - `springdoc.api-docs.enabled: ${OPENAPI_ENABLED:true}` y `springdoc.swagger-ui.enabled: ${SWAGGER_UI_ENABLED:true}` (habilitado en desarrollo/default; en `application-prod.yml` ambos pasan a `false` por defecto salvo override explícito `OPENAPI_ENABLED=true` / `SWAGGER_UI_ENABLED=true`).
  - `logging.level`: `root: INFO`, `com.eyelesscode.task_by_task: INFO`, `org.springframework.security: INFO` (dev mantiene `DEBUG` en `application-dev.yml`).
- **`application-prod.yml`:**
  - Fuerza defaults productivos seguros: `app.security.cookie.secure: ${COOKIE_SECURE:true}`, `app.security.cookie.same-site: ${COOKIE_SAME_SITE:Lax}`, `app.security.cookie.domain: ${COOKIE_DOMAIN:}`, `app.security.cors.allowed-origins: ${CORS_ALLOWED_ORIGINS:}`, `springdoc.api-docs.enabled: ${OPENAPI_ENABLED:false}`, `springdoc.swagger-ui.enabled: ${SWAGGER_UI_ENABLED:false}`, `logging.level.com.eyelesscode.task_by_task: INFO`.
- **`.env.example`:**
  - Documenta sin valores reales secretos: `SPRING_PROFILES_ACTIVE`, `DB_URL` (nota explícita sobre Session Mode puerto `5432` para Flyway), `DB_USERNAME`, `DB_PASSWORD`, `DB_POOL_MAX_SIZE`, `CORS_ALLOWED_ORIGINS`, `COOKIE_SECURE`, `COOKIE_SAME_SITE`, `COOKIE_DOMAIN`, `TRUSTED_PROXIES`, `OPENAPI_ENABLED`, `SWAGGER_UI_ENABLED`, `WEB_PUSH_ENABLED`, `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY`, `VAPID_SUBJECT`.

---

### 4.5 Pulido Integral OpenAPI 3 (`OpenApiConfig`, Controladores y DTOs)

- **`OpenApiConfig` (`@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true", matchIfMissing = true)`):**
  - Bean `OpenAPI`:
    - `Info`: título `"Task by Task API"`, versión `"1.0.0-mvp"`, descripción en español cubriendo alcance MVP, formato de fechas/horas (`dd/MM/yyyy`, `24H`/`12H`, `ZoneId`), regla de avisos `09:00 AM` / vencimiento `23:59:59.999` y modelo de sesión `SESSION` + CSRF `X-XSRF-TOKEN`.
    - `Components.SecuritySchemes`:
      - `"sessionCookie"`: `Type.APIKEY`, `In.COOKIE`, `name = "SESSION"`.
      - `"csrfToken"`: `Type.APIKEY`, `In.HEADER`, `name = "X-XSRF-TOKEN"`.
  - Bean `OpenApiCustomizer`:
    - Registra esquemas compartidos en `components.schemas`: `ProblemDetailResponse` (RFC 7807 con `type`, `title`, `status`, `detail`, `instance`, `invalidParams`, `retryAfterSeconds`).
    - Asegura que no permanezcan esquemas internos de `PatchField*` en `components.schemas`, y expone el esquema plano `UpdateTaskPatchRequest` (`title` no anulable `minLength: 2, maxLength: 100`; `description` `nullable: true, maxLength: 750`; `dueDate` `format: date, nullable: true`; `dueTime` `format: time, nullable: true`; `tags` `array[string], nullable: true`).
    - Inyecta respuestas estándar transversales de borde (`406`, `500` y `429` en auth pública) cuando aplique sin sobrescribir respuestas específicas ya declaradas.
- **Controladores y DTOs:**
  - Añadir `@Tag`, `@Operation`, `@Parameter`, `@ApiResponses` en español y `@SecurityRequirements` apropiados en `AuthController`, `UserController`, `GroupController`, `TagController`, `TaskController` y `NotificationController`.
  - Añadir `@Schema` con descripciones, ejemplos y restricciones del Apéndice B en todos los DTOs de entrada/salida, `KeysetPage<T>` y `ValidationErrorDetail`.

---

## 5. Estrategia de Pruebas Automatizadas

1. **Pruebas Unitarias Puras (`shared/`):**
   - `TrustedProxyResolverTest`: CIDR IPv4, IPv6, IPv4-mapped, IPs fuera de rango, entradas nulas/malformadas y extracción de IP.
   - `TrustedForwardedHeaderFilterTest`: verificación con proxy confiable (`10.0.0.10`, `127.0.0.1`) preservando `isSecure() == true` y `remoteAddr` reescrito vs cliente directo público (`203.0.113.50`) donde se eliminan cabeceras reenviadas falsificadas.
   - `RateLimitServiceTest`: ampliar casos con IP pública spoofeando `X-Forwarded-For` / `X-Real-IP` (ignorados) frente a proxy privado confiable.
   - `GlobalExceptionHandlerTest`: ampliar casos de `NoResourceFoundException` (`404`), `HttpRequestMethodNotSupportedException` (`405` + `Allow`), `HttpMediaTypeNotSupportedException` (`415`), `HttpMediaTypeNotAcceptableException` (`406`) y `ServletRequestBindingException` (`400`).
   - `JacksonStrictCoercionTest`: verificación directa del `JsonMapperBuilderCustomizer` rechazando `"name": 123`, `"completed": "true"`, `"targetGroupId": "1"`, `"dueDate": 12345` e ignorando propiedades desconocidas.
2. **Pruebas de Integración de Endurecimiento, Perfil `prod`, Actuator y OpenAPI (`Testcontainers`):**
   - `HardeningSecurityIntegrationTest`: verifica CORS (preflight permitido, origen no permitido sin `Access-Control-Allow-Origin`, `Retry-After` expuesto), cabeceras defensivas (`nosniff`, `DENY`, `Referrer-Policy`, `Permissions-Policy`, `CSP` API vs Swagger UI, `HSTS` bajo proxy HTTPS confiable), `GET /api/v1/auth/csrf` + `SameSite=Lax` + `Secure` tras proxy confiable vs proxy falso público, actualización transparente de hash en `POST /api/v1/auth/login` (`{noop}`/`{bcrypt}$2a$04$` -> `{bcrypt}`), rechazo uniforme de coerción escalar e ignorado de desconocidos en todos los módulos, Actuator `/health`, `/health/liveness`, `/health/readiness` (`200`, sin `details`) y denegación de `/actuator/env` / `/actuator/beans`, y borde HTTP autenticado (`404`, `405`, `415`, `406`) vs anónimo (`401`).
   - `ProdProfileIntegrationTest`: levanta `@SpringBootTest` con `@ActiveProfiles("prod")` + `Testcontainers` verificando HikariCP (`max=10`), `springdoc` deshabilitado (`GET /v3/api-docs` y `GET /swagger-ui/index.html` -> `404` RFC 7807 en español), `/actuator/health/readiness` -> `200`, y VAPID deshabilitado sin degradar readiness.
   - `OpenApiContractIntegrationTest`: valida `GET /v3/api-docs` como JSON OpenAPI 3 válido con metadatos en español, `securitySchemes` (`sessionCookie`, `csrfToken`), cobertura del 100% de rutas MVP, esquema plano de `PATCH /api/v1/tasks/{taskId}` (`title` no anulable, sin `PatchField*`), esquemas `ProblemDetail`/`ValidationErrorDetail` y ausencia total de rutas/términos de Fase Posterior.
3. **Suite E2E Integral del MVP (`MvpEndToEndJourneyIntegrationTest`):**
   - Prueba de ciclo de vida completo con `@SpringBootTest`, `Testcontainers` (`postgres:alpine`), `com.sun.net.httpserver.HttpServer` embebido para Web Push, claves VAPID P-256 válidas y `db-scheduler` activo, validando la cadena completa descrita en `HRD-REQ-21` y `EC-08` desde `GET /api/v1/auth/csrf` hasta `DELETE /api/v1/users/me` con cero filas huérfanas en las 12 tablas de `V1__init_schema.sql`.

---

## 6. Matriz de Cobertura de Requisitos (`HRD-REQ` -> Componentes Técnicos)

| Requisito EARS | Componente Técnico Implementador | Archivos Principales |
| :--- | :--- | :--- |
| **HRD-REQ-01**, **02** | `SecurityConfig` (`CorsConfigurationSource`) + `SecurityProperties.Cors` | `SecurityConfig.java`, `SecurityProperties.java`, `application*.yml` |
| **HRD-REQ-03** | `TrustedProxyResolver` + `TrustedForwardedHeaderFilter` + `RateLimitService` | `TrustedProxyResolver.java`, `TrustedForwardedHeaderFilter.java`, `RateLimitService.java` |
| **HRD-REQ-04** | Cabeceras defensivas + `DelegatingRequestMatcherHeaderWriter` CSP API/Swagger | `SecurityConfig.java` |
| **HRD-REQ-05** | `GET /api/v1/auth/csrf` + `CsrfTokenResponse` + política unificada `SESSION`/`XSRF-TOKEN` | `AuthController.java`, `CsrfTokenResponse.java`, `SecurityConfig.java` |
| **HRD-REQ-06** | `AuthService.login` (`passwordEncoder.upgradeEncoding`) | `AuthService.java` |
| **HRD-REQ-07** | `JacksonConfig` (`JsonMapperBuilderCustomizer` anti-coerción) + `@JsonIgnoreProperties` + Jackson 3 entrypoints | `JacksonConfig.java`, DTOs `auth`/`user`/`group`/`tag`, `RestAuthenticationEntryPoint.java`, `RestAccessDeniedHandler.java` |
| **HRD-REQ-08** a **11** | `GlobalExceptionHandler` (`404`/`405`/`415`/`406`/`400` binding/`500`) | `GlobalExceptionHandler.java` |
| **HRD-REQ-12**, **13** | Configuración Actuator + reglas `/actuator/**` en `SecurityConfig` | `application.yml`, `application-prod.yml`, `SecurityConfig.java` |
| **HRD-REQ-14**, **15**, **16** | Perfil `prod`, HikariCP bounds, `INFO` logs, `graceful` shutdown, `.env.example` | `application.yml`, `application-dev.yml`, `application-prod.yml`, `.env.example` |
| **HRD-REQ-17**, **18**, **19**, **20** | `OpenApiConfig` + `OpenApiCustomizer` + `@Tag`/`@Operation`/`@Schema` + toggle por entorno | `OpenApiConfig.java`, 6 `*Controller.java`, DTOs, `application*.yml` |
| **HRD-REQ-21**, **22**, **23** | `MvpEndToEndJourneyIntegrationTest`, `OpenApiContractIntegrationTest`, `ProdProfileIntegrationTest`, Spotless + suite completa | `src/test/java/...`, `MEMORY.md`, `docs/specs/005-hardening/*` |
