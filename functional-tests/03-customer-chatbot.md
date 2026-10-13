# 03 · Customer Support Chatbot 🟡 LIMITED

**Qué es:** el chat de la web y la app que responde dudas de clientes sobre cuentas y tarjetas.
**Por qué es de riesgo limitado:** no decide nada importante sobre las personas, pero habla
con ellas. Su obligación principal: **avisar de que estás hablando con una IA** (art. 50).
**Diferencia clave:** **basta una firma** para aprobarla (no hay cuatro ojos).

**Datos de prueba:** dueña *Alice Operator* · versión `3.2.0` · `DEPLOYER` · 1 obligación.

> Haz los journeys **en orden**.

---

## J1 · Antes de aprobar: el portero dice que no

1. En **AI system registry**, copia el id de *Customer Support Chatbot*.
2. Ve a **Policy decisions**:
   - *Subject*: `alice@bank.eu` · *Pipeline*: `credit-risk-scoring` · *AI system id*: el del chatbot · *Action*: `run`
3. **Evaluate**.

**Deberías ver:**
- [ ] 🔴 **DENY** con *AI system not approved*.

> 🤔 Fíjate: en las IAs **HIGH** salía *APPROVAL REQUIRED* (amarillo, "espera a que un
> responsable de riesgos firme"). Aquí sale **DENY** directamente. Es correcto: solo las de
> alto riesgo tienen esa vía de "pedir aprobación".

---

## J2 · Aprobación con una sola firma

1. **Approvals** → *Ready to submit* → **Submit for review** en el chatbot.
2. *Awaiting decision* → **Approve** (una sola vez).

**Deberías ver:**
- [ ] Desaparece de *Awaiting decision* **a la primera** (sin *First sign-off*).
- [ ] En *Recently decided*: **APPROVED** y debajo **Expires …** con una fecha de **dentro de un año**.

❌ **Fallo si** se queda esperando una segunda firma (eso es solo para HIGH).

---

## J3 · El historial de la aprobación

1. En *Recently decided*, haz clic en *Customer Support Chatbot*.

**Deberías ver en el *Audit trail*:**
- [ ] Una línea **APPROVE** en verde.
- [ ] `PENDING_APPROVAL → APPROVED · by dev-reviewer`.
- [ ] Un indicador de la **prueba en Ernest** (si Ernest está encendido: guardada / con número
  de bloque; si no: pendiente).
2. Pulsa **Close**: el historial se cierra.

---

## J4 · El portero ahora sí deja pasar… a quien toca

Repite **Policy decisions** con el id del chatbot y apunta el resultado de cada fila:

| # | Subject | Pipeline | Action | Esperado |
|---|---|---|---|---|
| a | `alice@bank.eu` | `credit-risk-scoring` | `run` | 🟢 **ALLOW** |
| b | `alice@bank.eu` | `credit-risk-scoring` | `approve` | 🔴 **DENY** (*denied by policy engine*): Alice solo puede ejecutar, no aprobar |
| c | `bob@bank.eu` | `credit-risk-scoring` | `approve` | 🟢 **ALLOW**: Bob es responsable de riesgos |
| d | `mallory@bank.eu` | `credit-risk-scoring` | `run` | 🔴 **DENY**: Mallory no tiene ningún permiso |
| e | `alice@bank.eu` | `customer-chat` | `run` | 🔴 **DENY**: nadie tiene permiso en ese pipeline |
| f | `ALICE@bank.eu` (mayúsculas) | `credit-risk-scoring` | `run` | Apunta lo que salga |

> ⚠️ Si **todo** sale DENY con *denied by policy engine*, probablemente **OPA no está
> arrancado**. Avisa antes de seguir: no es un fallo de la prueba.

> 💡 La fila **f** es exploratoria: ¿trata Prudence igual un email en mayúsculas?

---

## J5 · El semáforo de una IA aprobada

1. Ve a **Posture** y busca el chatbot.

**Deberías ver uno de estos casos:**

| Si… | Semáforo | Texto |
|---|---|---|
| Ernest está **apagado** | 🟡 AMBER | *Approved, but the evidence layer is unavailable.* |
| Ernest encendido, aún sin pruebas | 🟡 AMBER | *Approved, but no evidence recorded yet.* |
| Ernest encendido y con pruebas | 🟢 **GREEN** | *Approved and attested by Ernest.* |

2. Arriba a la derecha, *Evidence plane (Ernest)*: **● healthy** (verde) o **● unavailable** (rojo).
- [ ] ¿Coincide con el semáforo de la fila?
3. Si ves AMBER *no evidence recorded yet*: haz 3 o 4 decisiones ALLOW como la fila **a** de
   J4, **espera 1 minuto** y refresca.
- [ ] ¿Pasa a 🟢 GREEN?

---

## J6 · Intentar aprobarla otra vez

1. Ya está APPROVED. Mira **Approvals** → *Awaiting decision*.

**Deberías ver:**
- [ ] El chatbot **no** aparece ahí (ni hay botón para aprobarla dos veces).
- [ ] *Renewal due* dice *No approvals inside their review window.* La renovación solo aparece
  los últimos 30 días antes de caducar, así que **hoy no se puede probar**.
