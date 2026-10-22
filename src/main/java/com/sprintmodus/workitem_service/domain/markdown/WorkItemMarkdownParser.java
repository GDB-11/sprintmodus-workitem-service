package com.sprintmodus.workitem_service.domain.markdown;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sprintmodus.workitem_service.domain.markdown.ImportDiagnostic.Code;
import com.sprintmodus.workitem_service.domain.markdown.MarkdownFormat.Field;
import com.sprintmodus.workitem_service.domain.markdown.MarkdownFormat.TypeSpec;
import com.sprintmodus.workitem_service.domain.model.ItemType;
import com.sprintmodus.workitem_service.domain.model.Priority;
import com.sprintmodus.workitem_service.domain.service.WorkItemHierarchy;

/**
 * Reads an import file into a tree of {@link ParsedItem}s plus line-numbered diagnostics. Pure: no I/O, no lookups (sprints
 * and assignees stay as written). It is forgiving about what an LLM typically gets wrong (code fences around the whole file,
 * bold keys, accent-less or English words, decimal commas) and strict about structure and numbers. The heading keyword is the
 * type, the heading level is the nesting; see {@link MarkdownFormat}.
 */
public final class WorkItemMarkdownParser {

	public static final int MAX_ITEMS = 200;
	public static final int MAX_CHARS = 512 * 1024;

	private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
	private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");
	private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~).*$");
	private static final Pattern BULLET = Pattern.compile("^\\s*[-*+]\\s+(.*)$");
	private static final Pattern METADATA_BULLET = Pattern.compile("^\\s*[-*+]\\s+\\**([^:*]+?)\\**\\s*:\\**\\s*(.*)$");
	private static final Pattern METADATA_BOLD = Pattern.compile("^\\s*\\*\\*([^:*]+?):\\*\\*\\s*(.*)$");
	private static final Pattern SECTION = Pattern.compile("^\\s*\\*\\*(.+?)\\*\\*\\s*:?\\s*$");
	private static final Pattern CHECKBOX = Pattern.compile("^\\[[ xX]\\]\\s*(.*)$");
	private static final Pattern COMPACT_HOURS = Pattern.compile("^(.*?)\\s*\\[\\s*(\\d+(?:[.,]\\d+)?)\\s*h\\s*\\]\\s*$");
	private static final List<String> NOT_WRITABLE_KEYS = List.of("key", "clave", "status", "estado");

	private enum Section {
		DESCRIPTION, CRITERIA, TASKS, IGNORED
	}

	private final ItemType expectedRoot;
	private final List<ImportDiagnostic> diagnostics = new ArrayList<>();
	private final List<Node> roots = new ArrayList<>();
	private final Deque<Node> stack = new ArrayDeque<>();
	private int items;

	private WorkItemMarkdownParser(ItemType expectedRoot) {
		this.expectedRoot = expectedRoot;
	}

	/** @param expectedRoot the type the roots must have, from the screen the file is imported on */
	public static ParseResult parse(String text, ItemType expectedRoot) {
		return new WorkItemMarkdownParser(expectedRoot).run(text);
	}

	private ParseResult run(String text) {
		if (text.length() > MAX_CHARS) {
			add(Code.FILE_TOO_LARGE, 0, "max", String.valueOf(MAX_CHARS));
			return new ParseResult(List.of(), List.copyOf(diagnostics));
		}
		String[] lines = unwrapFence(blankComments(text.replace("﻿", ""))).split("\r?\n", -1);

		Node current = null;
		Section section = Section.DESCRIPTION;
		boolean metadataOpen = false;
		boolean inCode = false;
		int skipBelow = 0; // while > 0, everything under a skipped (Bug) heading of this level or deeper is dropped

		for (int i = 0; i < lines.length && items <= MAX_ITEMS; i++) {
			String line = lines[i];
			int number = i + 1;

			if (FENCE.matcher(line).matches()) {
				inCode = !inCode;
				if (skipBelow == 0 && current != null) {
					current.description.add(line);
				}
				continue;
			}
			Matcher heading = inCode ? null : HEADING.matcher(line);
			if (heading != null && heading.matches()) {
				int level = heading.group(1).length();
				if (skipBelow > 0 && level > skipBelow) {
					continue;
				}
				skipBelow = 0;
				String[] parts = heading.group(2).split(":", 2);
				String keyword = parts[0].replace("*", "").strip();
				if (parts.length == 2 && MarkdownFormat.isBugKeyword(keyword)) {
					add(Code.BUG_SKIPPED, number);
					skipBelow = level;
					continue;
				}
				Optional<ItemType> type = parts.length == 2 ? MarkdownFormat.typeOf(keyword) : Optional.empty();
				if (type.isPresent()) {
					current = open(type.get(), parts[1].replace("*", "").strip(), level, number);
					section = Section.DESCRIPTION;
					metadataOpen = true;
					continue;
				}
				// any other heading is just text of the description
			}
			if (skipBelow > 0) {
				continue;
			}
			if (current == null) {
				Matcher bullet = BULLET.matcher(line);
				if (!inCode && expectedRoot == ItemType.TASK && bullet.matches()) {
					addCompactTask(null, bullet.group(1), number);
				}
				continue;
			}
			if (inCode) {
				current.description.add(line);
				continue;
			}

			if (metadataOpen) {
				if (line.isBlank()) {
					continue;
				}
				if (readMetadata(current, line, number)) {
					continue;
				}
				metadataOpen = false;
			}

			Matcher bold = SECTION.matcher(line);
			if (bold.matches() && startSection(current, bold.group(1).strip().replaceAll(":$", ""), number) instanceof Section started) {
				section = started;
				continue;
			}
			switch (section) {
				case CRITERIA -> {
					Matcher bullet = BULLET.matcher(line);
					if (bullet.matches()) {
						Matcher box = CHECKBOX.matcher(bullet.group(1).strip());
						String criterion = (box.matches() ? box.group(1) : bullet.group(1)).strip();
						if (!criterion.isEmpty()) {
							current.criteria.add(criterion);
						}
					} else if (!line.isBlank()) {
						section = Section.DESCRIPTION;
						current.description.add(line);
					}
				}
				case TASKS -> {
					Matcher bullet = BULLET.matcher(line);
					if (bullet.matches()) {
						addCompactTask(current, bullet.group(1), number);
					} else if (!line.isBlank()) {
						section = Section.DESCRIPTION;
						current.description.add(line);
					}
				}
				case IGNORED -> {
				}
				case DESCRIPTION -> current.description.add(line);
			}
		}

		if (items > MAX_ITEMS) {
			add(Code.TOO_MANY_ITEMS, 0, "max", String.valueOf(MAX_ITEMS));
		} else if (roots.isEmpty()) {
			add(Code.NO_ITEMS, 0);
		}
		return new ParseResult(roots.stream().map(Node::freeze).toList(), List.copyOf(diagnostics));
	}

	private Node open(ItemType type, String title, int level, int line) {
		while (!stack.isEmpty() && stack.peek().level >= level) {
			stack.pop();
		}
		Node parent = stack.peek();
		Node node = new Node(type, title, level, line);
		items++;
		if (title.isEmpty()) {
			add(Code.EMPTY_TITLE, line, "type", type.name());
		}
		if (parent == null) {
			if (type != expectedRoot) {
				add(type == ItemType.EPIC ? Code.EPIC_NOT_ROOT : Code.ROOT_TYPE_MISMATCH, line, "expected", expectedRoot.name(), "found", type.name());
			}
			roots.add(node);
		} else {
			if (type == ItemType.EPIC) {
				add(Code.EPIC_NOT_ROOT, line, "expected", expectedRoot.name(), "found", type.name());
			} else {
				WorkItemHierarchy.validateHierarchy(type, parent.type)
						.tapError(message -> add(Code.NON_STANDARD_HIERARCHY, line, "type", type.name(), "parent", parent.type.name()));
			}
			parent.children.add(node);
		}
		stack.push(node);
		return node;
	}

	private void addCompactTask(Node parent, String text, int line) {
		String title = text.strip();
		BigDecimal hours = null;
		Matcher compact = COMPACT_HOURS.matcher(title);
		if (compact.matches()) {
			title = compact.group(1).strip();
			hours = new BigDecimal(compact.group(2).replace(',', '.'));
		}
		if (title.isEmpty()) {
			return;
		}
		items++;
		Node node = new Node(ItemType.TASK, title, Integer.MAX_VALUE, line);
		node.hours = hours;
		if (parent == null) {
			roots.add(node);
		} else {
			parent.children.add(node);
		}
	}

	/** @return the section that starts at this bold line, or {@code null} if it is just text */
	private Section startSection(Node node, String title, int line) {
		TypeSpec spec = MarkdownFormat.spec(node.type);
		if (MarkdownFormat.isAcceptanceCriteria(title)) {
			if (spec.acceptanceCriteria()) {
				return Section.CRITERIA;
			}
			add(Code.CRITERIA_IGNORED, line, "type", node.type.name());
			return Section.IGNORED;
		}
		if (MarkdownFormat.isTaskList(title)) {
			if (spec.taskList()) {
				return Section.TASKS;
			}
			add(Code.SECTION_IGNORED, line, "section", title);
			return Section.IGNORED;
		}
		if (MarkdownFormat.isIgnoredSection(title)) {
			add(Code.SECTION_IGNORED, line, "section", title);
			return Section.IGNORED;
		}
		return null;
	}

	/** @return whether the line was a metadata line (so the metadata block goes on) */
	private boolean readMetadata(Node node, String line, int number) {
		Matcher match = METADATA_BULLET.matcher(line);
		if (!match.matches()) {
			match = METADATA_BOLD.matcher(line);
			if (!match.matches()) {
				return false;
			}
		}
		String key = match.group(1).strip();
		String value = match.group(2).strip();
		Optional<Field> field = MarkdownFormat.fieldOf(key);
		if (field.isEmpty()) {
			if (NOT_WRITABLE_KEYS.contains(MarkdownFormat.normalize(key))) {
				add(Code.KEY_IGNORED, number, "key", key);
				return true;
			}
			return false;
		}
		if (!MarkdownFormat.spec(node.type).fields().contains(field.get())) {
			add(Code.KEY_IGNORED, number, "key", key);
			return true;
		}
		if (value.isEmpty()) {
			return true;
		}
		switch (field.get()) {
			case PRIORITY -> {
				Optional<Priority> priority = MarkdownFormat.priorityOf(value);
				if (priority.isPresent()) {
					node.priority = priority.get();
				} else {
					add(Code.UNKNOWN_PRIORITY, number, "value", value);
				}
			}
			case POINTS -> node.points = number(value, key, number);
			case HOURS -> node.hours = number(value, key, number);
			case SPRINT -> node.sprint = value;
			case ASSIGNEE -> node.assignee = value;
		}
		return true;
	}

	private BigDecimal number(String value, String key, int line) {
		try {
			BigDecimal parsed = new BigDecimal(value.replace(',', '.'));
			if (parsed.signum() >= 0) {
				return parsed;
			}
		} catch (NumberFormatException e) {
			// reported below
		}
		add(Code.INVALID_NUMBER, line, "key", key, "value", value);
		return null;
	}

	private void add(Code code, int line, String... params) {
		Map<String, String> map = new java.util.LinkedHashMap<>();
		for (int i = 0; i + 1 < params.length; i += 2) {
			map.put(params[i], params[i + 1]);
		}
		diagnostics.add(new ImportDiagnostic(code, line, Map.copyOf(map)));
	}

	/** Comments may span lines; keep the line breaks so line numbers stay true. */
	private static String blankComments(String text) {
		return COMMENT.matcher(text).replaceAll(match -> Matcher.quoteReplacement(match.group().replaceAll("[^\n]", "")));
	}

	/** A file an LLM wrapped in one code fence is read as if it were not (the fence lines become blank, numbering kept). */
	private static String unwrapFence(String text) {
		String[] lines = text.split("\n", -1);
		int first = 0;
		while (first < lines.length && lines[first].isBlank()) {
			first++;
		}
		int last = lines.length - 1;
		while (last > first && lines[last].isBlank()) {
			last--;
		}
		if (first < last && lines[first].strip().matches("(```|~~~)\\s*(markdown|md)?") && lines[last].strip().matches("```|~~~")) {
			lines[first] = "";
			lines[last] = "";
			return String.join("\n", lines);
		}
		return text;
	}

	private static final class Node {

		final ItemType type;
		final String title;
		final int level;
		final int line;
		Priority priority;
		BigDecimal points;
		BigDecimal hours;
		String sprint;
		String assignee;
		final List<String> description = new ArrayList<>();
		final List<String> criteria = new ArrayList<>();
		final List<Node> children = new ArrayList<>();

		Node(ItemType type, String title, int level, int line) {
			this.type = type;
			this.title = title;
			this.level = level;
			this.line = line;
		}

		ParsedItem freeze() {
			return new ParsedItem(type, title, line, priority, points, hours, sprint, assignee, text(), List.copyOf(criteria),
					children.stream().map(Node::freeze).toList());
		}

		private String text() {
			return String.join("\n", description).strip();
		}

	}

}
