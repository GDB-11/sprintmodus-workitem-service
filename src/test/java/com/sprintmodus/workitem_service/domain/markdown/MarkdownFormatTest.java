package com.sprintmodus.workitem_service.domain.markdown;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.sprintmodus.workitem_service.domain.markdown.MarkdownFormat.Context;
import com.sprintmodus.workitem_service.domain.markdown.MarkdownFormat.Field;
import com.sprintmodus.workitem_service.domain.model.ItemType;
import com.sprintmodus.workitem_service.domain.model.Priority;

/** The spec table against the golden fixtures ({@code planning/MARKDOWN_IMPORT_TEMPLATES*.md}, copied to test resources). */
class MarkdownFormatTest {

	private static final Pattern FENCE = Pattern.compile("(?s)````markdown\\n(.*?)\\n````");
	private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
	private static final Pattern HEADING = Pattern.compile("^(#+)\\s+([^:]+):.*$");
	private static final Pattern METADATA = Pattern.compile("^-\\s+([^:\\[]+):\\s*\\S.*$");
	private static final Pattern SECTION = Pattern.compile("^\\*\\*([^*]+)\\*\\*$");

	@Test
	void thereAreTenContextsInTheOrderOfTheFixtures() {
		assertThat(MarkdownFormat.contexts()).extracting(c -> c.root() + ":" + c.levels()).containsExactly(
				"EPIC:1", "EPIC:2", "EPIC:3", "EPIC:4", "FEATURE:1", "FEATURE:2", "FEATURE:3", "PBI:1", "PBI:2", "TASK:1");
	}

	@Test
	void theScreenDecidesTheRoots() {
		assertThat(MarkdownFormat.rootFor(null)).contains(ItemType.EPIC);
		assertThat(MarkdownFormat.rootFor(ItemType.EPIC)).contains(ItemType.FEATURE);
		assertThat(MarkdownFormat.rootFor(ItemType.FEATURE)).contains(ItemType.PBI);
		assertThat(MarkdownFormat.rootFor(ItemType.PBI)).contains(ItemType.TASK);
		assertThat(MarkdownFormat.rootFor(ItemType.TASK)).isEmpty();
		assertThat(MarkdownFormat.rootFor(ItemType.BUG)).isEmpty();
	}

	@Test
	void keywordsKeysAndPrioritiesAreMatchedInBothLanguagesIgnoringCaseAndAccents() {
		assertThat(MarkdownFormat.typeOf("EPICA")).contains(ItemType.EPIC);
		assertThat(MarkdownFormat.typeOf("epic")).contains(ItemType.EPIC);
		assertThat(MarkdownFormat.typeOf("Caracteristica")).contains(ItemType.FEATURE);
		assertThat(MarkdownFormat.typeOf("pbi")).contains(ItemType.PBI);
		assertThat(MarkdownFormat.typeOf("Elemento del backlog")).contains(ItemType.PBI);
		assertThat(MarkdownFormat.typeOf("Error")).isEmpty();
		assertThat(MarkdownFormat.isBugKeyword("bug")).isTrue();
		assertThat(MarkdownFormat.fieldOf("Responsable")).contains(Field.ASSIGNEE);
		assertThat(MarkdownFormat.fieldOf("Priority")).contains(Field.PRIORITY);
		assertThat(MarkdownFormat.fieldOf("estado")).isEmpty();
		assertThat(MarkdownFormat.priorityOf("Crítica")).contains(Priority.CRITICAL);
		assertThat(MarkdownFormat.priorityOf("critica")).contains(Priority.CRITICAL);
		assertThat(MarkdownFormat.priorityOf("urgent")).isEmpty();
		assertThat(MarkdownFormat.priorityWord(Priority.HIGH)).isEqualTo("alta");
		assertThat(MarkdownFormat.isAcceptanceCriteria("acceptance CRITERIA")).isTrue();
		assertThat(MarkdownFormat.isTaskList("Tareas")).isTrue();
		assertThat(MarkdownFormat.isIgnoredSection("Comentarios")).isTrue();
	}

	@Test
	void everySpanishFixtureUsesOnlyWhatTheTableAllowsAndOnlyTheCanonicalWords() throws IOException {
		check("/markdown/templates.es.md", true);
	}

	@Test
	void everyEnglishFixtureUsesOnlyWhatTheTableAllowsAndOnlyTheEnglishWords() throws IOException {
		check("/markdown/templates.en.md", false);
	}

	private void check(String resource, boolean spanish) throws IOException {
		String text = new String(getClass().getResourceAsStream(resource).readAllBytes(), StandardCharsets.UTF_8);
		List<String> blocks = new ArrayList<>();
		Matcher fence = FENCE.matcher(text);
		while (fence.find()) {
			blocks.add(fence.group(1));
		}
		List<Context> contexts = MarkdownFormat.contexts();
		assertThat(blocks).as("one fenced template per context").hasSameSizeAs(contexts);

		for (int i = 0; i < blocks.size(); i++) {
			Context context = contexts.get(i);
			String where = resource + " template " + (i + 1) + " " + context;
			List<ItemType> allowed = context.types();
			Set<ItemType> seen = new LinkedHashSet<>();
			ItemType current = null;
			boolean metadataOpen = false;

			for (String line : COMMENT.matcher(blocks.get(i)).replaceAll("").split("\n")) {
				Matcher heading = HEADING.matcher(line);
				if (heading.matches()) {
					String keyword = heading.group(2);
					ItemType type = MarkdownFormat.typeOf(keyword).orElseThrow(() -> new AssertionError(where + ": unknown keyword " + keyword));
					assertThat(allowed).as(where + ": " + keyword).contains(type);
					int expectedDepth = allowed.indexOf(type) + 1;
					int rootDepth = heading.group(1).length() - expectedDepth + 1;
					assertThat(rootDepth).as(where + ": heading depth of " + keyword).isEqualTo(1);
					var spec = MarkdownFormat.spec(type);
					if (spanish) {
						assertThat(keyword.strip()).as(where).isEqualTo(spec.keyword());
					} else {
						assertThat(spec.aliases()).as(where).contains(keyword.strip());
					}
					seen.add(type);
					current = type;
					metadataOpen = true;
					continue;
				}
				Matcher metadata = METADATA.matcher(line);
				if (metadataOpen && current != null && metadata.matches()) {
					Field field = MarkdownFormat.fieldOf(metadata.group(1)).orElseThrow(() -> new AssertionError(where + ": unknown key " + metadata.group(1)));
					assertThat(MarkdownFormat.spec(current).fields()).as(where + ": " + field + " on " + current).contains(field);
					continue;
				}
				if (!line.isBlank()) {
					metadataOpen = false;
				}
				Matcher section = SECTION.matcher(line.strip());
				if (section.matches() && current != null) {
					String title = section.group(1);
					if (MarkdownFormat.isAcceptanceCriteria(title)) {
						assertThat(MarkdownFormat.spec(current).acceptanceCriteria()).as(where + ": criteria on " + current).isTrue();
					} else if (MarkdownFormat.isTaskList(title)) {
						assertThat(MarkdownFormat.spec(current).taskList()).as(where + ": task list on " + current).isTrue();
					} else {
						throw new AssertionError(where + ": unknown section " + title);
					}
				}
			}
			// The long form of Tasks (D1 may use only the short list), but every other level must appear
			if (context.root() != ItemType.TASK) {
				assertThat(seen).as(where + ": every level of the context appears").containsExactlyElementsOf(allowed);
			}
		}
	}

}
