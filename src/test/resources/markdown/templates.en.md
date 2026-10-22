# Markdown import: the 10 accepted templates

Reference for Phase 22 (`planning/IMPLEMENTATION_WORK_ITEMS.md`). These are the files the backend generates from its spec table and the parser must accept without errors (they are the golden files). The Spanish vocabulary is accepted everywhere on equal terms, and is the one the system shows (see "Languages" below); the examples in this file are the English twins.

## Rules common to every template

- Heading keyword = type (`Epic:`, `Feature:`, `PBI:`, `Task:`), heading depth = nesting. The first heading is the root and may be at any level; below it, a deeper heading is a child.
- Right under a heading, a bullet list of `- key: value`. Allowed keys per type:

| Type | Keys | Sections |
|---|---|---|
| Epic | `priority` | description |
| Feature | `priority` | description, **Acceptance criteria** |
| PBI | `priority`, `points`, `sprint`, `assignee` | description, **Acceptance criteria**, **Tasks** |
| Task | `priority`, `hours`, `sprint`, `assignee` | description |

- `priority`: `low | medium | high | critical`. `points`: number ≥ 0. `hours`: number ≥ 0 (stored as estimated and remaining hours). `sprint`: sprint name in the project (unknown = warning). `assignee`: email, then exact full name (unknown = warning).
- Everything is optional except the title. Delete any line you do not need.
- Text after the metadata, up to the next heading, is the description (free Markdown). `**Acceptance criteria**` followed by `- [ ]` bullets is extracted into its own field (Feature and PBI only; elsewhere ignored with a warning).
- `**Tasks**` followed by bullets is the short form of Task children of a PBI: `- Title [3h]` (`[3h]` optional). A `Task:` heading is the long form, for a task that needs a description or an assignee. Both can be mixed under one PBI.
- `<!-- comments -->` are ignored. Status is never read: everything starts in the initial status.
- Ignored with a warning: `key:` lines, `**Links**`, `**Comments**` (an export re-imported), and any `Bug:` heading.
- Errors that block the import: an Epic anywhere but the root, a root of the wrong type for the screen, an empty title, negative or non-numeric points/hours, an unknown priority, more than ~200 items.

## Languages: Spanish and English are equal on input, Spanish only on output (decided 2026-10-21)

Sprintmodus is a Spanish platform, so every template has a Spanish twin (`planning/MARKDOWN_IMPORT_TEMPLATES.es.md`, the one actually shipped). The parser treats the two vocabularies as **exactly equivalent** and decides **per token, not per file**, so a file may even mix them (an LLM often does). Matching is case-insensitive and accent-insensitive (`Épica` = `epica`), and trailing `:` after the type keyword is required in both.

| Concept | Spanish (canonical, what is shipped) | English (also accepted) |
|---|---|---|
| Types | `Épica`, `Característica`, `Elemento del backlog` (alias `PBI`), `Tarea` | `Epic`, `Feature`, `PBI`, `Task` |
| Keys | `prioridad`, `puntos`, `horas`, `sprint`, `asignado` (alias `responsable`) | `priority`, `points`, `hours`, `sprint`, `assignee` |
| Priority values | `baja`, `media`, `alta`, `crítica` | `low`, `medium`, `high`, `critical` |
| Sections | `**Criterios de aceptación**`, `**Tareas**` | `**Acceptance criteria**`, `**Tasks**` |
| Ignored sections | `**Enlaces**`, `**Comentarios**`, heading `Error:` | `**Links**`, `**Comments**`, heading `Bug:` |
| Compact estimate | `[3h]` | `[3h]` |

Numbers accept `.` or `,` as the decimal separator (`2,5` = `2.5`). **Output is always Spanish**: the downloaded templates, the AI-instructions prompt and the Markdown export use the Spanish vocabulary (the export writes numbers with `.`). Import diagnostics carry a code and parameters; the frontend renders them in Spanish. The English twins of the 10 templates below are accepted input and live as test fixtures; a test asserts that each English template and its Spanish twin parse to identical trees.

---

# A. From `/work-items` (roots are Epics; no parent)

## A1. Epics only

````markdown
# Epic: Self-service onboarding
- priority: high            <!-- optional: low | medium | high | critical -->

Objective: customers reach value without talking to sales.
Business value: fewer demos, faster activation.

# Epic: Reporting and analytics
<!-- A title alone is valid. -->

# Epic: Public API
- priority: medium

Let customers integrate Sprintmodus with their own tools.
````

## A2. Epics + Features

````markdown
# Epic: Self-service onboarding
- priority: high

Objective: customers reach value without talking to sales.

## Feature: Email verification
- priority: medium

Sign-up sends a verification email; unverified accounts are limited.

**Acceptance criteria**
- [ ] The link expires after 24 hours
- [ ] An unverified user cannot invite others

## Feature: Guided setup wizard
<!-- Feature with only a title. -->

## Feature: Sample project
- priority: low

Creates a demo project on first login.

# Epic: Reporting and analytics

## Feature: Export to CSV
````

## A3. Epics + Features + PBIs

````markdown
# Epic: Self-service onboarding
- priority: high

Objective: customers reach value without talking to sales.

## Feature: Email verification
- priority: medium

Sign-up sends a verification email.

**Acceptance criteria**
- [ ] The link expires after 24 hours

### PBI: Receive a verification email
- priority: high
- points: 5                 <!-- optional -->
- sprint: Sprint 3          <!-- optional, by name -->
- assignee: ana@acme.com    <!-- optional, email or full name -->

Como nuevo usuario quiero recibir un correo de verificación para activar mi cuenta.

**Acceptance criteria**
- [ ] The email arrives within 1 minute
- [ ] The email is in the organization's language

### PBI: Resend the verification email
<!-- PBI without estimate, sprint or assignee. -->

Como usuario sin verificar quiero pedir otro correo.

### PBI: Verify by link
- points: 3

## Feature: Guided setup wizard

### PBI: Choose a project template
- points: 8
````

## A4. Epics + Features + PBIs + Tasks (the full template)

````markdown
# Epic: Self-service onboarding
- priority: high

Objective: customers reach value without talking to sales.

## Feature: Email verification
- priority: medium

Sign-up sends a verification email.

**Acceptance criteria**
- [ ] The link expires after 24 hours

### PBI: Receive a verification email
- priority: high
- points: 5
- sprint: Sprint 3
- assignee: ana@acme.com

Como nuevo usuario quiero recibir un correo de verificación para activar mi cuenta.

**Acceptance criteria**
- [ ] The email arrives within 1 minute

**Tasks**
- Create the email template [3h]
- Token generation endpoint [5h]
- Resend throttling                   <!-- task without hours -->

#### Task: Wire the resend button
- hours: 2
- assignee: Luis Pérez

Only needed after the endpoint exists; reuse the shared button component.

### PBI: Verify by link
- points: 3

#### Task: Verification page
- hours: 4

#### Task: Handle expired links
<!-- Task with only a title. -->

## Feature: Guided setup wizard

### PBI: Choose a project template
<!-- PBI without points and without tasks. -->

# Epic: Reporting and analytics

## Feature: Export to CSV
````

---

# B. From an Epic's screen (roots are Features; many allowed)

## B1. Features only

````markdown
# Feature: Email verification
- priority: medium

Sign-up sends a verification email; unverified accounts are limited.

**Acceptance criteria**
- [ ] The link expires after 24 hours

# Feature: Guided setup wizard
<!-- Title only. -->

# Feature: Sample project
- priority: low

Creates a demo project on first login.
````

## B2. Features + PBIs

````markdown
# Feature: Email verification
- priority: medium

Sign-up sends a verification email.

**Acceptance criteria**
- [ ] The link expires after 24 hours

## PBI: Receive a verification email
- priority: high
- points: 5
- sprint: Sprint 3
- assignee: ana@acme.com

Como nuevo usuario quiero recibir un correo de verificación para activar mi cuenta.

**Acceptance criteria**
- [ ] The email arrives within 1 minute

## PBI: Resend the verification email
<!-- No estimate. -->

# Feature: Guided setup wizard

## PBI: Choose a project template
- points: 8
````

## B3. Features + PBIs + Tasks

````markdown
# Feature: Email verification
- priority: medium

Sign-up sends a verification email.

**Acceptance criteria**
- [ ] The link expires after 24 hours

## PBI: Receive a verification email
- priority: high
- points: 5
- sprint: Sprint 3
- assignee: ana@acme.com

Como nuevo usuario quiero recibir un correo de verificación para activar mi cuenta.

**Acceptance criteria**
- [ ] The email arrives within 1 minute

**Tasks**
- Create the email template [3h]
- Token generation endpoint [5h]
- Resend throttling

### Task: Wire the resend button
- hours: 2
- assignee: Luis Pérez

Reuse the shared button component.

## PBI: Verify by link
- points: 3

### Task: Verification page
- hours: 4

# Feature: Guided setup wizard

## PBI: Choose a project template
````

---

# C. From a Feature's screen (roots are PBIs)

## C1. PBIs only

````markdown
# PBI: Receive a verification email
- priority: high
- points: 5
- sprint: Sprint 3
- assignee: ana@acme.com

Como nuevo usuario quiero recibir un correo de verificación para activar mi cuenta.

**Acceptance criteria**
- [ ] The email arrives within 1 minute
- [ ] The email is in the organization's language

# PBI: Resend the verification email
<!-- No points, sprint or assignee. -->

Como usuario sin verificar quiero pedir otro correo.

# PBI: Verify by link
- points: 3
````

## C2. PBIs + Tasks

````markdown
# PBI: Receive a verification email
- priority: high
- points: 5
- sprint: Sprint 3
- assignee: ana@acme.com

Como nuevo usuario quiero recibir un correo de verificación para activar mi cuenta.

**Acceptance criteria**
- [ ] The email arrives within 1 minute

**Tasks**
- Create the email template [3h]
- Token generation endpoint [5h]
- Resend throttling

## Task: Wire the resend button
- hours: 2
- assignee: Luis Pérez

Reuse the shared button component.

# PBI: Verify by link
- points: 3

## Task: Verification page
- hours: 4

# PBI: Resend the verification email
<!-- PBI with no tasks yet. -->
````

---

# D. From a PBI's screen (roots are Tasks)

## D1. Tasks only

Two equivalent forms, which can be mixed in one file.

````markdown
<!-- Short form: a plain bullet list; each bullet is a Task. -->
- Create the email template [3h]
- Token generation endpoint [5h]
- Resend throttling

<!-- Long form: one heading per task. -->
# Task: Wire the resend button
- priority: medium
- hours: 2
- assignee: Luis Pérez

Reuse the shared button component.

# Task: Handle expired links
<!-- Title only. -->
````
