package com.sprintmodus.workitem_service.adapter.rest.dto;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sprintmodus.workitem_service.application.service.ImportWorkItemsUseCase.Outcome;
import com.sprintmodus.workitem_service.domain.markdown.ImportDiagnostic;
import com.sprintmodus.workitem_service.domain.markdown.ParsedItem;
import com.sprintmodus.workitem_service.domain.model.ItemType;
import com.sprintmodus.workitem_service.domain.model.Priority;

/** The JSON of an import (or its dry run): the tree as it would be created, counts per type and the diagnostics. */
public final class ImportResponses {

	private ImportResponses() {
	}

	public record Preview(boolean dryRun, boolean imported, int total, Map<ItemType, Integer> counts, List<Item> items,
			List<Diagnostic> diagnostics, List<Created> created) {

		public static Preview from(Outcome outcome) {
			Map<ItemType, Integer> counts = new EnumMap<>(ItemType.class);
			outcome.items().forEach(item -> count(item, counts));
			return new Preview(outcome.dryRun(), outcome.imported(), counts.values().stream().mapToInt(Integer::intValue).sum(), counts,
					outcome.items().stream().map(Item::from).toList(), outcome.diagnostics().stream().map(Diagnostic::from).toList(),
					outcome.created().stream().map(root -> new Created(root.workItemCode(), root.displayKey(), root.type(), root.title())).toList());
		}

		private static void count(ParsedItem item, Map<ItemType, Integer> counts) {
			counts.put(item.type(), counts.getOrDefault(item.type(), 0) + 1);
			item.children().forEach(child -> count(child, counts));
		}

	}

	public record Item(ItemType type, String title, int line, Priority priority, BigDecimal points, BigDecimal hours, String sprint,
			String assignee, boolean hasDescription, int criteria, List<Item> children) {

		static Item from(ParsedItem item) {
			return new Item(item.type(), item.title(), item.line(), item.priority(), item.points(), item.hours(), item.sprintName(),
					item.assignee(), item.description() != null && !item.description().isBlank(), item.acceptanceCriteria().size(),
					item.children().stream().map(Item::from).toList());
		}

	}

	public record Diagnostic(String code, String severity, int line, Map<String, String> params) {

		static Diagnostic from(ImportDiagnostic diagnostic) {
			return new Diagnostic(diagnostic.code().name(), diagnostic.severity().name(), diagnostic.line(), diagnostic.params());
		}

	}

	public record Created(UUID workItemCode, String displayKey, ItemType type, String title) {
	}

}
