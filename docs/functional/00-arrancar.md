# 00 · Cómo arrancar todo

Prudence necesita varias piezas encendidas. Esta guía te dice **cuáles, en qué orden y cómo
comprobar que van**. Todos los comandos se lanzan desde la carpeta del repo de Prudence.

## Las piezas

| Pieza | Para qué | Puerto | ¿Obligatoria? |
|---|---|---|---|
| **PostgreSQL** | Base de datos de Prudence (modo con login) | 5432 | Solo con login |
| **MongoDB** | Base de datos de Ernest | 27017 | Solo si usas Ernest |
| **Ernest** | Guarda las pruebas (semáforo verde) | 3001 | Recomendada |
| **Keycloak** | Login de usuarios (alice, bob, auditor) | 8081 | Solo con login |
| **OPA** | Decide quién puede hacer qué | 8181 | **Sí** |
| **Prudence backend** | El cerebro | 8099 | **Sí** |
| **Prudence web** | Lo que ves en el navegador | 5200 | **Sí** |

## Elige modo

| | 🅰️ **Sin login** (rápido) | 🅱️ **Con login** (completo) |
|---|---|---|
| Base de datos | Un archivo en `./data` | PostgreSQL |
| Quién firma | Siempre `dev-reviewer` | `alice`, `bob`, `auditor` |
| Doble firma (IAs HIGH) | La 2ª firma **siempre falla** | ✅ Se completa (alice + bob) |
| Pestaña *Policy decisions* | ✅ Funciona | ❌ Da error a personas (normal) |
| Ideal para | La mayoría de journeys | Journeys de doble firma |

> 💡 Puedes tener los dos instalados, pero **solo uno encendido a la vez** (los dos usan el puerto 8099).

---

## Una sola vez (la primera)

- **Keycloak** (solo modo 🅱️): descarga un `.tar.gz` 26.x de
  `github.com/keycloak/keycloak/releases` y descomprímelo **al lado** de la carpeta de Prudence.
- **OPA**: `brew install opa`
- **PostgreSQL y MongoDB**: ya funcionan como servicios y arrancan solos al encender el Mac.
  Si alguno no responde, mira [Si algo falla](#si-algo-falla).

---

## 🅰️ Modo sin login — 4 terminales

Abre una terminal por línea, **en este orden**, y déjala abierta:

```bash
./scripts/opa.sh
```
```bash
cd ../ai-blockchain-provenance && FRONTEND_PORT=5174 ./scripts/dev-local.sh
```
```bash
./scripts/backend.sh
```
```bash
./scripts/ui.sh
```

La 2ª (Ernest) es opcional: sin ella, el semáforo de las IAs aprobadas se queda en 🟡.

## 🅱️ Modo con login — 5 terminales

```bash
./scripts/keycloak.sh
```
```bash
./scripts/opa.sh
```
```bash
cd ../ai-blockchain-provenance && FRONTEND_PORT=5174 ./scripts/dev-local.sh
```
```bash
./scripts/backend-sso.sh
```
```bash
./scripts/ui-sso.sh
```

Espera a que Keycloak diga `started` antes de lanzar `backend-sso.sh` (unos 10 segundos).

## Último paso (los dos modos): cargar las IAs de ejemplo

Con el backend ya arrancado, en otra terminal:

```bash
./scripts/seed.sh
```

Da de alta las 4 IAs de las guías. Si ya existen, no las duplica.

---

## ¿Está todo encendido?

| Comprueba | Debería decir |
|---|---|
| `curl -s localhost:8099/actuator/health` | `"status":"UP"` |
| `curl -s localhost:8181/health` | `{}` |
| `curl -s localhost:3001/health` | algo con `ok` / `healthy` |
| `mongosh --quiet --eval "db.runCommand({ping:1}).ok"` | `1` |
| Abrir **http://localhost:5200** | La web de Prudence (en 🅱️, primero el login) |
| Abrir **http://localhost:8081** (🅱️) | La consola de Keycloak (`admin` / `admin`) |

## Apagar

En cada terminal: **Ctrl + C**. PostgreSQL y MongoDB se quedan encendidos (no molestan).

⚠️ Al parar Ernest con Ctrl + C **se borran sus datos** (es una base de usar y tirar).

## Empezar de cero (borrar todo lo que hayas probado)

Con el backend **parado**:

| Modo | Comando | Después |
|---|---|---|
| 🅰️ | `rm -rf data` | Arranca y `./scripts/seed.sh` |
| 🅱️ | `psql -h 127.0.0.1 -d postgres -c 'drop database prudence_sso'` | Arranca y `./scripts/seed.sh` |

---

## Si algo falla

| Síntoma | Solución |
|---|---|
| `port XXXX is already in use` | Ya hay algo en ese puerto. ¿Lo dejaste abierto en otra terminal? Ciérralo |
| `opa not found` | `brew install opa` |
| `Keycloak not found` | Descárgalo (ver *Una sola vez*) |
| `Keycloak issuer not reachable` | Arranca primero `./scripts/keycloak.sh` y espera a `started` |
| `PostgreSQL not reachable` | `brew services restart postgresql@16` |
| MongoDB no responde | `brew services restart mongodb-community` |
| El backend en 🅰️ peta con `Columna … no encontrada` | La base es de una versión vieja: haz *Empezar de cero* |
| En *Policy decisions* todo sale DENY | OPA no está encendido |
| El semáforo nunca pasa a 🟢 | Ernest no está encendido, o espera 1 minuto y refresca |
