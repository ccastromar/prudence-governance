# 06 · Demo en vivo: AOS pide permiso a Prudence 🎬

**Qué es:** una demostración con **dos pantallas**. En **AOS** (el que *ejecuta* tareas) escribes
una petición; antes de hacer nada, AOS **pregunta a Prudence** (el *portero*) si puede usar esa IA.
Prudence contesta según el **nivel de riesgo** y el **estado de aprobación** de la IA, y AOS obedece.

**Sin IA de verdad:** AOS usa un **modelo de demostración determinista** (`aos-demo:deterministic`).
No piensa ni inventa: reconoce las frases de la demo y responde siempre lo mismo. Así la demo
no depende de una GPU ni de internet, y nadie puede decir que "el modelo tuvo suerte".

**Las 4 IAs de la demo** salen del seed público (`scripts/seed-mvp.sh`):

| Botón en AOS | IA en Prudence | Riesgo | Estado inicial | Primera respuesta |
|---|---|---|---|---|
| *Support lookup · limited risk* | MVP · Asistente de soporte al cliente | 🟡 LIMITED | UNDER_ASSESSMENT (SUSPENDED tras un reinicio) | 🔴 **DENY** |
| *Credit pre-screening · high risk* | MVP · Preselección de crédito al consumo | 🔴 HIGH | PENDING_APPROVAL | 🟠 **APPROVAL REQUIRED** |
| *Social scoring · prohibited* | MVP · Puntuación social de ciudadanos | ⛔ PROHIBITED | BLOCKED | 🔴 **DENY** (siempre) |
| *Translator · suspended* | MVP · Traductor de comunicaciones | 🟡 LIMITED | SUSPENDED | 🔴 **DENY** |

> 🔁 **Repetir la demo:** la demo **aprueba** estas IAs, y Prudence no tiene botón de "desaprobar"
> (su historial solo se añade, nunca se borra). Para volver al principio ejecuta
> `./scripts/aos-demo-rearm.sh`: abre en cada IA aprobada un incidente **no grave**,
> *Reinicio de la demo en vivo de AOS*, que la suspende, y lo cierra al momento; el de crédito
> vuelve a quedar esperando sus dos firmas. Los ids no cambian y no hace falta reiniciar AOS.
> Cada reinicio deja esos incidentes cerrados en el registro, a propósito: es la verdad de lo
> que pasó. Ojo: después de un reinicio, `seed-mvp.sh` se queja de que el soporte ya no está
> en UNDER_ASSESSMENT; es su comprobación estricta y no afecta a la demo.

---

## Encender todo (lo hace quien prepara la demo)

1. Prudence, OPA y la UI de Prudence como en [00-arrancar.md](00-arrancar.md), y después el seed:
   - **Sin login:** `SEED_AUTHOR_TOKEN=dev SEED_REVIEWER_ONE_TOKEN=dev SEED_REVIEWER_TWO_TOKEN=dev ./scripts/seed-mvp.sh`
     (en modo de pruebas los tokens no se comprueban).
   - **Con login:** el seed necesita **tres** usuarios distintos (un autor y dos aprobadores de sistemas) (ver `scripts/README.md`).
2. Saca los ids de las 4 IAs para AOS (solo lee, no cambia nada):
   ```bash
   ./scripts/aos-demo-env.sh > /tmp/aos-demo.env
   ```
   En pantalla verás una tabla con cada IA, su estado y **la respuesta que debería dar primero**.
   Si no coincide con la tabla de arriba, para y revisa antes de empezar.
3. En la carpeta de AOS:
   ```bash
   set -a; . /tmp/aos-demo.env; set +a
   PRUDENCE_PDP_URL=http://127.0.0.1:8099 PRUDENCE_PDP_SUBJECT=service-account-aos-pdp ./run.sh demo
   ```
   **Con login**, añade también `PRUDENCE_PDP_TOKEN_URL=http://localhost:8081/realms/chestertown/protocol/openid-connect/token`,
   `PRUDENCE_PDP_CLIENT_ID=pdp-client` y `PRUDENCE_PDP_CLIENT_SECRET=pdp-dev-secret`.
4. Abre **AOS** en http://localhost:5173 y **Prudence** en http://localhost:5200, una al lado de la otra.

> 🔒 Si falta un id o la URL de Prudence, AOS **no ejecuta** esas tareas y lo dice
> (*requires a Prudence verdict … (fail-closed)*). Es a propósito: sin portero, no se pasa.

---

## J1 · Soporte: de "no" a "sí" con una firma 🟡

1. En AOS, pulsa **Support lookup · limited risk** y envía el texto.

**Deberías ver:**
- [ ] Tarjeta roja **Denied by Prudence** · *AI system not approved*.
- [ ] *AOS did not run any step*: no se ha llamado a ninguna herramienta.

2. En Prudence → **Approvals**: *Submit for review* en *MVP · Asistente de soporte al cliente* (sale para reenviar si viene de un reinicio) y luego **Approve** (una sola firma).
3. En AOS, envía **otra vez** la misma petición.

**Deberías ver:**
- [ ] **Final Result** con el resumen: *AOS looked up the support service read-only and changed no data.*

❌ **Fallo si** AOS ejecuta la consulta **antes** de aprobarla en Prudence.

---

## J2 · Crédito: alto riesgo, hacen falta dos personas 🔴

1. En AOS, pulsa **Credit pre-screening · high risk** y envía.

**Deberías ver:**
- [ ] Tarjeta **ámbar** *Blocked by Prudence: approval required* · aprobador `risk-officer`.
- [ ] Ningún botón en AOS para "aprobar aquí": **la aprobación solo se hace en Prudence**.

2. En Prudence → **Approvals** → **Approve** (primera firma). Repite en AOS → sigue **ámbar**.
3. **Con login:** *Sign out*, entra con la **otra** persona y firma otra vez → **APPROVED**. Repite en AOS.

**Deberías ver (con login):**
- [ ] **Final Result**: la solicitud queda en la cola de revisión de los analistas; *la decisión sigue pendiente de revisión humana*.

> 🤔 **Sin login** la segunda firma da error (*the second high-risk approver must be independent*):
> todo va firmado como `dev-reviewer`. Eso **es** la regla de los cuatro ojos funcionando, y es
> un buen momento para contarlo en la demo.

---

## J3 · Puntuación social: prohibida, siempre "no" ⛔

1. En AOS, pulsa **Social scoring · prohibited** y envía.

**Deberías ver:**
- [ ] Tarjeta roja **Denied by Prudence** · *prohibited AI system*.
- [ ] En Prudence **no hay forma** de aprobarla (*a prohibited AI system cannot be approved*).

> 🛡️ Doble barrera: aunque alguien lograra aprobarla, la política de OPA **no da permiso** a ese
> pipeline. Y la herramienta de prueba de AOS se niega a puntuar a nadie si alguna vez la llamaran.

---

## J4 · Traductor: suspendido por un incidente 🟡⛔

1. En AOS, pulsa **Translator · suspended** y envía → 🔴 **DENY** · *AI system not approved*.
2. En Prudence → **Approvals**: el traductor aparece para volver a enviarlo. *Submit for review* → **Approve**.
3. Repite en AOS.

**Deberías ver:**
- [ ] **Final Result** con `translation=a agência abre às nove`.

---

## J5 · (Opcional) Cuando falla la máquina, no el portero 💥

Escribe en AOS una petición de la demo y añade al final una de estas marcas:

| Marca | Qué simula | Qué debes ver |
|---|---|---|
| `#mock-fail:500` | El modelo se cae | **Execution Failed** (rojo), *no* la tarjeta de Prudence |
| `#mock-fail:badjson` | El modelo responde basura | **Execution Failed** · *invalid JSON from LLM* |
| `#mock-fail:slow` | El modelo tarda | Tarda unos segundos y luego sigue normal |

- [ ] Ningún fallo técnico ejecuta la tarea "por si acaso".
- [ ] Una decisión de Prudence se ve **distinta** de un fallo técnico (tarjeta con escudo vs. *Execution Failed*).

---

## La frase que lo resume

**AOS hace, Prudence decide.** AOS no puede saltarse a Prudence, y lo que Prudence aprueba,
revoca o prohíbe cambia lo que AOS hace **en la siguiente petición**, sin tocar AOS.
