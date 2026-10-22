package com.sprintmodus.workitem_service.domain.markdown;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.sprintmodus.workitem_service.domain.model.ItemType;
import com.sprintmodus.workitem_service.domain.model.Priority;

/**
 * The Markdown interchange format as data: the one table the parser, the renderer and the templates are driven by, so
 * they cannot drift. Spanish is canonical (everything the system writes); English is accepted on input equally, token by
 * token. {@code planning/MARKDOWN_IMPORT_TEMPLATES*.md} are the golden fixtures of this table.
 */
public final class MarkdownFormat {

	/** The metadata lines ({@code - key: value}) a heading can carry. */
	public enum Field {

		PRIORITY("prioridad", List.of("priority")),
		POINTS("puntos", List.of("points")),
		HOURS("horas", List.of("hours")),
		SPRINT("sprint", List.of()),
		ASSIGNEE("asignado", List.of("responsable", "assignee"));

		private final String key;
		private final List<String> aliases;

		Field(String key, List<String> aliases) {
			this.key = key;
			this.aliases = aliases;
		}

		/** The Spanish key the system writes. */
		public String key() {
			return key;
		}

		boolean matches(String normalized) {
			return normalize(key).equals(normalized) || aliases.stream().anyMatch(alias -> normalize(alias).equals(normalized));
		}

	}

	/**
	 * What one work item type carries in the format.
	 *
	 * @param keyword the Spanish heading keyword the system writes
	 * @param aliases other accepted keywords (English included)
	 * @param fields the metadata keys allowed, in the order they are written
	 * @param acceptanceCriteria whether {@code **Criterios de aceptación**} is extracted (else ignored with a warning)
	 * @param taskList whether the compact {@code **Tareas**} list is allowed
	 */
	public record TypeSpec(ItemType type, String keyword, List<String> aliases, List<Field> fields, boolean acceptanceCriteria, boolean taskList) {
	}

	/**
	 * A place a file can be imported from: the screen it was opened on decides the type of the roots.
	 *
	 * @param root the type of the file's top-level items
	 * @param levels how many types the file includes, starting at the root (1 = roots only)
	 */
	public record Context(ItemType root, int levels) {

		public List<ItemType> types() {
			int start = CHAIN.indexOf(root);
			return CHAIN.subList(start, start + levels);
		}

	}

	/** The importable types from the top of the hierarchy down; a Bug is never imported. */
	public static final List<ItemType> CHAIN = List.of(ItemType.EPIC, ItemType.FEATURE, ItemType.PBI, ItemType.TASK);

	private static final List<TypeSpec> TYPES = List.of(
			new TypeSpec(ItemType.EPIC, "Épica", List.of("Epic"), List.of(Field.PRIORITY), false, false),
			new TypeSpec(ItemType.FEATURE, "Característica", List.of("Feature"), List.of(Field.PRIORITY), true, false),
			new TypeSpec(ItemType.PBI, "Elemento del backlog", List.of("PBI"),
					List.of(Field.PRIORITY, Field.POINTS, Field.SPRINT, Field.ASSIGNEE), true, true),
			new TypeSpec(ItemType.TASK, "Tarea", List.of("Task"), List.of(Field.PRIORITY, Field.HOURS, Field.SPRINT, Field.ASSIGNEE), false, false));

	/** A heading with this keyword is skipped with a warning (a Bug is tied to a PBI with a link instead). */
	private static final List<String> BUG_KEYWORDS = List.of("Error", "Bug");

	private static final Map<Priority, List<String>> PRIORITY_WORDS = Map.of(
			Priority.LOW, List.of("baja", "low"),
			Priority.MEDIUM, List.of("media", "medium"),
			Priority.HIGH, List.of("alta", "high"),
			Priority.CRITICAL, List.of("crítica", "critical"));

	public static final String ACCEPTANCE_CRITERIA = "Criterios de aceptación";
	public static final List<String> ACCEPTANCE_CRITERIA_ALIASES = List.of("Acceptance criteria");
	public static final String TASKS = "Tareas";
	public static final List<String> TASKS_ALIASES = List.of("Tasks");
	/** Written by the export, recognized and ignored on import (with one warning). */
	public static final List<String> IGNORED_SECTIONS = List.of("Enlaces", "Comentarios", "Links", "Comments");

	private MarkdownFormat() {
	}

	public static TypeSpec spec(ItemType type) {
		return TYPES.stream().filter(spec -> spec.type() == type).findFirst()
				.orElseThrow(() -> new IllegalArgumentException(type + " is not part of the Markdown format"));
	}

	/** The type a heading keyword names, in either language; empty for anything else (including a Bug keyword). */
	public static Optional<ItemType> typeOf(String keyword) {
		String normalized = normalize(keyword);
		return TYPES.stream()
				.filter(spec -> normalize(spec.keyword()).equals(normalized) || spec.aliases().stream().anyMatch(alias -> normalize(alias).equals(normalized)))
				.map(TypeSpec::type).findFirst();
	}

	public static boolean isBugKeyword(String keyword) {
		return BUG_KEYWORDS.stream().anyMatch(bug -> normalize(bug).equals(normalize(keyword)));
	}

	/** The metadata field a key names, in either language. */
	public static Optional<Field> fieldOf(String key) {
		String normalized = normalize(key);
		for (Field field : Field.values()) {
			if (field.matches(normalized)) {
				return Optional.of(field);
			}
		}
		return Optional.empty();
	}

	public static Optional<Priority> priorityOf(String word) {
		String normalized = normalize(word);
		return PRIORITY_WORDS.entrySet().stream().filter(entry -> entry.getValue().stream().anyMatch(w -> normalize(w).equals(normalized)))
				.map(Map.Entry::getKey).findFirst();
	}

	/** The Spanish word the system writes for a priority. */
	public static String priorityWord(Priority priority) {
		return PRIORITY_WORDS.get(priority).getFirst();
	}

	public static boolean isAcceptanceCriteria(String title) {
		return matchesAny(title, ACCEPTANCE_CRITERIA, ACCEPTANCE_CRITERIA_ALIASES);
	}

	public static boolean isTaskList(String title) {
		return matchesAny(title, TASKS, TASKS_ALIASES);
	}

	public static boolean isIgnoredSection(String title) {
		return IGNORED_SECTIONS.stream().anyMatch(section -> normalize(section).equals(normalize(title)));
	}

	/** Which types may be roots, in order: no parent means Epics, an Epic's screen Features, and so on. Bug and Task have no import. */
	public static Optional<ItemType> rootFor(ItemType screenParent) {
		if (screenParent == null) {
			return Optional.of(ItemType.EPIC);
		}
		int index = CHAIN.indexOf(screenParent);
		return index >= 0 && index < CHAIN.size() - 1 ? Optional.of(CHAIN.get(index + 1)) : Optional.empty();
	}

	/** The 10 (context × depth) combinations: the root type and how many types of the chain the file includes. */
	public static List<Context> contexts() {
		List<Context> contexts = new java.util.ArrayList<>();
		for (int start = 0; start < CHAIN.size(); start++) {
			for (int levels = 1; levels <= CHAIN.size() - start; levels++) {
				contexts.add(new Context(CHAIN.get(start), levels));
			}
		}
		return List.copyOf(contexts);
	}

	/** Lower case, no accents, trimmed: {@code Épica} equals {@code epica}. */
	public static String normalize(String text) {
		return Normalizer.normalize(text.strip(), Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
	}

	private static boolean matchesAny(String title, String canonical, List<String> aliases) {
		String normalized = normalize(title);
		return normalize(canonical).equals(normalized) || aliases.stream().anyMatch(alias -> normalize(alias).equals(normalized));
	}

}
