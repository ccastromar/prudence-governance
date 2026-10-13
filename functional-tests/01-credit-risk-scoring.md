# 01 · Credit Risk Scoring EU 🔴 HIGH

**Qué es:** la IA que decide si a una persona se le concede un préstamo.
**Por qué es de riesgo alto:** decide sobre el dinero y la vida de la gente. La ley la pone en
la lista de usos de alto riesgo (Anexo III, punto 5b). Por eso **necesita dos firmas** para
aprobarse.

**Datos de prueba:** dueña *Alice Operator* · versión `2.4.1` · el banco es quien la **usa**
(`DEPLOYER`) · 5 obligaciones legales.

> Haz los journeys **en orden**: cada uno parte de donde terminó el anterior.

---

## J1 · Ver la ficha en el registro

1. Ve a **AI system registry**.
2. Busca *Credit Risk Scoring EU* en la tabla de abajo.

**Deberías ver:**
- [ ] Una etiqueta de riesgo **HIGH** (y la marca de Anexo III).
- [ ] Debajo: `DEPLOYER · v2.4.1`.
- [ ] *Lifecycle*: **UNDER_ASSESSMENT**.
- [ ] *Owner*: Alice Operator.
3. **Copia su id** (el código largo debajo del nombre). Lo usarás en J2.

---

## J2 · Preguntar al portero antes de aprobarla

Nadie la ha aprobado todavía. Si alguien intenta usarla, Prudence tiene que decir "espera".

1. Ve a **Policy decisions**.
2. Rellena:
   - *Subject*: `alice@bank.eu`
   - *Pipeline*: `credit-risk-scoring`
   - *AI system id*: pega el id de J1
   - *Action*: `run`
3. Pulsa **Evaluate**.

**Deberías ver:**
- [ ] Etiqueta amarilla **APPROVAL REQUIRED**.
- [ ] `approver: risk-officer`.
- [ ] Un texto que dice que está esperando aprobación (*high-risk system awaiting governance approval*).

❌ **Fallo si** sale **ALLOW** (verde): una IA sin aprobar nunca puede dejarse usar.

---

## J3 · Enviarla a revisión

1. Ve a **Approvals**.
2. En la sección *Ready to submit*, busca *Credit Risk Scoring EU*.
3. Pulsa **Submit for review**.

**Deberías ver:**
- [ ] Desaparece de *Ready to submit*.
- [ ] Aparece en **Awaiting decision**, con su propósito y su dueña.
4. Ve a **Posture** → la fila de esta IA:
- [ ] Semáforo 🟡 **AMBER** · *Awaiting governance approval.*

---

## J4 · Primera firma (cuatro ojos, parte 1)

1. Ve a **Approvals** → *Awaiting decision*.
2. En *Credit Risk Scoring EU*, pulsa **Approve** (solo una vez).

**Deberías ver:**
- [ ] **Sigue** en *Awaiting decision* (no está aprobada del todo).
- [ ] En la columna *Owner* aparece: **First sign-off: dev-reviewer**.
3. Ve a **Posture**:
- [ ] Sigue 🟡 **AMBER**, con el texto *First sign-off recorded; awaiting the independent second approval.*

---

## J5 · Segunda firma con la misma persona (debe fallar ✋)

Esta es **la prueba más importante** de esta IA: una sola persona no puede aprobarla sola.

1. En **Approvals** → *Awaiting decision*, pulsa otra vez **Approve** en *Credit Risk Scoring EU*.

**Deberías ver:**
- [ ] Un **mensaje de error en rojo** arriba que dice que el segundo aprobador tiene que ser
  otra persona (*the second high-risk approver must be independent*).
- [ ] La IA **sigue** en *Awaiting decision* con *First sign-off: dev-reviewer*.

❌ **Fallo grave si** pasa a *APPROVED*: significaría que una persona sola puede aprobar una IA
de alto riesgo.

> 🔐 Con login (modo Keycloak): si firma `alice` y luego `bob`, debe quedar **APPROVED**.

---

## J6 · Rechazarla después de la primera firma

Cualquiera de los dos revisores puede decir que no, incluso después de que el primero firmara.

1. En **Approvals** → *Awaiting decision*, pulsa **Reject** en *Credit Risk Scoring EU*.
2. Se abre una ventanita. En *Reason* escribe: `Falta el informe de sesgo por edad`.
3. Pulsa **Confirm reject**.

**Deberías ver:**
- [ ] Sale de *Awaiting decision*.
- [ ] Aparece en *Recently decided* con estado **REJECTED**.
- [ ] Aparece **también** en *Ready to submit* (se puede volver a enviar).
4. En *Recently decided*, **haz clic en la fila** de la IA. Se abre su *Audit trail*:
- [ ] Una línea **FIRST_APPROVAL** de `dev-reviewer`.
- [ ] Una línea **REJECT** con tu motivo entre comillas: "Falta el informe de sesgo por edad".
- [ ] Cada línea dice de qué estado pasó a cuál (p. ej. `PENDING_SECOND_APPROVAL → REJECTED`).
5. Ve a **Posture**:
- [ ] 🔴 **RED** · *Rejected by governance review.*
- [ ] El semáforo general de arriba (*Estate roll-up*) también en 🔴 RED.

---

## J7 · Volver a enviarla (empieza de cero)

1. En **Approvals** → *Ready to submit*, pulsa **Submit for review** en *Credit Risk Scoring EU*.

**Deberías ver:**
- [ ] Vuelve a *Awaiting decision*.
- [ ] **Ya NO** pone *First sign-off*: la firma de antes no cuenta, la revisión empieza de cero.

❌ **Fallo si** sigue apareciendo *First sign-off: dev-reviewer* (se habría "colado" una firma vieja).

---

## J8 · Registrar un incidente grave (discriminación)

Imagina que se descubre que la IA rechaza más préstamos a personas mayores.

1. Ve a **Incidents**.
2. Rellena *Register an incident*:
   - *AI system*: Credit Risk Scoring EU
   - *Title*: `Rechazos por edad`
   - *What happened*: `Se rechazan más préstamos a mayores de 60 años sin motivo financiero`
   - *Occurred at*: ayer a cualquier hora
   - *Became aware at*: hoy, hace una hora
   - *Classification*: **Fundamental-rights infringement**
   - *Causal link*: el que indique que la IA lo causó (o el más directo de la lista)
   - *Member State(s)*: `ES`
   - **Widespread infringement**: ✅ marcado
   - **Suspend the AI system now**: ⬜ sin marcar
3. Pulsa **Register incident**.

**Deberías ver:**
- [ ] El incidente en la lista, estado **OPEN**.
4. Haz clic en él:
- [ ] Indica que hay que informar **en 2 días** desde que os enterasteis (por marcar *Widespread*).
- [ ] La fecha límite es "hoy hace una hora + 2 días".
5. Ve a **Posture**:
- [ ] Esta IA está como mínimo 🟡 **AMBER**, con el texto *1 open serious incident(s)*.

---

## J9 · Informar del incidente a la autoridad

1. En **Incidents**, abre *Rechazos por edad*.
2. En la parte de informes:
   - *Filed with*: `AESIA (ES)`
   - *Their reference*: `AESIA-2026-0001`
   - *Submitted at*: ahora
3. Pulsa el botón del **informe inicial**.

**Deberías ver:**
- [ ] Estado **INITIAL REPORT SUBMITTED**.
- [ ] Que se informó **a tiempo** (*on time*).
4. Repite con el **informe final**, rellenando *Root cause* y *Corrective actions*:
- [ ] Estado **FINAL REPORT SUBMITTED**.
5. Escribe una *Note* (`Revisado y cerrado`) y pulsa **Close incident**:
- [ ] Estado **CLOSED**.
- [ ] El botón de cerrar **no funciona** si la nota está vacía (pruébalo antes).
