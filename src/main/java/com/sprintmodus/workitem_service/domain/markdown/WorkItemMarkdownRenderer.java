package com.sprintmodus.workitem_service.domain.markdown;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.sprintmodus.workitem_service.domain.markdown.MarkdownFormat.Field;
import com.sprintmodus.workitem_service.domain.model.ItemType;
import com.sprintmodus.workitem_service.domain.model.Link;
import com.sprintmodus.workitem_service.domain.model.WorkItem;

/**
 * Writes a work item subtree in the Markdown interchange format, always in Spanish. It never branches on the type of the
 * root: it walks whatever children it is given. The audit history is not part of the format. User text is kept as written,
 * except that a line which would read as a heading is escaped, so it cannot add items when the file is imported again.
 */
public final class WorkItemMarkdownRenderer {

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);
	private static final int MAX_HEADING_LEVEL = 6;

	private WorkItemMarkdownRenderer() {
	}

	/** @param truncated whether the caller left items out to stay under its size cap; the document then says so */
	public static String render(ExportNode root, boolean truncated) {
		StringBuilder out = new StringBuilder();
		write(out, root, 1);
		if (truncated) {
			out.append("<!-- El documento se truncó: faltan elementos por el límite de tamaño de la exportación. -->\n");
		}
		return out.toString();
	}

	private static void write(StringBuilder out, ExportNode node, int level) {
		WorkItem item = node.item();
		String keyword = item.type() == ItemType.BUG ? "Error" : MarkdownFormat.spec(item.type()).keyword();
		out.append("#".repeat(Math.min(level, MAX_HEADING_LEVEL))).append(' ').append(keyword).append(": ").append(oneLine(item.title()))
				.append('\n');

		line(out, "clave", item.displayKey());
		if (node.statusName() != null) {
			line(out, "estado", oneLine(node.statusName()));
		}
		line(out, Field.PRIORITY.key(), MarkdownFormat.priorityWord(item.priority()));
		if (item.effortPoints() > 0 && (item.type() == ItemType.PBI || item.type() == ItemType.BUG)) {
			line(out, Field.POINTS.key(), String.valueOf(item.effortPoints()));
		}
		if (item.type() == ItemType.TASK && item.estimatedHours() != null && item.estimatedHours().signum() > 0) {
			line(out, Field.HOURS.key(), number(item.estimatedHours()));
		}
		if (node.sprintName() != null) {
			line(out, Field.SPRINT.key(), oneLine(node.sprintName()));
		}
		String assignees = node.assignees().stream().map(a -> oneLine(a.fullName())).distinct().collect(Collectors.joining(", "));
		if (!assignees.isEmpty()) {
			line(out, Field.ASSIGNEE.key(), assignees);
		}
		out.append('\n');

		if (item.description() != null && !item.description().isBlank()) {
			out.append(escape(item.description().strip())).append("\n\n");
		}
		List<String> criteria = criteria(item.acceptanceCriteria());
		if (!criteria.isEmpty()) {
			out.append("**").append(MarkdownFormat.ACCEPTANCE_CRITERIA).append("**\n");
			criteria.forEach(c -> out.append("- [ ] ").append(c).append('\n'));
			out.append('\n');
		}
		if (!node.links().isEmpty()) {
			out.append("**Enlaces**\n");
			for (Link link : node.links()) {
				out.append("- ").append(LINK_LABELS.get(link.type())).append(' ').append(link.other().displayKey()).append(": ")
						.append(oneLine(link.other().title())).append('\n');
			}
			out.append('\n');
		}
		if (!node.comments().isEmpty()) {
			out.append("**Comentarios**\n");
			node.comments().forEach(c -> out.append("- ").append(oneLine(c.author().fullName())).append(" (").append(DATE.format(c.createdAt()))
					.append("): ").append(oneLine(c.content())).append('\n'));
			out.append('\n');
		}
		for (ExportNode child : node.children()) {
			write(out, child, level + 1);
		}
	}

	private static final java.util.Map<com.sprintmodus.workitem_service.domain.model.LinkType, String> LINK_LABELS = java.util.Map.of(
			com.sprintmodus.workitem_service.domain.model.LinkType.RELATED_TO, "Relacionado con",
			com.sprintmodus.workitem_service.domain.model.LinkType.BLOCKS, "Bloquea a",
			com.sprintmodus.workitem_service.domain.model.LinkType.IS_BLOCKED_BY, "Bloqueado por",
			com.sprintmodus.workitem_service.domain.model.LinkType.DUPLICATES, "Duplica a",
			com.sprintmodus.workitem_service.domain.model.LinkType.IS_DUPLICATED_BY, "Duplicado por");

	private static void line(StringBuilder out, String key, String value) {
		out.append("- ").append(key).append(": ").append(value).append('\n');
	}

	private static String number(BigDecimal value) {
		return value.stripTrailingZeros().toPlainString();
	}

	/** Titles and one-line values cannot span lines. */
	private static String oneLine(String text) {
		return text == null ? "" : text.replaceAll("\\s*\\R\\s*", " ").strip();
	}

	/** A line that starts with {@code #} would be read as a heading, and a bold-only line as a section. */
	private static String escape(String text) {
		return text.lines().map(line -> {
			String stripped = line.stripLeading();
			return stripped.startsWith("#") || stripped.matches("\\*\\*.+?\\*\\*\\s*:?") ? "\\" + line : line;
		}).collect(Collectors.joining("\n"));
	}

	/** The stored criteria are one per line, possibly already written as checkbox bullets. */
	private static List<String> criteria(String stored) {
		List<String> result = new ArrayList<>();
		if (stored == null) {
			return result;
		}
		for (String line : stored.split("\\R")) {
			String text = line.strip().replaceFirst("^(?:[-*+]\\s*)?(?:\\[[ xX]\\]\\s*)?", "").strip();
			if (!text.isEmpty()) {
				result.add(text);
			}
		}
		return result;
	}


}
