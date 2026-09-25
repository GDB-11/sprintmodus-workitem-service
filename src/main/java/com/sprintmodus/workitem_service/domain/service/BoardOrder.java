package com.sprintmodus.workitem_service.domain.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The manual order of the cards that share a rank group. Pure: given the group as it is ordered now, it says how it is
 * ordered after one card is placed elsewhere, and the position of a card is then its place in that list (1 = first).
 */
public final class BoardOrder {

	private BoardOrder() {
	}

	/**
	 * {@code group} with {@code moved} taken out and put right before {@code before}, or last when {@code before} is
	 * {@code null}. {@code before} must be in the group and different from {@code moved}; {@code moved} must be in it too.
	 */
	public static List<UUID> place(List<UUID> group, UUID moved, UUID before) {
		if (!group.contains(moved) || (before != null && (before.equals(moved) || !group.contains(before)))) {
			throw new IllegalArgumentException("Both cards must be in the group, and a card cannot be placed before itself.");
		}
		List<UUID> placed = new ArrayList<>(group.stream().filter(code -> !code.equals(moved)).toList());
		placed.add(before == null ? placed.size() : placed.indexOf(before), moved);
		return placed;
	}

}
