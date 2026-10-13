# Pruebas funcionales de Prudence

Guías para probar Prudence **a mano, desde el navegador**, sin saber programar.
Cada archivo es un sistema de IA con varios *journeys* (recorridos): pasos a seguir y lo que
deberías ver. Si ves otra cosa, has encontrado un posible fallo. 🎯

| Archivo | Sistema de IA | Riesgo |
|---|---|---|
| [00-arrancar.md](00-arrancar.md) | **Empieza aquí:** cómo encender todo | — |
| [01-credit-risk-scoring.md](01-credit-risk-scoring.md) | Credit Risk Scoring EU | 🔴 HIGH |
| [02-cv-screening.md](02-cv-screening.md) | CV Screening Assistant | 🔴 HIGH |
| [03-customer-chatbot.md](03-customer-chatbot.md) | Customer Support Chatbot | 🟡 LIMITED |
| [04-email-spam-filter.md](04-email-spam-filter.md) | Email Spam Filter | 🟢 MINIMAL |
| [05-journeys-generales.md](05-journeys-generales.md) | Registro, sistemas prohibidos, certificados | — |

---

## ¿Qué es Prudence? (en 1 minuto)

Imagina un banco que usa varias **inteligencias artificiales**: una decide si te dan un
préstamo, otra lee currículums, otra es el chat de atención al cliente…

La ley europea (la **EU AI Act**) dice que algunas de esas IAs son peligrosas si se usan mal,
y que **antes de usarlas alguien tiene que revisarlas y dar el visto bueno**.

**Prudence es el "portero"**: lleva la lista de todas las IAs, cuánto riesgo tiene cada una,
quién las ha aprobado, y responde "sí / no / espera" cuando alguien quiere usarlas.

## Palabras que vas a ver

| En pantalla | Qué significa |
|---|---|
| **AI system** | Una inteligencia artificial que usa la empresa |
| **Risk tier** (nivel de riesgo) | `PROHIBITED` prohibida · `HIGH` alto · `LIMITED` limitado · `MINIMAL` mínimo |
| **Owner** | La persona responsable de esa IA |
| **Submit for review** | "Enviar a revisión": pedir que alguien la apruebe |
| **Approve / Reject** | Aprobar / Rechazar |
| **Four-eyes** ("cuatro ojos") | Las IAs de riesgo `HIGH` necesitan **dos personas distintas** para aprobarse |
| **Audit trail** | El historial: quién hizo qué y cuándo |
| **Posture** | Un semáforo 🟢🟡🔴 de cómo está cada IA |
| **Policy decision** | Preguntar al portero: "¿puede esta persona usar esta IA?" |
| **Incident** | Algo salió mal con una IA (p. ej. discriminó a alguien) |
| **Attestation** | Un "certificado" con el estado de todas las IAs en un momento dado |
| **Ernest** | Otra app que guarda las pruebas para que nadie pueda borrarlas ni cambiarlas |

### Los estados de una IA (columna *Lifecycle* / *Approval*)

```
UNDER_ASSESSMENT ──Submit──▶ PENDING_APPROVAL ──Approve──▶ APPROVED ✅
 (en evaluación)              (esperando)          │
                                                   │ si es HIGH: 1ª firma
                                                   ▼
                                    PENDING_SECOND_APPROVAL ──2ª firma (otra persona)──▶ APPROVED ✅

Desde PENDING_*  ──Reject──▶ REJECTED ❌ ──Submit──▶ vuelta a empezar
Incidente grave  ──────────▶ SUSPENDED ⛔
Nivel PROHIBITED ──────────▶ BLOCKED ⛔ (nunca se puede aprobar)
```

### El semáforo (pestaña *Posture*)

| Color | Significa |
|---|---|
| 🟢 **GREEN** | Aprobada **y** Ernest tiene pruebas guardadas de ella |
| 🟡 **AMBER** | Le falta algo: está esperando aprobación, o Ernest no responde, o tiene un incidente abierto |
| 🔴 **RED** | No debe usarse: rechazada, suspendida, bloqueada, caducada o con un informe de incidente fuera de plazo |

El color de arriba del todo (*Estate roll-up*) es **el peor** de todos: con una sola IA en
rojo, todo sale en rojo.

---

## Antes de empezar

1. Enciende todo siguiendo [00-arrancar.md](00-arrancar.md) (o pide que te lo dejen encendido).
2. Abre en el navegador: **http://localhost:5200**
3. Verás arriba unas pestañas: *AI system registry · Approvals · Policy decisions · Posture ·
   Incidents · Attestations*.
4. En **AI system registry**, abajo, están los 4 sistemas de estas guías. Si no están, pide que
   ejecuten `./scripts/seed.sh` (los da de alta; si ya existen, no los duplica). Debajo de cada
   nombre hay un código largo (el **id**, tipo `5f350cde-8c1b-…`). **Lo vas a necesitar**:
   selecciónalo con doble clic y cópialo (Cmd+C).

> ⚠️ **Importante: estamos en modo de pruebas sin login.** Todo lo que hagas queda firmado
> como `dev-reviewer`. Por eso, cuando una IA de riesgo **HIGH** pida la **segunda firma**,
> Prudence dirá que no, porque "eres la misma persona". **Eso es correcto**: es la regla de
> los cuatro ojos funcionando. Para completar esa aprobación hace falta el modo con login
> (dos usuarios distintos, `alice` y `bob`).

> 🔐 **Modo con login** (si te lo han arrancado así): al abrir la web te pedirá entrar. Usuarios:
> `alice` / `alice` y `bob` / `bob` (pueden registrar, aprobar y rechazar), `auditor` / `auditor`
> (solo puede mirar). Así **sí** puedes completar la doble firma: firma con `alice`, pulsa
> **Sign out**, entra con `bob` y firma otra vez → **APPROVED**. En este modo la pestaña
> *Policy decisions* da error a las personas: es correcto, solo la usan las máquinas.

> 💡 **Refresca la página (Cmd+R) después de cada acción** si algo no se actualiza: algunas
> pestañas solo leen los datos al abrirse.

> 🔁 Algunos journeys cambian el estado de la IA y no hay botón para deshacer. **Sigue el
> orden de cada archivo.** Si quieres empezar de cero, pide que reinicien la base de datos.

---

## Cómo apuntar los resultados

Copia esta tabla en un documento y rellénala:

| Archivo | Journey | Paso | ¿OK? | Qué viste (si falló) | Captura |
|---|---|---|---|---|---|
| 01 | J1 | 3 | ✅ | | |
| 01 | J2 | 2 | ❌ | Salió DENY en vez de APPROVAL REQUIRED | captura-01.png |

**Si algo falla, apunta siempre:** qué hiciste justo antes, qué esperabas, qué viste, y haz una
**captura de pantalla** (Cmd+Shift+4 en Mac). Un fallo bien contado vale oro. 🏆
