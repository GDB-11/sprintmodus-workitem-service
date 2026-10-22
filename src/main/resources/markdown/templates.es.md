# Plantillas de importación Markdown (versión en español, la que se entrega)

Estas 10 plantillas son **lo que descarga el usuario** y **el formato que escribe la exportación**: siempre en español. Son el equivalente exacto, palabra por palabra, de las plantillas en inglés de `planning/MARKDOWN_IMPORT_TEMPLATES.md`, que el importador acepta igual. Las reglas y el vocabulario completo (español / inglés) están en ese archivo; las plantillas descargadas empiezan con un comentario `<!-- ... -->` que explica el uso y se ignora al importar.

Vocabulario usado aquí: `Épica`, `Característica`, `Elemento del backlog` (también se acepta `PBI`), `Tarea`; claves `prioridad` (`baja | media | alta | crítica`), `puntos`, `horas`, `sprint`, `asignado`; secciones `**Criterios de aceptación**` y `**Tareas**`.

---

# A. Desde `/work-items` (las raíces son Épicas; sin elemento superior)

## A1. Solo Épicas

````markdown
<!-- Plantilla de Sprintmodus: Épicas. Borra las líneas opcionales que no necesites. -->

# Épica: Incorporación autoservicio
- prioridad: alta            <!-- opcional: baja | media | alta | crítica -->

Objetivo: que los clientes obtengan valor sin hablar con ventas.
Valor de negocio: menos demostraciones y activación más rápida.

# Épica: Informes y analítica
<!-- Un título solo es válido. -->

# Épica: API pública
- prioridad: media

Permitir que los clientes integren Sprintmodus con sus propias herramientas.
````

## A2. Épicas + Características

````markdown
<!-- Plantilla de Sprintmodus: Épicas y Características. Borra las líneas opcionales que no necesites. -->

# Épica: Incorporación autoservicio
- prioridad: alta

Objetivo: que los clientes obtengan valor sin hablar con ventas.

## Característica: Verificación de correo
- prioridad: media

El registro envía un correo de verificación; las cuentas sin verificar tienen límites.

**Criterios de aceptación**
- [ ] El enlace caduca a las 24 horas
- [ ] Un usuario sin verificar no puede invitar a otros

## Característica: Asistente de configuración guiada
<!-- Característica con solo título. -->

## Característica: Proyecto de ejemplo
- prioridad: baja

Crea un proyecto de demostración en el primer acceso.

# Épica: Informes y analítica

## Característica: Exportar a CSV
````

## A3. Épicas + Características + Elementos del backlog

````markdown
<!-- Plantilla de Sprintmodus: Épicas, Características y Elementos del backlog. Borra las líneas opcionales que no necesites. -->

# Épica: Incorporación autoservicio
- prioridad: alta

Objetivo: que los clientes obtengan valor sin hablar con ventas.

## Característica: Verificación de correo
- prioridad: media

El registro envía un correo de verificación.

**Criterios de aceptación**
- [ ] El enlace caduca a las 24 horas

### Elemento del backlog: Recibir un correo de verificación
- prioridad: alta
- puntos: 5                  <!-- opcional -->
- sprint: Sprint 3           <!-- opcional, por nombre -->
- asignado: ana@acme.com     <!-- opcional, correo o nombre completo -->

Como nuevo usuario quiero recibir un correo de verificación para activar mi cuenta.

**Criterios de aceptación**
- [ ] El correo llega en menos de 1 minuto
- [ ] El correo está en el idioma de la organización

### Elemento del backlog: Reenviar el correo de verificación
<!-- Sin estimación, sprint ni responsable. -->

Como usuario sin verificar quiero pedir otro correo.

### Elemento del backlog: Verificar mediante enlace
- puntos: 3

## Característica: Asistente de configuración guiada

### Elemento del backlog: Elegir una plantilla de proyecto
- puntos: 8
````

## A4. Épicas + Características + Elementos del backlog + Tareas (plantilla completa)

````markdown
<!-- Plantilla de Sprintmodus: jerarquía completa. Borra las líneas opcionales que no necesites. -->

# Épica: Incorporación autoservicio
- prioridad: alta

Objetivo: que los clientes obtengan valor sin hablar con ventas.

## Característica: Verificación de correo
- prioridad: media

El registro envía un correo de verificación.

**Criterios de aceptación**
- [ ] El enlace caduca a las 24 horas

### Elemento del backlog: Recibir un correo de verificación
- prioridad: alta
- puntos: 5
- sprint: Sprint 3
- asignado: ana@acme.com

Como nuevo usuario quiero recibir un correo de verificación para activar mi cuenta.

**Criterios de aceptación**
- [ ] El correo llega en menos de 1 minuto

**Tareas**
- Crear la plantilla del correo [3h]
- Endpoint de generación del token [5h]
- Limitar los reenvíos                 <!-- tarea sin horas -->

#### Tarea: Conectar el botón de reenvío
- horas: 2
- asignado: Luis Pérez

Reutilizar el componente de botón compartido.

### Elemento del backlog: Verificar mediante enlace
- puntos: 3

#### Tarea: Página de verificación
- horas: 4

#### Tarea: Gestionar enlaces caducados
<!-- Tarea con solo título. -->

## Característica: Asistente de configuración guiada

### Elemento del backlog: Elegir una plantilla de proyecto
<!-- Sin puntos y sin tareas. -->

# Épica: Informes y analítica

## Característica: Exportar a CSV
````

---

# B. Desde la pantalla de una Épica (las raíces son Características; puede haber varias)

## B1. Solo Características

````markdown
<!-- Plantilla de Sprintmodus: Características. Borra las líneas opcionales que no necesites. -->

# Característica: Verificación de correo
- prioridad: media

El registro envía un correo de verificación; las cuentas sin verificar tienen límites.

**Criterios de aceptación**
- [ ] El enlace caduca a las 24 horas

# Característica: Asistente de configuración guiada
<!-- Solo título. -->

# Característica: Proyecto de ejemplo
- prioridad: baja

Crea un proyecto de demostración en el primer acceso.
````

## B2. Características + Elementos del backlog

````markdown
<!-- Plantilla de Sprintmodus: Características y Elementos del backlog. Borra las líneas opcionales que no necesites. -->

# Característica: Verificación de correo
- prioridad: media

El registro envía un correo de verificación.

**Criterios de aceptación**
- [ ] El enlace caduca a las 24 horas

## Elemento del backlog: Recibir un correo de verificación
- prioridad: alta
- puntos: 5
- sprint: Sprint 3
- asignado: ana@acme.com

Como nuevo usuario quiero recibir un correo de verificación para activar mi cuenta.

**Criterios de aceptación**
- [ ] El correo llega en menos de 1 minuto

## Elemento del backlog: Reenviar el correo de verificación
<!-- Sin estimación. -->

# Característica: Asistente de configuración guiada

## Elemento del backlog: Elegir una plantilla de proyecto
- puntos: 8
````

## B3. Características + Elementos del backlog + Tareas

````markdown
<!-- Plantilla de Sprintmodus: Características, Elementos del backlog y Tareas. Borra las líneas opcionales que no necesites. -->

# Característica: Verificación de correo
- prioridad: media

El registro envía un correo de verificación.

**Criterios de aceptación**
- [ ] El enlace caduca a las 24 horas

## Elemento del backlog: Recibir un correo de verificación
- prioridad: alta
- puntos: 5
- sprint: Sprint 3
- asignado: ana@acme.com

Como nuevo usuario quiero recibir un correo de verificación para activar mi cuenta.

**Criterios de aceptación**
- [ ] El correo llega en menos de 1 minuto

**Tareas**
- Crear la plantilla del correo [3h]
- Endpoint de generación del token [5h]
- Limitar los reenvíos

### Tarea: Conectar el botón de reenvío
- horas: 2
- asignado: Luis Pérez

Reutilizar el componente de botón compartido.

## Elemento del backlog: Verificar mediante enlace
- puntos: 3

### Tarea: Página de verificación
- horas: 4

# Característica: Asistente de configuración guiada

## Elemento del backlog: Elegir una plantilla de proyecto
````

---

# C. Desde la pantalla de una Característica (las raíces son Elementos del backlog)

## C1. Solo Elementos del backlog

````markdown
<!-- Plantilla de Sprintmodus: Elementos del backlog. Borra las líneas opcionales que no necesites. -->

# Elemento del backlog: Recibir un correo de verificación
- prioridad: alta
- puntos: 5
- sprint: Sprint 3
- asignado: ana@acme.com

Como nuevo usuario quiero recibir un correo de verificación para activar mi cuenta.

**Criterios de aceptación**
- [ ] El correo llega en menos de 1 minuto
- [ ] El correo está en el idioma de la organización

# Elemento del backlog: Reenviar el correo de verificación
<!-- Sin puntos, sprint ni responsable. -->

Como usuario sin verificar quiero pedir otro correo.

# Elemento del backlog: Verificar mediante enlace
- puntos: 3
````

## C2. Elementos del backlog + Tareas

````markdown
<!-- Plantilla de Sprintmodus: Elementos del backlog y Tareas. Borra las líneas opcionales que no necesites. -->

# Elemento del backlog: Recibir un correo de verificación
- prioridad: alta
- puntos: 5
- sprint: Sprint 3
- asignado: ana@acme.com

Como nuevo usuario quiero recibir un correo de verificación para activar mi cuenta.

**Criterios de aceptación**
- [ ] El correo llega en menos de 1 minuto

**Tareas**
- Crear la plantilla del correo [3h]
- Endpoint de generación del token [5h]
- Limitar los reenvíos

## Tarea: Conectar el botón de reenvío
- horas: 2
- asignado: Luis Pérez

Reutilizar el componente de botón compartido.

# Elemento del backlog: Verificar mediante enlace
- puntos: 3

## Tarea: Página de verificación
- horas: 4

# Elemento del backlog: Reenviar el correo de verificación
<!-- Elemento sin tareas todavía. -->
````

---

# D. Desde la pantalla de un Elemento del backlog (las raíces son Tareas)

## D1. Solo Tareas

Dos formas equivalentes, que se pueden mezclar en un mismo archivo.

````markdown
<!-- Plantilla de Sprintmodus: Tareas. Borra las líneas opcionales que no necesites. -->

<!-- Forma corta: una lista simple; cada viñeta es una Tarea. -->
- Crear la plantilla del correo [3h]
- Endpoint de generación del token [5h]
- Limitar los reenvíos

<!-- Forma larga: un encabezado por tarea. -->
# Tarea: Conectar el botón de reenvío
- prioridad: media
- horas: 2
- asignado: Luis Pérez

Reutilizar el componente de botón compartido.

# Tarea: Gestionar enlaces caducados
<!-- Solo título. -->
````
