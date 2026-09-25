package com.sprintmodus.workitem_service.adapter.websocket;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * "X is typing" arrives on every keystroke; the first one is broadcast and the following ones within the window are
 * dropped, so a busy board is not flooded. Nothing is persisted.
 */
@Component
class TypingThrottle {

	static final Duration WINDOW = Duration.ofSeconds(2);

	/** Above this many remembered entries, expired ones are dropped, so the map cannot grow without bound. */
	private static final int PRUNE_ABOVE = 10_000;

	private final Clock clock;

	private final Map<String, Instant> lastBroadcast = new ConcurrentHashMap<>();

	@Autowired
	TypingThrottle(ObjectProvider<Clock> clock) {
		this(clock.getIfAvailable(Clock::systemUTC));
	}

	TypingThrottle(Clock clock) {
		this.clock = clock;
	}

	boolean allow(UUID userCode, UUID workItemCode) {
		Instant now = clock.instant();
		if (lastBroadcast.size() > PRUNE_ABOVE) {
			lastBroadcast.values().removeIf(last -> !last.plus(WINDOW).isAfter(now));
		}
		boolean[] allowed = new boolean[1];
		lastBroadcast.compute(userCode + "/" + workItemCode, (_, last) -> {
			if (last == null || !last.plus(WINDOW).isAfter(now)) {
				allowed[0] = true;
				return now;
			}
			return last;
		});
		return allowed[0];
	}

}
