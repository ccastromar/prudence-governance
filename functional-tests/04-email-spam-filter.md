# 04 · Email Spam Filter 🟢 MINIMAL

**Qué es:** el filtro que separa el correo basura del correo bueno en los buzones de la empresa.
**Por qué es de riesgo mínimo:** no decide nada sobre las personas ni habla con ellas. La ley
no le pone obligaciones; solo un código de conducta voluntario.
**Para qué sirve en las pruebas:** comprobar que **incluso la IA más inofensiva pasa por
revisión** y que el "rechazar y volver a enviar" funciona de principio a fin.

**Datos de prueba:** dueño *Platform Team* · versión `5.1.3` · `DEPLOYER` · 1 obligación.

> Haz los journeys **en orden**.

---

## J1 · Riesgo mínimo ≠ aprobada automáticamente

1. Ve a **AI system registry**.

**Deberías ver:**
- [ ] *Email Spam Filter* con riesgo **MINIMAL** y estado **UNDER_ASSESSMENT** (como las demás).
2. Ve a **Posture**:
- [ ] 🟡 **AMBER** · *Classification under assessment — not yet submitted for review.*

❌ **Fallo si** sale APPROVED o GREEN sin que nadie la haya aprobado: **ninguna** IA se
aprueba sola, por poco riesgo que tenga.

---

## J2 · Un id que no existe

1. Ve a **Policy decisions**:
   - *Subject*: `alice@bank.eu` · *Pipeline*: `credit-risk-scoring` · *Action*: `run`
   - *AI system id*: `no-existo-123`
2. **Evaluate**.

**Deberías ver:**
- [ ] 🔴 **DENY** · *unknown AI system: no-existo-123*.
3. Prueba ahora con el *AI system id* **vacío**:
- [ ] Apunta lo que pasa (¿DENY? ¿error?). Lo correcto es que **nunca** salga ALLOW.

---

## J3 · Rechazar → reenviar → aprobar (el ciclo completo)

1. **Approvals** → *Ready to submit* → **Submit for review** en *Email Spam Filter*.
2. *Awaiting decision* → **Reject** → *Reason*: `Falta indicar quién revisa los falsos positivos` → **Confirm reject**.
- [ ] Estado **REJECTED**. Aparece en *Ready to submit* y en *Recently decided*.
3. *Ready to submit* → **Submit for review** otra vez.
- [ ] Vuelve a *Awaiting decision*.
4. **Approve** (una vez).
- [ ] **APPROVED** a la primera (riesgo mínimo: una firma).
5. *Recently decided* → clic en la fila → *Audit trail*:
- [ ] Ves **REJECT** (con el motivo) y después **APPROVE**, en orden de hora.
- [ ] El rechazo **no ha desaparecido** del historial aunque luego se aprobara.

> 🧠 La idea: el historial **nunca se borra**. Si un auditor pregunta "¿esta IA tuvo algún
> problema?", tiene que poder ver el rechazo.

---

## J4 · El semáforo general (*Estate roll-up*)

Ahora tienes IAs en varios estados. Ve a **Posture**.

1. Apunta el color de **cada fila** y el de **arriba** (*Estate roll-up*).

**Deberías ver:**
- [ ] El de arriba es **el peor color** de todas las filas. Si alguna está 🔴 (p. ej. *CV
  Screening* suspendida), arriba sale 🔴 **RED** aunque el spam filter esté verde.
- [ ] *Email Spam Filter*: 🟡 AMBER o 🟢 GREEN según Ernest (mira la tabla de J5 en
  [03-customer-chatbot.md](03-customer-chatbot.md)).

---

## J5 · Incidente que NO es grave

1. **Incidents** → *Register an incident*:
   - *AI system*: Email Spam Filter
   - *Title*: `Correos de nóminas en spam`
   - *What happened*: `Durante 2 horas los correos de nóminas fueron a la carpeta de spam`
   - *Occurred at*: hoy por la mañana · *Became aware at*: hoy, hace 30 min
   - *Classification*: **Not a serious incident**
   - *Member State(s)*: `ES`
   - Las dos casillas sin marcar
2. **Register incident**.

**Deberías ver:**
- [ ] Incidente **OPEN**, **sin** plazo de informe (*no Art. 73 report required*).
- [ ] **No** aparecen los campos para informar a la autoridad (*Filed with*…).
3. Ve a **Posture**:
- [ ] El spam filter **no** cambia a AMBER por este incidente (solo cuentan los graves).
4. Ciérralo: *Note* `Regla de filtrado corregida` → **Close incident** → estado **CLOSED**.
