package com.sprintmodus.workitem_service.adapter.websocket;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Who is online on which board. A user with the board open in two tabs has two sessions but is one online user, and only
 * counts as gone when their last session ends. In memory and per instance: a second workitem-service instance would need a
 * shared broker (no message broker for now, see the architecture notes).
 * <p>
 * The per-board maps are replaced, never modified, so a reader always sees a consistent snapshot.
 */
@Component
public class WebSocketSessionManager {

	/** What is left of a board after a session ended. */
	public record Departure(BoardKey board, OnlineUser user, boolean stillOnline, List<OnlineUser> usersOnline) {
	}

	private final Map<BoardKey, Map<String, OnlineUser>> boards = new ConcurrentHashMap<>();

	private final Map<String, BoardKey> boardOfSession = new ConcurrentHashMap<>();

	/** Registers (or re-registers) a session and returns everyone online on its board, the new session included. */
	public List<OnlineUser> registerSession(String sessionId, BoardKey board, OnlineUser user) {
		boardOfSession.put(sessionId, board);
		boards.compute(board, (_, sessions) -> {
			Map<String, OnlineUser> updated = sessions == null ? new HashMap<>() : new HashMap<>(sessions);
			updated.put(sessionId, user);
			return Map.copyOf(updated);
		});
		return getUsersOnline(board);
	}

	/** Empty if the session was never registered or already left, so a second notice of the same departure does nothing. */
	public Optional<Departure> unregisterSession(String sessionId) {
		BoardKey[] left = new BoardKey[1];
		// atomic: of two notices for one session (a goodbye and the connection dropping), only one gets the board back
		boardOfSession.computeIfPresent(sessionId, (_, board) -> {
			left[0] = board;
			return null;
		});
		BoardKey board = left[0];
		if (board == null) {
			return Optional.empty();
		}
		OnlineUser[] user = new OnlineUser[1];
		boards.computeIfPresent(board, (_, sessions) -> {
			user[0] = sessions.get(sessionId);
			Map<String, OnlineUser> remaining = new HashMap<>(sessions);
			remaining.keySet().removeIf(sessionId::equals);
			return remaining.isEmpty() ? null : Map.copyOf(remaining);
		});
		if (user[0] == null) {
			return Optional.empty();
		}
		List<OnlineUser> online = getUsersOnline(board);
		return Optional.of(new Departure(board, user[0], online.contains(user[0]), online));
	}

	/** Each user once, in a stable order. */
	public List<OnlineUser> getUsersOnline(BoardKey board) {
		return boards.getOrDefault(board, Map.of()).values().stream().distinct()
				.sorted(Comparator.comparing(OnlineUser::email).thenComparing(OnlineUser::userCode)).toList();
	}

}
