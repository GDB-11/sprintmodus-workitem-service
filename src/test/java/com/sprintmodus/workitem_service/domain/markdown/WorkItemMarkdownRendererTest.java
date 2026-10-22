package com.sprintmodus.workitem_service.domain.markdown;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.sprintmodus.workitem_service.domain.markdown.ImportDiagnostic.Code;
import com.sprintmodus.workitem_service.domain.model.Assignment;
import com.sprintmodus.workitem_service.domain.model.AssignmentRole;
import com.sprintmodus.workitem_service.domain.model.Comment;
import com.sprintmodus.workitem_service.domain.model.ItemType;
import com.sprintmodus.workitem_service.domain.model.Priority;
import com.sprintmodus.workitem_service.domain.model.StatusRef;
import com.sprintmodus.workitem_service.domain.model.UserRef;
import com.sprintmodus.workitem_service.domain.model.WorkItem;

class WorkItemMarkdownRendererTest {

	private static final UserRef ANA = new UserRef(UUID.randomUUID(), "Ana Díaz");

	@Test
	void writesSpanishAndNestsByDepth() {
		ExportNode task = node(ItemType.TASK, 1002, "Crear plantilla", Priority.LOW, 0, "3", null, null, List.of(), List.of());
		ExportNode pbi = node(ItemType.PBI, 1001, "Recibir correo", Priority.HIGH, 5, "0", "Los criterios:\n- [ ] Llega en 1 minuto\nSegundo",
				"Sprint 3", List.of(task), List.of());
		ExportNode epic = node(ItemType.EPIC, 1000, "Incorporación", Priority.MEDIUM, 0, "0", "Objetivo.", null, List.of(pbi), List.of());

		String md = WorkItemMarkdownRenderer.render(epic, false);

		assertThat(md).contains("# Épica: Incorporación\n- clave: WAR-1000\n- estado: Nuevo\n- prioridad: media\n");
		assertThat(md).contains("## Elemento del backlog: Recibir correo").contains("- puntos: 5").contains("- sprint: Sprint 3");
		assertThat(md).contains("### Tarea: Crear plantilla").contains("- horas: 3");
		assertThat(md).contains("**Criterios de aceptación**\n- [ ] Los criterios:\n- [ ] Llega en 1 minuto\n- [ ] Segundo");
		assertThat(md).doesNotContain("Historial");
	}

	@Test
	void whatItWritesIsReadBackWithoutErrorsAndWithTheSameShape() {
		ExportNode task = node(ItemType.TASK, 1002, "Crear plantilla", Priority.LOW, 0, "3", null, null, List.of(), List.of());
		ExportNode pbi = node(ItemType.PBI, 1001, "Recibir correo", Priority.HIGH, 5, "0", "Como usuario quiero algo.", "Sprint 3", List.of(task),
				List.of(new Comment(UUID.randomUUID(), UUID.randomUUID(), ANA, "Ojo con esto", Instant.parse("2026-10-21T10:00:00Z"))));

		ParseResult parsed = WorkItemMarkdownParser.parse(WorkItemMarkdownRenderer.render(pbi, false), ItemType.PBI);

		assertThat(parsed.hasErrors()).isFalse();
		assertThat(parsed.diagnostics()).extracting(ImportDiagnostic::code).contains(Code.KEY_IGNORED, Code.SECTION_IGNORED);
		ParsedItem root = parsed.roots().getFirst();
		assertThat(root.title()).isEqualTo("Recibir correo");
		assertThat(root.points()).isEqualByComparingTo("5");
		assertThat(root.priority()).isEqualTo(Priority.HIGH);
		assertThat(root.children()).extracting(ParsedItem::title).containsExactly("Crear plantilla");
		assertThat(root.children().getFirst().hours()).isEqualByComparingTo("3");
	}

	@Test
	void userTextCannotAddItemsWhenImportedAgain() {
		ExportNode feature = node(ItemType.FEATURE, 1000, "Una", Priority.MEDIUM, 0, "0", "Texto\n# Épica: Intrusa\n**Tareas**\n- Falsa", null, List.of(),
				List.of());

		ParseResult parsed = WorkItemMarkdownParser.parse(WorkItemMarkdownRenderer.render(feature, false), ItemType.FEATURE);

		assertThat(parsed.hasErrors()).isFalse();
		assertThat(parsed.count()).isEqualTo(1);
	}

	@Test
	void saysSoWhenItWasTruncated() {
		ExportNode epic = node(ItemType.EPIC, 1000, "E", Priority.MEDIUM, 0, "0", null, null, List.of(), List.of());

		assertThat(WorkItemMarkdownRenderer.render(epic, true)).contains("truncó");
		assertThat(WorkItemMarkdownRenderer.render(epic, false)).doesNotContain("truncó");
	}

	private static ExportNode node(ItemType type, long number, String title, Priority priority, int points, String hours, String text, String sprint,
			List<ExportNode> children, List<Comment> comments) {
		UUID code = UUID.randomUUID();
		boolean pbi = type == ItemType.PBI;
		String description = text;
		String criteria = null;
		if (text != null && text.startsWith("Los criterios")) {
			description = null;
			criteria = text;
		}
		WorkItem item = new WorkItem(code, number, UUID.randomUUID(), "WAR", type, title, description, criteria, priority,
				new StatusRef("NEW", "Nuevo", true, false), null, null, points, new BigDecimal(hours), new BigDecimal(hours), ANA, null,
				Instant.now(), Instant.now());
		List<Assignment> assignees = pbi ? List.of(new Assignment(UUID.randomUUID(), ANA.userCode(), ANA.fullName(), AssignmentRole.DEV))
				: List.of();
		return new ExportNode(item, "Nuevo", sprint, assignees, List.of(), comments, children);
	}

}
