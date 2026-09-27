package com.sprintmodus.workitem_service.domain.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the {@code @[Full Name](userCode)} tokens of a comment. The token carries the user's code, never a name or an e-mail,
 * so a rename or two people with the same name can never mention the wrong person; the name in it is only what a client
 * shows. Anything that is not exactly a token (no closing bracket, a code that is not a UUID, a newline in the name) is plain
 * text. Pure: no I/O.
 */
public final class MentionParser {

	/** A comment naming more distinct people than this is refused: it is a mistake or an attempt to spam. */
	public static final int MAX_DISTINCT_MENTIONS = 20;

	private static final Pattern TOKEN = Pattern.compile(
			"@\\[([^\\[\\]\\r\\n]{1,255})\\]\\(([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\)");

	private MentionParser() {
	}

	/** The name a client showed when it wrote the token; the user's real name comes from the database. */
	public record Mention(UUID userCode, String name) {
	}

	/** Every distinct user mentioned, in order of first appearance; the same user twice counts once. */
	public static List<Mention> parse(String text) {
		Map<UUID, Mention> found = new LinkedHashMap<>();
		Matcher matcher = TOKEN.matcher(text);
		while (matcher.find()) {
			UUID userCode = UUID.fromString(matcher.group(2));
			found.putIfAbsent(userCode, new Mention(userCode, matcher.group(1)));
		}
		return List.copyOf(found.values());
	}

	/** The text as a person reads it: every token reduced to {@code @Full Name}. */
	public static String plainText(String text) {
		return TOKEN.matcher(text).replaceAll(match -> Matcher.quoteReplacement("@" + match.group(1)));
	}

}
