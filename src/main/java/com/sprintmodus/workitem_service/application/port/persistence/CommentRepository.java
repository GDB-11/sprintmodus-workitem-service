package com.sprintmodus.workitem_service.application.port.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.sprintmodus.workitem_service.domain.model.Comment;

public interface CommentRepository {

	/** Oldest first. */
	List<Comment> findByWorkItem(UUID workItemCode);

	/** Empty if the work item is not active or the author does not exist. */
	Optional<Comment> add(UUID workItemCode, UUID authorCode, String content);

	/** Records who a comment mentions, once per user. The users must already have been checked to exist. */
	void addMentions(UUID commentCode, Collection<UUID> userCodes);

}
