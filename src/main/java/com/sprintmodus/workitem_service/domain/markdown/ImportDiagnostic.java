package com.sprintmodus.workitem_service.domain.markdown;

import java.util.Map;

/**
 * A finding about an import file: a stable code plus parameters (never display text, the frontend words it in Spanish).
 *
 * @param line the 1-based line of the file it is about, 0 when it concerns the whole file
 */
public record ImportDiagnostic(Code code, int line, Map<String, String> params) {

	public enum Severity {
		ERROR, WARNING
	}

	/** Every code the import can emit; the frontend must have wording for each. */
	public enum Code {

		// errors: they block the import
		FILE_TOO_LARGE(Severity.ERROR),
		NO_ITEMS(Severity.ERROR),
		TOO_MANY_ITEMS(Severity.ERROR),
		EPIC_NOT_ROOT(Severity.ERROR),
		ROOT_TYPE_MISMATCH(Severity.ERROR),
		EMPTY_TITLE(Severity.ERROR),
		INVALID_NUMBER(Severity.ERROR),
		TITLE_TOO_LONG(Severity.ERROR),
		TEXT_TOO_LONG(Severity.ERROR),
		UNKNOWN_PRIORITY(Severity.ERROR),
		// warnings: the item is still created
		UNKNOWN_SPRINT(Severity.WARNING),
		UNKNOWN_ASSIGNEE(Severity.WARNING),
		NON_STANDARD_HIERARCHY(Severity.WARNING),
		CRITERIA_IGNORED(Severity.WARNING),
		SECTION_IGNORED(Severity.WARNING),
		BUG_SKIPPED(Severity.WARNING),
		KEY_IGNORED(Severity.WARNING);

		private final Severity severity;

		Code(Severity severity) {
			this.severity = severity;
		}

		public Severity severity() {
			return severity;
		}

	}

	public Severity severity() {
		return code.severity();
	}

}
