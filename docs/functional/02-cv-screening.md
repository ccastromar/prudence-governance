# 02 · CV Screening Assistant 🔴 HIGH

**Qué es:** una IA que ordena los currículums de quienes buscan trabajo antes de que un
reclutador los lea.
**Por qué es de riesgo alto:** puede decidir quién consigue una entrevista. La ley la pone en
la lista de alto riesgo (Anexo III, punto 4a).
**Diferencia con la 01:** aquí la empresa **fabrica** la IA (`PROVIDER`), no solo la usa, así
que tiene más obligaciones (6).

**Datos de prueba:** dueño *Bob RiskOfficer* · versión `1.0.0` · `PROVIDER`.

> Haz los journeys **en orden**.

---

## J1 · Comparar con la otra IA de alto riesgo

1. Ve a **AI system registry**.

**Deberías ver en *CV Screening Assistant*:**
- [ ] Riesgo **HIGH** + Anexo III.
- [ ] `PROVIDER · v1.0.0` (en la 01 ponía `DEPLOYER`).
- [ ] *Owner*: Bob RiskOfficer.
2. Ve a **Approvals** → *Ready to submit*:
- [ ] En la columna *Evidence* pone **6 obligations · No Annex I**.
3. **Copia su id.**

---

## J2 · El portero con un usuario desconocido

1. Ve a **Policy decisions** y rellena:
   - *Subject*: `mallory@bank.eu` (una persona que no está dada de alta)
   - *Pipeline*: `credit-risk-scoring`
   - *AI system id*: el de esta IA
   - *Action*: `run`
2. Pulsa **Evaluate**.

**Deberías ver:**
- [ ] 🟡 **APPROVAL REQUIRED**: la IA **todavía no está aprobada**, así que Prudence ni
  siquiera mira quién pregunta.

> 🤔 Lo importante: el orden es **primero "¿está aprobada la IA?"** y **luego "¿puede esta
> persona?"**.

---

## J3 · Primera firma y rechazo del segundo revisor

1. **Approvals** → *Ready to submit* → **Submit for review** en *CV Screening Assistant*.
2. En *Awaiting decision* → **Approve** (una vez).
- [ ] Aparece *First sign-off: dev-reviewer*.
3. Ahora pulsa **Reject** y en *Reason* escribe `El dataset de entrenamiento no está documentado`.
4. **Confirm reject**.

**Deberías ver:**
- [ ] Estado **REJECTED** en *Recently decided*.
5. Clic en la fila → *Audit trail*:
- [ ] **FIRST_APPROVAL** y luego **REJECT**, en ese orden, con la hora de cada uno.
- [ ] El motivo aparece entre comillas.

---

## J4 · Rechazar sin escribir motivo

1. **Ready to submit** → **Submit for review** otra vez.
2. **Awaiting decision** → **Reject** → deja *Reason* **vacío** → **Confirm reject**.

**Apunta qué pasa** (no hay respuesta "correcta" escrita; es una prueba exploratoria):
- [ ] ¿Te deja rechazar sin motivo? ¿Sale algún error?
- [ ] Si te deja: en el *Audit trail*, ¿cómo aparece la línea REJECT?

> 💬 En revisiones de gobierno lo normal es exigir un motivo. Si te deja sin él, apúntalo
> como **observación** (no necesariamente un fallo).

---

## J5 · Incidente grave CON suspensión inmediata ⛔

Imagina que se descubre que la IA descarta todos los currículums de mujeres.

1. Ve a **Incidents** → *Register an incident*:
   - *AI system*: CV Screening Assistant
   - *Title*: `Sesgo de género`
   - *What happened*: `La IA puntúa más bajo los CV con nombres femeninos`
   - *Occurred at*: hace 3 días
   - *Became aware at*: hoy
   - *Classification*: **Fundamental-rights infringement**
   - *Member State(s)*: `ES, PT`
   - *Widespread infringement*: ⬜ sin marcar
   - **Suspend the AI system now**: ✅ **marcado**
2. **Register incident**.

**Deberías ver:**
- [ ] Incidente **OPEN**, plazo para informar de **15 días** desde hoy (no 2, porque no es *widespread*).
3. Ve a **AI system registry**:
- [ ] *CV Screening Assistant* ahora está **SUSPENDED**.
4. Ve a **Posture**:
- [ ] 🔴 **RED** · *Suspended — must not run until reinstated.* (y el aviso del incidente).
5. Ve a **Policy decisions** y repite la pregunta de J2 con `alice@bank.eu`:
- [ ] **No** sale ALLOW.

---

## J6 · Volver a enviar una IA suspendida

Una IA suspendida **solo vuelve a funcionar** si se reenvía a revisión y se aprueba de nuevo.

1. Ve a **Approvals** → *Ready to submit — assessed, rejected, expired or suspended*.

**Deberías ver:**
- [ ] *CV Screening Assistant* aparece en esa lista (estado SUSPENDED).
2. Pulsa **Submit for review**.
- [ ] Pasa a *Awaiting decision*: vuelve a necesitar las dos firmas (es HIGH).
- [ ] En **Posture** deja de estar 🔴 RED *Suspended* y pasa a 🟡 AMBER *Awaiting governance approval*.

❌ **Fallo si** no aparece en ninguna sección de *Approvals*: no habría forma de rehabilitarla.

---

## J7 · Cambiar la clasificación del incidente

1. En **Incidents**, abre *Sesgo de género* (debe seguir **OPEN**).
2. En la parte de re-triage, cambia *Classification* a **Not a serious incident**.
3. Pulsa re-triage **sin** escribir nada en *Note*:
- [ ] El botón está desactivado o no hace nada (la nota es obligatoria).
4. Escribe en *Note* `Tras revisar: era un error de configuración, sin efecto real` y vuelve a pulsar.

**Deberías ver:**
- [ ] Ya no hay plazo de informe: *Not a serious incident… no Art. 73 report required.*
- [ ] El estado de la IA **no cambia** por reclasificar el incidente (sigue donde la dejó J6: *Awaiting decision*). Un incidente nunca aprueba ni reactiva una IA por sí solo.
