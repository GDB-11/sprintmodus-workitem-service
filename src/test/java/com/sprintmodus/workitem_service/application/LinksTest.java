package com.sprintmodus.workitem_service.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sprintmodus.workitem_service.application.dto.Actor;
import com.sprintmodus.workitem_service.application.dto.Commands.CreateLink;
import com.sprintmodus.workitem_service.application.error.LinkError;
import com.sprintmodus.workitem_service.application.service.CreateLinkUseCase;
import com.sprintmodus.workitem_service.application.service.RemoveLinkUseCase;
import com.sprintmodus.workitem_service.domain.model.ChangeType;
import com.sprintmodus.workitem_service.domain.model.ItemType;
import com.sprintmodus.workitem_service.domain.model.LinkType;
import com.sprintmodus.workitem_service.domain.model.OrganizationRole;
import com.sprintmodus.workitem_service.domain.model.WorkItem;
import com.sprintmodus.workitem_service.support.Fakes;

class LinksTest {

	private final Fakes.Workflows workflows = new Fakes.Workflows();

	private final Fakes.WorkItems items = new Fakes.WorkItems(workflows);

	private final Fakes.Links links = new Fakes.Links(items);

	private final Fakes.Audit audit = new Fakes.Audit();

	private final Fakes.Transactions transactions = new Fakes.Transactions(audit);

	private final Actor member = new Actor(UUID.randomUUID(), OrganizationRole.MEMBER);

	private final CreateLinkUseCase createLink = new CreateLinkUseCase(items, links, audit, transactions);

	private final RemoveLinkUseCase removeLink = new RemoveLinkUseCase(links, audit, transactions);

	private WorkItem a;

	private WorkItem b;

	private WorkItem c;

	@BeforeEach
	void threeItems() {
		UUID project = items.addProject("WAR");
		a = items.add(project, ItemType.PBI, "NEW", 3, null, member.userCode());
		b = items.add(project, ItemType.PBI, "NEW", 2, null, member.userCode());
		c = items.add(project, ItemType.PBI, "NEW", 1, null, member.userCode());
	}

	@Test
	void linksTwoItemsAndAuditsItWithTheActingUser() {
		var result = createLink.execute(new CreateLink(member, a.code(), b.code(), LinkType.BLOCKS));

		assertThat(result.getValue().type()).isEqualTo(LinkType.BLOCKS);
		assertThat(result.getValue().item().code()).isEqualTo(b.code());
		assertThat(audit.entries).singleElement().satisfies(entry -> {
			assertThat(entry.type()).isEqualTo(ChangeType.LINKED);
			assertThat(entry.newValue()).isEqualTo(b.displayKey() + " (BLOCKS)");
			assertThat(entry.changedBy()).isEqualTo(member.userCode());
			assertThat(entry.additionalData()).containsEntry("targetCode", b.code().toString()).containsEntry("type", "BLOCKS");
		});
		assertThat(transactions.auditSizeAtEnd).containsExactly(1);
		assertThat(links.findByWorkItem(a.code())).extracting(l -> l.other().code()).containsExactly(b.code());
		assertThat(links.findByWorkItem(b.code())).extracting(l -> l.other().code()).containsExactly(a.code());
	}

	@Test
	void rejectsASelfLinkAndAMissingWorkItem() {
		assertThat(createLink.execute(new CreateLink(member, a.code(), a.code(), LinkType.RELATED_TO)).getError())
				.isInstanceOf(LinkError.InvalidLink.class);
		assertThat(createLink.execute(new CreateLink(member, UUID.randomUUID(), b.code(), LinkType.RELATED_TO)).getError())
				.isInstanceOf(LinkError.WorkItemNotFound.class);
		assertThat(createLink.execute(new CreateLink(member, a.code(), UUID.randomUUID(), LinkType.RELATED_TO)).getError())
				.isInstanceOf(LinkError.TargetNotFound.class);
		assertThat(audit.entries).isEmpty();
	}

	@Test
	void theSameLinkCannotBeMadeTwiceButDifferentTypesAreIndependent() {
		assertThat(createLink.execute(new CreateLink(member, a.code(), b.code(), LinkType.RELATED_TO)).isSuccess()).isTrue();

		assertThat(createLink.execute(new CreateLink(member, a.code(), b.code(), LinkType.RELATED_TO)).getError())
				.isInstanceOf(LinkError.AlreadyLinked.class);
		assertThat(createLink.execute(new CreateLink(member, a.code(), b.code(), LinkType.DUPLICATES)).isSuccess())
				.as("a different type between the same two items is a different link").isTrue();
	}

	@Test
	void aLinkThatWouldCloseACycleInItsOwnTypeIsRejected() {
		createLink.execute(new CreateLink(member, a.code(), b.code(), LinkType.BLOCKS));
		createLink.execute(new CreateLink(member, b.code(), c.code(), LinkType.BLOCKS));

		var result = createLink.execute(new CreateLink(member, c.code(), a.code(), LinkType.BLOCKS));

		assertThat(result.getError()).isInstanceOf(LinkError.CyclicLink.class);
		assertThat(links.findByWorkItem(c.code())).hasSize(1);
	}

	@Test
	void aCycleInOneTypeDoesNotBlockAnotherType() {
		createLink.execute(new CreateLink(member, a.code(), b.code(), LinkType.BLOCKS));
		createLink.execute(new CreateLink(member, b.code(), c.code(), LinkType.BLOCKS));

		// c -> a would close a BLOCKS cycle (rejected in the test above), but RELATED_TO is a separate graph
		var result = createLink.execute(new CreateLink(member, c.code(), a.code(), LinkType.RELATED_TO));

		assertThat(result.isSuccess()).isTrue();
	}

	@Test
	void removingALinkDeactivatesItAndAuditsUnlinkedThenItCanBeMadeAgain() {
		var created = createLink.execute(new CreateLink(member, a.code(), b.code(), LinkType.RELATED_TO)).getValue();
		audit.entries.clear();

		assertThat(removeLink.execute(member, a.code(), created.code()).isSuccess()).isTrue();
		assertThat(links.findByWorkItem(a.code())).isEmpty();
		assertThat(audit.entries).singleElement().satisfies(entry -> {
			assertThat(entry.type()).isEqualTo(ChangeType.UNLINKED);
			assertThat(entry.oldValue()).isEqualTo(b.displayKey() + " (RELATED_TO)");
			assertThat(entry.changedBy()).isEqualTo(member.userCode());
		});
		assertThat(removeLink.execute(member, a.code(), created.code()).getError()).isInstanceOf(LinkError.LinkNotFound.class);

		assertThat(createLink.execute(new CreateLink(member, a.code(), b.code(), LinkType.RELATED_TO)).isSuccess())
				.as("relinking after removal works").isTrue();
	}

	@Test
	void aLinkOfAnotherItemCannotBeRemovedThroughThisOne() {
		var created = createLink.execute(new CreateLink(member, a.code(), b.code(), LinkType.RELATED_TO)).getValue();

		assertThat(removeLink.execute(member, c.code(), created.code()).getError()).isInstanceOf(LinkError.LinkNotFound.class);
		assertThat(links.findByWorkItem(a.code())).hasSize(1);
	}

}
