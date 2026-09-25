package com.sprintmodus.workitem_service.adapter.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class TypingThrottleTest {

	private static final class MutableClock extends Clock {

		Instant now = Instant.parse("2026-09-24T10:00:00Z");

		@Override
		public ZoneOffset getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}

	}

	private final MutableClock clock = new MutableClock();

	private final TypingThrottle throttle = new TypingThrottle(clock);

	private final UUID user = UUID.randomUUID();

	private final UUID item = UUID.randomUUID();

	@Test
	void theFirstKeystrokeIsBroadcastAndTheOnesInsideTheWindowAreNot() {
		assertThat(throttle.allow(user, item)).isTrue();

		clock.now = clock.now.plus(TypingThrottle.WINDOW.minusMillis(1));
		assertThat(throttle.allow(user, item)).isFalse();
	}

	@Test
	void typingIsBroadcastAgainOnceTheWindowHasPassed() {
		throttle.allow(user, item);

		clock.now = clock.now.plus(TypingThrottle.WINDOW).plus(Duration.ofMillis(1));
		assertThat(throttle.allow(user, item)).isTrue();
	}

	@Test
	void eachUserAndEachWorkItemIsThrottledOnItsOwn() {
		throttle.allow(user, item);

		assertThat(throttle.allow(UUID.randomUUID(), item)).isTrue();
		assertThat(throttle.allow(user, UUID.randomUUID())).isTrue();
	}

}
