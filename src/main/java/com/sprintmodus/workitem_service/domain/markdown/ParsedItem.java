package com.sprintmodus.workitem_service.domain.markdown;

import java.math.BigDecimal;
import java.util.List;

import com.sprintmodus.workitem_service.domain.model.ItemType;
import com.sprintmodus.workitem_service.domain.model.Priority;

/**
 * One work item as written in an import file. Sprint and assignee are still as written (the use case resolves them);
 * {@code description} is plain text, never interpreted as HTML.
 *
 * @param line the line of its heading (or bullet) in the file
 */
public record ParsedItem(ItemType type, String title, int line, Priority priority, BigDecimal points, BigDecimal hours, String sprintName,
		String assignee, String description, List<String> acceptanceCriteria, List<ParsedItem> children) {

	public int count() {
		return 1 + children.stream().mapToInt(ParsedItem::count).sum();
	}

}
