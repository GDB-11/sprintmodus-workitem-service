package com.sprintmodus.workitem_service.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.sprintmodus.workitem_service.application.service.ImportTemplates;
import com.sprintmodus.workitem_service.domain.markdown.MarkdownFormat;
import com.sprintmodus.workitem_service.domain.markdown.WorkItemMarkdownParser;
import com.sprintmodus.workitem_service.domain.model.ItemType;

class ImportTemplatesTest {

	private final ImportTemplates templates = new ImportTemplates();

	@Test
	void everyContextHasATemplateThatItsOwnParserReadsWithoutErrors() {
		for (var context : MarkdownFormat.contexts()) {
			String template = templates.template(context.root(), context.levels()).orElseThrow();
			var parsed = WorkItemMarkdownParser.parse(template, context.root());
			assertThat(parsed.hasErrors()).as(context + " " + parsed.diagnostics()).isFalse();
			assertThat(parsed.count()).as(context.toString()).isPositive();
		}
	}

	@Test
	void thereIsNoTemplateForACombinationThatDoesNotExist() {
		assertThat(templates.template(ItemType.TASK, 2)).isEmpty();
		assertThat(templates.template(ItemType.BUG, 1)).isEmpty();
	}

	@Test
	void theAiPromptIsSpanishAndEmbedsTheTemplate() {
		String prompt = templates.aiInstructions(ItemType.FEATURE, 2).orElseThrow();
		assertThat(prompt).contains("Responde únicamente").contains("# Característica").doesNotContain("# Épica:");
	}

}
