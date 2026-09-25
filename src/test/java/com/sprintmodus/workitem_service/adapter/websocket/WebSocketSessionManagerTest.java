package com.sprintmodus.workitem_service.adapter.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class WebSocketSessionManagerTest {

	private final WebSocketSessionManager sessions = new WebSocketSessionManager();

	private final BoardKey board = new BoardKey(UUID.randomUUID(), UUID.randomUUID());

	private final OnlineUser ana = new OnlineUser(UUID.randomUUID(), "ana@acme.io");

	private final OnlineUser luis = new OnlineUser(UUID.randomUUID(), "luis@acme.io");

	@Test
	void registeringReturnsEveryoneOnlineOnTheBoardOrderedByEmail() {
		sessions.registerSession("s-luis", board, luis);

		assertThat(sessions.registerSession("s-ana", board, ana)).containsExactly(ana, luis);
	}

	@Test
	void aUserWithTwoTabsIsOneOnlineUserAndOnlyLeavesWithTheLastTab() {
		sessions.registerSession("tab-1", board, ana);
		sessions.registerSession("tab-2", board, ana);
		assertThat(sessions.getUsersOnline(board)).containsExactly(ana);

		var first = sessions.unregisterSession("tab-1").orElseThrow();
		assertThat(first.stillOnline()).isTrue();
		assertThat(first.usersOnline()).containsExactly(ana);

		var last = sessions.unregisterSession("tab-2").orElseThrow();
		assertThat(last.stillOnline()).isFalse();
		assertThat(last.usersOnline()).isEmpty();
		assertThat(last.user()).isEqualTo(ana);
	}

	@Test
	void leavingTwiceIsANoOpSoAGoodbyeAndAConnectionDropAreNotBothAnnounced() {
		sessions.registerSession("s", board, ana);

		assertThat(sessions.unregisterSession("s")).isPresent();
		assertThat(sessions.unregisterSession("s")).isEmpty();
		assertThat(sessions.unregisterSession("never-registered")).isEmpty();
	}

	@Test
	void boardsAreIndependentAndNeverShareUsersAcrossTenants() {
		BoardKey otherTenant = new BoardKey(UUID.randomUUID(), board.projectCode());
		sessions.registerSession("a", board, ana);
		sessions.registerSession("b", otherTenant, luis);

		assertThat(sessions.getUsersOnline(board)).containsExactly(ana);
		assertThat(sessions.getUsersOnline(otherTenant)).containsExactly(luis);
	}

}
