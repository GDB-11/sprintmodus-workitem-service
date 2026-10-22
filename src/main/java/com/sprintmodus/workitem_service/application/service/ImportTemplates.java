package com.sprintmodus.workitem_service.application.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.sprintmodus.workitem_service.domain.markdown.MarkdownFormat;
import com.sprintmodus.workitem_service.domain.markdown.MarkdownFormat.Context;
import com.sprintmodus.workitem_service.domain.model.ItemType;

/**
 * The Markdown templates a user downloads and the prompt they paste into an LLM, always in Spanish. The 10 templates are
 * the fenced blocks of {@code markdown/templates.es.md}, in the order of {@link MarkdownFormat#contexts()}; the spec table
 * test checks every one of them against the table, so they cannot drift from the parser.
 */
@Service
public class ImportTemplates {

	private static final Pattern FENCE = Pattern.compile("(?s)````markdown\\n(.*?)\\n````");

	private final List<String> templates;

	public ImportTemplates() {
		this.templates = load();
	}

	/** The template for a file whose roots are {@code root} and that includes {@code levels} types; empty for a combination that has none. */
	public Optional<String> template(ItemType root, int levels) {
		List<Context> contexts = MarkdownFormat.contexts();
		for (int i = 0; i < contexts.size(); i++) {
			if (contexts.get(i).root() == root && contexts.get(i).levels() == levels) {
				return Optional.of(templates.get(i) + "\n");
			}
		}
		return Optional.empty();
	}

	/** The prompt for an LLM: the rules of the format, the template of the context and what to answer with. */
	public Optional<String> aiInstructions(ItemType root, int levels) {
		return template(root, levels).map(template -> """
				Eres un asistente de planificación de proyectos. Con base en el resumen que te doy al final, escribe un archivo \
				Markdown para importar en Sprintmodus.

				Reglas del formato:
				- Cada elemento es un encabezado con su tipo: `Épica:`, `Característica:`, `Elemento del backlog:` o `Tarea:`, \
				seguido del título. El nivel del encabezado (#, ##, ###, ####) indica la jerarquía.
				- Justo debajo del encabezado, una lista opcional de líneas `- clave: valor`: `prioridad` (baja | media | alta | crítica), \
				`puntos` (solo en elementos del backlog), `horas` (solo en tareas), `sprint`, `asignado`.
				- El texto que sigue es la descripción.
				- `**Criterios de aceptación**` con una lista de `- [ ] criterio`, solo en características y elementos del backlog.
				- Todo es opcional salvo el título. No escribas estado ni identificadores.
				- Responde únicamente con el contenido del archivo, sin explicaciones ni bloques de código alrededor.

				Ejemplo del formato esperado:

				%s
				Mi resumen del proyecto:
				""".formatted(template));
	}

	private static List<String> load() {
		try (var stream = ImportTemplates.class.getResourceAsStream("/markdown/templates.es.md")) {
			String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
			List<String> blocks = new ArrayList<>();
			Matcher fence = FENCE.matcher(text);
			while (fence.find()) {
				blocks.add(fence.group(1));
			}
			if (blocks.size() != MarkdownFormat.contexts().size()) {
				throw new IllegalStateException("templates.es.md must hold one template per context");
			}
			return List.copyOf(blocks);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}
