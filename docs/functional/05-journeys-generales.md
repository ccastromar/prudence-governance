# 05 · Journeys generales

Pruebas que no van de una IA concreta: dar de alta una IA nueva, el caso "prohibida" y los
certificados. Puedes hacerlas en cualquier momento.

---

## J1 · Dar de alta una IA nueva desde el formulario

1. Ve a **AI system registry**. Arriba está el formulario.
2. Rellena:

| Campo | Valor |
|---|---|
| Name | `Fraud Detection Cards` |
| Risk tier | `LIMITED` |
| EU AI Act role | `DEPLOYER` |
| Intended purpose | `Marca pagos con tarjeta sospechosos para revisión humana` |
| Owner name | `Tu nombre` |
| Owner email | `tester@example.eu` |
| System version | `1.0` |
| Model / build SHA-256 | `sha256:abc123` |
| Operating geography | `European Union` |
| Affected population | `Clientes con tarjeta` |
| Annex I / Annex III | las dos **sin marcar** |
| Classification justification | `Detección de fraude: excluida del Anexo III` |
| Applicable obligations | `Art 50 transparency` |

3. Pulsa **Register system**.

**Deberías ver:**
- [ ] El formulario se vacía.
- [ ] La IA nueva aparece en la tabla con **UNDER_ASSESSMENT**.
- [ ] Aparece también en **Approvals** → *Ready to submit* y en **Posture** (🟡 AMBER).

---

## J2 · Formulario con errores (intenta romperlo 😈)

Haz **una prueba cada vez** y apunta qué pasa:

| # | Qué haces | Esperado |
|---|---|---|
| a | Dejas *Name* vacío y pulsas Register | No se envía; el navegador marca el campo |
| b | *Owner email*: `esto-no-es-un-email` | No se envía (email no válido) |
| c | *Applicable obligations* vacío | No se envía |
| d | *Name* con 300 letras | Apunta qué pasa |
| e | *Name* con emojis y acentos: `Asistente Ñandú 🤖` | Se guarda y se ve bien en la tabla |
| f | Pulsas **Register system** dos veces muy rápido | Apunta si se crea la IA **dos veces** |

> Si alguna da *Registration failed* en rojo, apunta **qué campo** la provocó.

---

## J3 · Una IA prohibida ⛔

Algunas IAs están **prohibidas** por ley (p. ej. puntuar socialmente a la gente).

1. Registra una IA como en J1, pero con:
   - *Name*: `Social Scoring` · *Risk tier*: **PROHIBITED**
   - *Intended purpose*: `Puntúa a los clientes según su comportamiento en redes sociales`
2. **Register system**.

**Deberías ver:**
- [ ] Estado **BLOCKED** directamente (no UNDER_ASSESSMENT).
- [ ] En **Approvals**: **no** aparece en *Ready to submit* ni en *Awaiting decision* (no se
  puede ni enviar a revisión).
- [ ] En **Posture**: 🔴 **RED** · *Prohibited tier — blocked from use.*
3. Copia su id → **Policy decisions** con `alice@bank.eu` / `credit-risk-scoring` / `run`:
- [ ] 🔴 **DENY** · *prohibited AI system*.

❌ **Fallo grave si** de alguna forma puedes aprobarla.

---

## J4 · Generar un certificado (*attestation*)

Un certificado es una "foto" firmada del estado de todas las IAs en este momento.

1. Ve a **Attestations** → **Generate attestation**.

**Deberías ver:**
- [ ] Una fila nueva con la fecha de ahora (*Issued*), un resumen (*Estate*) y un código
  *Content hash* (una especie de huella digital del certificado).
2. Haz clic en la fila → se abre el detalle.
3. Pulsa **Verify**:
- [ ] Sale un resultado de verificación. Con Ernest encendido debería confirmar que el
  certificado está guardado y no se ha modificado. Sin Ernest, lo dirá (*PENDING* / *UNSEALED*).
4. Pulsa el botón **OSCAL**:
- [ ] Se abre una pestaña nueva con texto en formato JSON (es el mismo certificado en un
  formato estándar que leen las máquinas). No hace falta entenderlo: solo que **se abra** y
  no salga un error.

---

## J5 · El certificado refleja los cambios

1. Genera un certificado (J4) y apunta lo que dice *Estate*.
2. Cambia algo: por ejemplo, envía a revisión y aprueba *Fraud Detection Cards*.
3. Genera **otro** certificado.

**Deberías ver:**
- [ ] El *Content hash* del segundo es **distinto** al del primero.
- [ ] El primer certificado **sigue ahí** y no ha cambiado (los certificados no se reescriben).

---

## J6 · ¿Qué pasa si Ernest se apaga? (pide ayuda para esto)

Pide a quien te pasó las pruebas que **pare Ernest** un momento.

1. Ve a **Posture** y refresca.

**Deberías ver:**
- [ ] Arriba a la derecha: *Evidence plane (Ernest)* **● unavailable** (rojo).
- [ ] Las IAs aprobadas pasan a 🟡 **AMBER** · *the evidence layer is unavailable*.
- [ ] Prudence **sigue funcionando**: puedes navegar y hacer decisiones.
2. Aprueba algo o genera un certificado mientras Ernest está apagado.
3. Pide que **vuelvan a arrancar Ernest**, espera 1 o 2 minutos y refresca **Posture**.
- [ ] Vuelve a **● healthy**.
- [ ] Lo que hiciste con Ernest apagado **no se ha perdido** (Prudence lo guarda y lo envía
  después). Si ves *Undelivered records* con un botón **Retry**, púlsalo y apunta qué pasa.
