package com.sprintmodus.workitem_service.domain.markdown;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.sprintmodus.workitem_service.domain.markdown.ImportDiagnostic.Code;
import com.sprintmodus.workitem_service.domain.markdown.MarkdownFormat.Context;
import com.sprintmodus.workitem_service.domain.model.ItemType;
import com.sprintmodus.workitem_service.domain.model.Priority;

class WorkItemMarkdownParserTest {

	private static final Pattern FENCE = Pattern.compile("(?s)````markdown\\n(.*?)\\n````");

	// ------------------------------------------------------------------ golden files

	@Test
	void everyShippedTemplateParsesWithoutAnyDiagnostic() throws IOException {
		for (String resource : List.of("/markdown/templates.es.md", "/markdown/templates.en.md")) {
			List<String> blocks = blocks(resource);
			for (int i = 0; i < blocks.size(); i++) {
				Context context = MarkdownFormat.contexts().get(i);
				ParseResult result = WorkItemMarkdownParser.parse(blocks.get(i), context.root());
				assertThat(result.diagnostics()).as(resource + " #" + (i + 1)).isEmpty();
				assertThat(result.roots()).as(resource + " #" + (i + 1)).isNotEmpty().allMatch(root -> root.type() == context.root());
			}
		}
	}

	@Test
	void eachEnglishTemplateAndItsSpanishTwinHaveTheSameShape() throws IOException {
		List<String> es = blocks("/markdown/templates.es.md");
		List<String> en = blocks("/markdown/templates.en.md");
		for (int i = 0; i < es.size(); i++) {
			ItemType root = MarkdownFormat.contexts().get(i).root();
			assertThat(shapes(WorkItemMarkdownParser.parse(en.get(i), root).roots())).as("template #" + (i + 1))
					.isEqualTo(shapes(WorkItemMarkdownParser.parse(es.get(i), root).roots()));
		}
	}

	@Test
	void theFullTemplateIsReadFieldByField() throws IOException {
		ParseResult result = WorkItemMarkdownParser.parse(blocks("/markdown/templates.es.md").get(3), ItemType.EPIC);

		assertThat(result.roots()).extracting(ParsedItem::title).containsExactly("Incorporación autoservicio", "Informes y analítica");
		ParsedItem epic = result.roots().getFirst();
		assertThat(epic.priority()).isEqualTo(Priority.HIGH);
		assertThat(epic.description()).isEqualTo("Objetivo: que los clientes obtengan valor sin hablar con ventas.");
		ParsedItem feature = epic.children().getFirst();
		assertThat(feature.acceptanceCriteria()).containsExactly("El enlace caduca a las 24 horas");
		ParsedItem pbi = feature.children().getFirst();
		assertThat(pbi.type()).isEqualTo(ItemType.PBI);
		assertThat(pbi.points()).isEqualByComparingTo("5");
		assertThat(pbi.sprintName()).isEqualTo("Sprint 3");
		assertThat(pbi.assignee()).isEqualTo("ana@acme.com");
		assertThat(pbi.description()).startsWith("Como nuevo usuario");
		assertThat(pbi.children()).extracting(ParsedItem::title).containsExactly(
				"Crear la plantilla del correo", "Endpoint de generación del token", "Limitar los reenvíos", "Conectar el botón de reenvío");
		assertThat(pbi.children()).extracting(ParsedItem::hours).usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
				.containsExactly(new BigDecimal("3"), new BigDecimal("5"), null, new BigDecimal("2"));
		assertThat(pbi.children().getLast().assignee()).isEqualTo("Luis Pérez");
		assertThat(result.count()).isEqualTo(14);
	}

	@Test
	void aPlainBulletListIsAValidFileOnAPbiScreen() {
		ParseResult result = WorkItemMarkdownParser.parse("- Uno [2,5h]\n- Dos\n", ItemType.TASK);

		assertThat(result.diagnostics()).isEmpty();
		assertThat(result.roots()).extracting(ParsedItem::title).containsExactly("Uno", "Dos");
		assertThat(result.roots().getFirst().hours()).isEqualByComparingTo("2.5");
	}

	// ------------------------------------------------------------------ what an LLM gets slightly wrong

	@Test
	void aFileWrappedInOneCodeFenceIsReadAndLineNumbersStayTrue() {
		ParseResult result = WorkItemMarkdownParser.parse("```markdown\n# Epic: A\n- points: x\n```\n", ItemType.EPIC);

		// "points" is not a key an Epic has; the line is the true one in the file (3)
		assertThat(result.roots()).extracting(ParsedItem::title).containsExactly("A");
		assertThat(result.diagnostics()).singleElement().satisfies(d -> {
			assertThat(d.code()).isEqualTo(Code.KEY_IGNORED);
			assertThat(d.line()).isEqualTo(3);
		});
	}

	@Test
	void boldKeysAccentlessWordsMixedLanguagesAndDecimalCommasAreAccepted() {
		String text = """
				## EPICA: Grande
				**Prioridad:** CRITICA

				### Feature: Algo
				#### pbi: Historia
				- Points: 2,5
				- responsable: ana@acme.com
				""";

		ParseResult result = WorkItemMarkdownParser.parse(text, ItemType.EPIC);

		assertThat(result.diagnostics()).isEmpty();
		ParsedItem epic = result.roots().getFirst();
		assertThat(epic.priority()).isEqualTo(Priority.CRITICAL);
		ParsedItem pbi = epic.children().getFirst().children().getFirst();
		assertThat(pbi.points()).isEqualByComparingTo("2.5");
		assertThat(pbi.assignee()).isEqualTo("ana@acme.com");
	}

	@Test
	void commentsMayBeMultiLineAndDoNotShiftLineNumbers() {
		String text = "<!-- a\nb\nc -->\n# Epic: One\n# Feature: Two\n";

		ParseResult result = WorkItemMarkdownParser.parse(text, ItemType.EPIC);

		assertThat(result.roots().getFirst().line()).isEqualTo(4);
		assertThat(diagnostic(result, Code.ROOT_TYPE_MISMATCH).line()).isEqualTo(5);
	}

	@Test
	void aHashLineInsideACodeBlockOfTheDescriptionIsNotAHeading() {
		String text = "# Epic: One\n\nRun:\n```bash\n# Feature: not a feature\nls\n```\n";

		ParseResult result = WorkItemMarkdownParser.parse(text, ItemType.EPIC);

		assertThat(result.diagnostics()).isEmpty();
		assertThat(result.roots().getFirst().children()).isEmpty();
		assertThat(result.roots().getFirst().description()).contains("# Feature: not a feature");
	}

	@Test
	void anyOtherHeadingIsJustTextOfTheDescription() {
		ParseResult result = WorkItemMarkdownParser.parse("# Epic: One\n## Notes\nSome text\n", ItemType.EPIC);

		assertThat(result.diagnostics()).isEmpty();
		assertThat(result.roots().getFirst().description()).isEqualTo("## Notes\nSome text");
	}

	@Test
	void descriptionIsKeptVerbatimAsText() {
		ParseResult result = WorkItemMarkdownParser.parse("# Epic: <b>Big</b>\n\n<script>alert(1)</script> & more\n", ItemType.EPIC);

		assertThat(result.roots().getFirst().title()).isEqualTo("<b>Big</b>");
		assertThat(result.roots().getFirst().description()).isEqualTo("<script>alert(1)</script> & more");
	}

	// ------------------------------------------------------------------ errors

	@Test
	void anEpicAnywhereButTheRootIsAnError() {
		ParseResult result = WorkItemMarkdownParser.parse("# Epic: A\n## Epic: B\n", ItemType.EPIC);

		assertThat(result.hasErrors()).isTrue();
		assertThat(diagnostic(result, Code.EPIC_NOT_ROOT).line()).isEqualTo(2);
	}

	@Test
	void aRootOfTheWrongTypeForTheScreenIsAnError() {
		ParseResult result = WorkItemMarkdownParser.parse("# Task: T\n", ItemType.FEATURE);

		ImportDiagnostic error = diagnostic(result, Code.ROOT_TYPE_MISMATCH);
		assertThat(error.params()).containsEntry("expected", "FEATURE").containsEntry("found", "TASK");
	}

	@Test
	void anEpicAsRootOfAFeatureScreenSaysSo() {
		assertThat(diagnostic(WorkItemMarkdownParser.parse("# Epic: A\n", ItemType.FEATURE), Code.EPIC_NOT_ROOT)).isNotNull();
	}

	@Test
	void emptyTitlesBadNumbersAndUnknownPrioritiesAreErrorsWithTheirLine() {
		String text = """
				# Epic:
				# Epic: A
				- prioridad: urgente
				# Epic: B
				# Feature: C
				## PBI: D
				- points: -1
				- hours: 3
				## PBI: E
				- points: many
				""";

		ParseResult result = WorkItemMarkdownParser.parse(text, ItemType.EPIC);

		assertThat(result.diagnostics().stream().filter(d -> d.severity() == ImportDiagnostic.Severity.ERROR))
				.extracting(d -> d.code() + "@" + d.line())
				.contains("EMPTY_TITLE@1", "UNKNOWN_PRIORITY@3", "INVALID_NUMBER@7", "INVALID_NUMBER@10");
		// the hours line is not a PBI key: ignored, not an error
		assertThat(diagnostic(result, Code.KEY_IGNORED).line()).isEqualTo(8);
	}

	@Test
	void tooManyItemsStopsTheReadingWithOneError() {
		StringBuilder text = new StringBuilder();
		for (int i = 0; i <= WorkItemMarkdownParser.MAX_ITEMS; i++) {
			text.append("# Epic: E").append(i).append('\n');
		}

		ParseResult result = WorkItemMarkdownParser.parse(text.toString(), ItemType.EPIC);

		assertThat(result.diagnostics()).extracting(ImportDiagnostic::code).containsExactly(Code.TOO_MANY_ITEMS);
	}

	@Test
	void anOversizedFileIsRefusedWithoutReadingIt() {
		ParseResult result = WorkItemMarkdownParser.parse("x".repeat(WorkItemMarkdownParser.MAX_CHARS + 1), ItemType.EPIC);

		assertThat(result.diagnostics()).extracting(ImportDiagnostic::code).containsExactly(Code.FILE_TOO_LARGE);
		assertThat(result.roots()).isEmpty();
	}

	@Test
	void aFileWithNothingInItIsAnError() {
		assertThat(WorkItemMarkdownParser.parse("just some words\n", ItemType.EPIC).diagnostics()).extracting(ImportDiagnostic::code)
				.containsExactly(Code.NO_ITEMS);
	}

	// ------------------------------------------------------------------ warnings

	@Test
	void aBugIsSkippedWithItsWholeSubtree() {
		String text = "# Epic: A\n## Bug: Crash\n### Task: Fix\n## Feature: F\n";

		ParseResult result = WorkItemMarkdownParser.parse(text, ItemType.EPIC);

		assertThat(result.roots().getFirst().children()).extracting(ParsedItem::title).containsExactly("F");
		assertThat(result.diagnostics()).extracting(d -> d.code() + "@" + d.line()).containsExactly("BUG_SKIPPED@2");
		assertThat(WorkItemMarkdownParser.parse("# Error: Falla\n# Epic: A\n", ItemType.EPIC).roots()).hasSize(1);
	}

	@Test
	void criteriaOnAnEpicAreIgnoredWithAWarning() {
		ParseResult result = WorkItemMarkdownParser.parse("# Epic: A\n**Criterios de aceptación**\n- [ ] x\n", ItemType.EPIC);

		assertThat(diagnostic(result, Code.CRITERIA_IGNORED).line()).isEqualTo(2);
		assertThat(result.roots().getFirst().acceptanceCriteria()).isEmpty();
		assertThat(result.roots().getFirst().description()).isEmpty();
		assertThat(result.hasErrors()).isFalse();
	}

	@Test
	void anExportReImportedIgnoresKeyStatusLinksAndComments() {
		String text = """
				# Épica: A
				- key: MOB-1000
				- estado: Aprobado
				- prioridad: alta

				Texto.

				**Enlaces**
				- depende de MOB-1

				**Comentarios**
				- Ana: hola

				## Característica: F
				""";

		ParseResult result = WorkItemMarkdownParser.parse(text, ItemType.EPIC);

		assertThat(result.hasErrors()).isFalse();
		assertThat(result.diagnostics()).extracting(ImportDiagnostic::code).containsExactly(Code.KEY_IGNORED, Code.KEY_IGNORED, Code.SECTION_IGNORED, Code.SECTION_IGNORED);
		ParsedItem epic = result.roots().getFirst();
		assertThat(epic.description()).isEqualTo("Texto.");
		assertThat(epic.priority()).isEqualTo(Priority.HIGH);
		assertThat(epic.children()).hasSize(1);
	}

	@Test
	void aNonRecommendedParentOnlyWarns() {
		ParseResult result = WorkItemMarkdownParser.parse("# Feature: F\n## Task: T\n", ItemType.FEATURE);

		assertThat(result.hasErrors()).isFalse();
		assertThat(diagnostic(result, Code.NON_STANDARD_HIERARCHY).params()).containsEntry("type", "TASK").containsEntry("parent", "FEATURE");
	}

	@Test
	void everyCodeIsEitherAnErrorOrAWarningAndTheFrontendListIsComplete() {
		assertThat(Code.values()).hasSize(17);
	}

	// ------------------------------------------------------------------ helpers

	private static ImportDiagnostic diagnostic(ParseResult result, Code code) {
		return result.diagnostics().stream().filter(d -> d.code() == code).findFirst()
				.orElseThrow(() -> new AssertionError("no " + code + " in " + result.diagnostics()));
	}

	private static List<String> blocks(String resource) throws IOException {
		String text = new String(WorkItemMarkdownParserTest.class.getResourceAsStream(resource).readAllBytes(), StandardCharsets.UTF_8);
		List<String> blocks = new ArrayList<>();
		Matcher fence = FENCE.matcher(text);
		while (fence.find()) {
			blocks.add(fence.group(1));
		}
		return blocks;
	}

	/** Everything but the free text: titles and descriptions are translated in the twins. */
	private static List<String> shapes(List<ParsedItem> items) {
		return items.stream().map(item -> item.type() + "|" + item.priority() + "|" + item.points() + "|" + item.hours() + "|"
				+ (item.sprintName() != null) + "|" + (item.assignee() != null) + "|" + (item.description().isEmpty()) + "|"
				+ item.acceptanceCriteria().size() + "|" + shapes(item.children())).toList();
	}

}
