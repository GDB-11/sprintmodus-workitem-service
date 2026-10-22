package com.sprintmodus.workitem_service.domain.markdown;

import java.util.List;

public record ParseResult(List<ParsedItem> roots, List<ImportDiagnostic> diagnostics) {

	public boolean hasErrors() {
		return diagnostics.stream().anyMatch(d -> d.severity() == ImportDiagnostic.Severity.ERROR);
	}

	public int count() {
		return roots.stream().mapToInt(ParsedItem::count).sum();
	}

}
