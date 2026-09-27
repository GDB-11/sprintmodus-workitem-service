package com.sprintmodus.workitem_service.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sprintmodus.workitem_service.application.dto.Actor;
import com.sprintmodus.workitem_service.application.dto.Commands.AddComment;
import com.sprintmodus.workitem_service.application.error.CommentError;
import com.sprintmodus.workitem_service.application.error.NotificationError;
import com.sprintmodus.workitem_service.application.error.UserSearchError;
import com.sprintmodus.workitem_service.application.service.AddCommentUseCase;
import com.sprintmodus.workitem_service.application.service.CountUnreadNotificationsUseCase;
import com.sprintmodus.workitem_service.application.service.CountUsersUseCase;
import com.sprintmodus.workitem_service.application.service.ListNotificationsUseCase;
import com.sprintmodus.workitem_service.application.service.MarkAllNotificationsReadUseCase;
import com.sprintmodus.workitem_service.application.service.MarkNotificationReadUseCase;
import com.sprintmodus.workitem_service.application.service.SearchUsersUseCase;
import com.sprintmodus.workitem_service.domain.event.DomainEvent.NotificationCreated;
import com.sprintmodus.workitem_service.domain.event.DomainEvent.UserMentioned;
import com.sprintmodus.workitem_service.domain.event.DomainEvent.WorkItemCommentAdded;
import com.sprintmodus.workitem_service.domain.model.ItemType;
import com.sprintmodus.workitem_service.domain.model.NotificationType;
import com.sprintmodus.workitem_service.domain.model.OrganizationRole;
import com.sprintmodus.workitem_service.domain.model.UserRef;
import com.sprintmodus.workitem_service.domain.model.WorkItem;
import com.sprintmodus.workitem_service.support.Fakes;

/** Phase 14: @mentions in comments and the notifications they write, with the fakes (no Spring, no database). */
class MentionsAndNotificationsTest {

	private final Fakes.Workflows workflows = new Fakes.Workflows();

	private final Fakes.WorkItems items = new Fakes.WorkItems(workflows);

	private final Fakes.Comments comments = new Fakes.Comments(items);

	private final Fakes.Audit audit = new Fakes.Audit();

	private final Fakes.Transactions transactions = new Fakes.Transactions(audit);

	private final Fakes.Events events = new Fakes.Events(transactions);

	private final Fakes.Users users = new Fakes.Users();

	private final Fakes.Notifications notifications = new Fakes.Notifications(items, users, transactions);

	private final AddCommentUseCase addComment = new AddCommentUseCase(comments, users, notifications, audit, transactions, events);

	private final ListNotificationsUseCase list = new ListNotificationsUseCase(notifications);

	private final CountUnreadNotificationsUseCase countUnread = new CountUnreadNotificationsUseCase(notifications);

	private final MarkNotificationReadUseCase markRead = new MarkNotificationReadUseCase(notifications, transactions);

	private final MarkAllNotificationsReadUseCase markAllRead = new MarkAllNotificationsReadUseCase(notifications, transactions);

	private final SearchUsersUseCase searchUsers = new SearchUsersUseCase(users);

	private final CountUsersUseCase countUsers = new CountUsersUseCase(users);

	private UUID authorCode;

	private UUID anaCode;

	private UUID bobCode;

	private Actor author;

	private Actor ana;

	private Actor bob;

	private WorkItem item;

	@BeforeEach
	void aTeamAndAnItem() {
		authorCode = users.add("Olivia Owner");
		anaCode = users.add("Ana Diaz");
		bobCode = users.add("Bob Builder");
		author = new Actor(authorCode, OrganizationRole.OWNER);
		ana = new Actor(anaCode, OrganizationRole.MEMBER);
		bob = new Actor(bobCode, OrganizationRole.MEMBER);
		item = items.add(items.addProject("WAR"), ItemType.PBI, "NEW", 3, null, authorCode);
	}

	private static String token(String name, UUID code) {
		return "@[" + name + "](" + code + ")";
	}

	private String comment(Actor actor, String text) {
		var result = addComment.execute(new AddComment(actor, item.code(), text));
		assertThat(result.isSuccess()).as(text).isTrue();
		return result.getValue().code().toString();
	}

	// ------------------------------------------------------------------ writing a comment with mentions

	@Test
	void everyMentionedUserGetsOneNotificationAndTheAuthorGetsNone() {
		comment(author, "Please look, " + token("Ana Diaz", anaCode) + " and " + token("Bob Builder", bobCode));

		assertThat(notifications.of(anaCode)).singleElement().satisfies(notification -> {
			assertThat(notification.type()).isEqualTo(NotificationType.MENTIONED);
			assertThat(notification.workItemCode()).isEqualTo(item.code());
			assertThat(notification.workItemKey()).isEqualTo(item.displayKey());
			assertThat(notification.actor()).isEqualTo(new UserRef(authorCode, "Olivia Owner"));
			assertThat(notification.read()).isFalse();
		});
		assertThat(notifications.of(bobCode)).hasSize(1);
		assertThat(notifications.of(authorCode)).isEmpty();
	}

	@Test
	void theNotificationsAreWrittenInsideTheTransactionOfTheComment() {
		comment(author, token("Ana Diaz", anaCode) + " " + token("Bob Builder", bobCode));

		assertThat(notifications.createdInsideTransaction).containsExactly(true, true);
		assertThat(transactions.runs).isEqualTo(1);
	}

	@Test
	void theMentionsOfACommentAreRecordedOncePerUser() {
		String commentCode = comment(author, token("Ana", anaCode) + " " + token("Ana Diaz", anaCode) + " " + token("Bob", bobCode));

		assertThat(comments.mentions.get(UUID.fromString(commentCode))).containsExactly(anaCode, bobCode);
		assertThat(notifications.of(anaCode)).as("mentioned twice, notified once").hasSize(1);
	}

	@Test
	void aSelfMentionStaysInTheTextButNotifiesNobodyAndPublishesNoMentionEvent() {
		String commentCode = comment(author, "Note to self " + token("Olivia Owner", authorCode));

		assertThat(comments.stored).singleElement().satisfies(stored -> assertThat(stored.content()).contains(token("Olivia Owner", authorCode)));
		assertThat(comments.mentions.get(UUID.fromString(commentCode))).containsExactly(authorCode);
		assertThat(notifications.total()).isZero();
		assertThat(events.published).containsExactly(new WorkItemCommentAdded(item.code(), authorCode, UUID.fromString(commentCode)));
	}

	@Test
	void aCommentWithoutMentionsNotifiesNobody() {
		comment(author, "Just a comment, mail me at a@b.c, ping @ana (plain text) @[Ana](not-a-code)");

		assertThat(notifications.total()).isZero();
		assertThat(comments.mentions).isEmpty();
	}

	@Test
	void aMentionOfAnUnknownOrInactiveUserRefusesTheWholeCommentAndWritesNothing() {
		UUID gone = users.add("Gone Person");
		users.deactivate(gone);
		for (UUID stale : new UUID[] { UUID.randomUUID(), gone }) {
			var result = addComment.execute(new AddComment(author, item.code(), token("Ana Diaz", anaCode) + " and " + token("Stale Name", stale)));

			assertThat(result.getError()).isInstanceOfSatisfying(CommentError.UnknownMention.class, error -> assertThat(error.message()).contains("@Stale Name"));
		}

		assertThat(comments.stored).isEmpty();
		assertThat(comments.mentions).isEmpty();
		assertThat(audit.entries).isEmpty();
		assertThat(notifications.total()).as("not even the valid mention was notified").isZero();
		assertThat(events.published).isEmpty();
	}

	@Test
	void aMentionOnAMissingItemIsAnItemNotFoundAndNotifiesNobody() {
		var result = addComment.execute(new AddComment(author, UUID.randomUUID(), token("Ana Diaz", anaCode)));

		assertThat(result.getError()).isInstanceOf(CommentError.WorkItemNotFound.class);
		assertThat(notifications.total()).isZero();
		assertThat(events.published).isEmpty();
	}

	@Test
	void atMostTwentyDistinctPeopleCanBeMentioned() {
		String twenty = IntStream.range(0, 20).mapToObj(i -> token("User " + i, users.add("User " + i))).collect(Collectors.joining(" "));
		String twentyOne = twenty + " " + token("One more", users.add("One more"));

		assertThat(addComment.execute(new AddComment(author, item.code(), twenty)).isSuccess()).isTrue();
		assertThat(addComment.execute(new AddComment(author, item.code(), twentyOne)).getError()).isInstanceOf(CommentError.InvalidComment.class);
		assertThat(comments.stored).hasSize(1);
	}

	@Test
	void theLimitOfTenThousandCharactersCountsTheRawTokens() {
		String token = token("Ana Diaz", anaCode);
		String fits = token + "z".repeat(10_000 - token.length());

		assertThat(addComment.execute(new AddComment(author, item.code(), fits)).isSuccess()).isTrue();
		assertThat(addComment.execute(new AddComment(author, item.code(), fits + "z")).getError()).isInstanceOf(CommentError.InvalidComment.class);
	}

	@Test
	void theEventsAreOnlyPublishedAfterTheCommitAndCarryNoText() {
		String commentCode = comment(author, "Look " + token("Ana Diaz", anaCode));
		UUID notificationCode = notifications.of(anaCode).getFirst().code();
		UUID comment = UUID.fromString(commentCode);

		assertThat(events.published).containsExactly(new WorkItemCommentAdded(item.code(), authorCode, comment),
				new UserMentioned(item.code(), authorCode, comment, anaCode),
				new NotificationCreated(item.code(), authorCode, notificationCode, anaCode, NotificationType.MENTIONED));
		assertThat(events.insideTransaction).containsOnly(false);
	}

	@Test
	void theAuditPreviewReadsAsPlainNamesAndNeverCutsATokenInHalf() {
		String padding = "x".repeat(85);
		comment(author, padding + " " + token("Ana Diaz", anaCode) + " tail");

		assertThat(audit.entries).singleElement().satisfies(entry -> {
			assertThat(entry.newValue()).isEqualTo((padding + " @Ana Diaz tail").substring(0, 100)).doesNotContain("](").doesNotContain(anaCode.toString());
			assertThat(entry.newValue()).startsWith(padding + " @Ana");
		});
		assertThat(comments.stored.getFirst().content()).as("the comment itself keeps the raw token").contains(token("Ana Diaz", anaCode));
	}

	// ------------------------------------------------------------------ reading and marking notifications

	@Test
	void notificationsAreListedNewestFirstAndUnreadOnesCanBeFiltered() {
		comment(author, token("Ana Diaz", anaCode) + " first");
		comment(bob, token("Ana Diaz", anaCode) + " second");
		UUID newest = notifications.of(anaCode).getFirst().code();
		UUID oldest = notifications.of(anaCode).getLast().code();
		markRead.execute(ana, oldest);

		var all = list.execute(ana, false, 0, 20).getValue();
		var unread = list.execute(ana, true, 0, 20).getValue();

		assertThat(all.total()).isEqualTo(2);
		assertThat(all.items()).extracting(n -> n.code()).containsExactly(newest, oldest);
		assertThat(all.items().getFirst().actor().fullName()).isEqualTo("Bob Builder");
		assertThat(unread.items()).extracting(n -> n.code()).containsExactly(newest);
	}

	@Test
	void theListIsPagedAndItsParametersAreChecked() {
		for (int i = 0; i < 3; i++) {
			comment(author, token("Ana Diaz", anaCode) + " " + i);
		}

		assertThat(list.execute(ana, false, 0, 2).getValue().items()).hasSize(2);
		assertThat(list.execute(ana, false, 1, 2).getValue().items()).hasSize(1);
		assertThat(list.execute(ana, false, 1, 2).getValue().total()).isEqualTo(3);
		assertThat(list.execute(ana, false, -1, 20).getError()).isInstanceOf(NotificationError.InvalidQuery.class);
		assertThat(list.execute(ana, false, 0, 0).getError()).isInstanceOf(NotificationError.InvalidQuery.class);
		assertThat(list.execute(ana, false, 0, 101).getError()).isInstanceOf(NotificationError.InvalidQuery.class);
		assertThat(list.execute(ana, false, 0, 100).isSuccess()).isTrue();
	}

	@Test
	void everyoneSeesOnlyTheirOwnNotifications() {
		comment(author, token("Ana Diaz", anaCode));

		assertThat(list.execute(ana, false, 0, 20).getValue().items()).hasSize(1);
		assertThat(list.execute(bob, false, 0, 20).getValue().items()).isEmpty();
		assertThat(countUnread.execute(ana).getValue()).isEqualTo(1);
		assertThat(countUnread.execute(bob).getValue()).isZero();
	}

	@Test
	void markingOneAsReadLowersTheCountAndIsIdempotent() {
		comment(author, token("Ana Diaz", anaCode) + " one");
		comment(author, token("Ana Diaz", anaCode) + " two");
		UUID first = notifications.of(anaCode).getFirst().code();

		assertThat(markRead.execute(ana, first).isSuccess()).isTrue();
		assertThat(markRead.execute(ana, first).isSuccess()).as("reading again is fine").isTrue();

		assertThat(countUnread.execute(ana).getValue()).isEqualTo(1);
		assertThat(list.execute(ana, false, 0, 20).getValue().items().getFirst().read()).isTrue();
	}

	@Test
	void someoneElsesOrAMissingNotificationIsNotFound() {
		comment(author, token("Ana Diaz", anaCode));
		UUID anas = notifications.of(anaCode).getFirst().code();

		assertThat(markRead.execute(bob, anas).getError()).isInstanceOf(NotificationError.NotificationNotFound.class);
		assertThat(markRead.execute(ana, UUID.randomUUID()).getError()).isInstanceOf(NotificationError.NotificationNotFound.class);
		assertThat(countUnread.execute(ana).getValue()).as("Bob could not read Ana's notification").isEqualTo(1);
	}

	@Test
	void markingAllAsReadOnlyTouchesTheCallersNotifications() {
		comment(author, token("Ana Diaz", anaCode) + " " + token("Bob Builder", bobCode));
		comment(bob, token("Ana Diaz", anaCode));

		assertThat(markAllRead.execute(ana).getValue()).isEqualTo(2);
		assertThat(countUnread.execute(ana).getValue()).isZero();
		assertThat(countUnread.execute(bob).getValue()).isEqualTo(1);
		assertThat(markAllRead.execute(ana).getValue()).as("nothing left to mark").isZero();
	}

	@Test
	void aNotificationAboutADeletedItemIsNeitherListedNorCounted() {
		comment(author, token("Ana Diaz", anaCode));
		items.softDelete(item.code(), authorCode);

		assertThat(countUnread.execute(ana).getValue()).isZero();
		assertThat(list.execute(ana, false, 0, 20).getValue().items()).isEmpty();
		assertThat(markRead.execute(ana, notifications.of(anaCode).getFirst().code()).getError()).isInstanceOf(NotificationError.NotificationNotFound.class);
	}

	// ------------------------------------------------------------------ the autocomplete

	@Test
	void usersAreFoundByTheStartOfTheNameOrOfAnyWordOfIt() {
		assertThat(searchUsers.execute("ana", 10).getValue()).extracting(UserRef::fullName).containsExactly("Ana Diaz");
		assertThat(searchUsers.execute("diaz", 10).getValue()).extracting(UserRef::fullName).containsExactly("Ana Diaz");
		assertThat(searchUsers.execute("  BOB ", 10).getValue()).extracting(UserRef::fullName).containsExactly("Bob Builder");
		assertThat(searchUsers.execute("zzz", 10).getValue()).isEmpty();
	}

	@Test
	void aBlankSearchListsUsersByNameUpToTheLimitAndOnlyActiveOnesAppear() {
		users.deactivate(bobCode);

		assertThat(searchUsers.execute("", 10).getValue()).extracting(UserRef::fullName).containsExactly("Ana Diaz", "Olivia Owner");
		assertThat(searchUsers.execute(null, 1).getValue()).hasSize(1);
	}

	@Test
	void theSearchParametersAreChecked() {
		assertThat(searchUsers.execute("x".repeat(101), 10).getError()).isInstanceOf(UserSearchError.InvalidQuery.class);
		assertThat(searchUsers.execute("a", 0).getError()).isInstanceOf(UserSearchError.InvalidQuery.class);
		assertThat(searchUsers.execute("a", 51).getError()).isInstanceOf(UserSearchError.InvalidQuery.class);
		assertThat(searchUsers.execute("x".repeat(100), 50).isSuccess()).isTrue();
	}

	@Test
	void theActiveUserCountExcludesDeactivatedUsers() {
		assertThat(countUsers.execute().getValue()).isEqualTo(3);

		users.deactivate(bobCode);

		assertThat(countUsers.execute().getValue()).isEqualTo(2);
	}

}
