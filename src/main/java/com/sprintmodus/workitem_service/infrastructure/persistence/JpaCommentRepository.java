package com.sprintmodus.workitem_service.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.sprintmodus.workitem_service.application.port.persistence.CommentRepository;
import com.sprintmodus.workitem_service.domain.model.Comment;
import com.sprintmodus.workitem_service.domain.model.UserRef;

@Repository
class JpaCommentRepository implements CommentRepository {

	private final CommentQueries comments;

	JpaCommentRepository(CommentQueries comments) {
		this.comments = comments;
	}

	@Override
	public List<Comment> findByWorkItem(UUID workItemCode) {
		return comments.findByWorkItem(workItemCode.toString()).stream().map(JpaCommentRepository::toDomain).toList();
	}

	@Override
	public Optional<Comment> add(UUID workItemCode, UUID authorCode, String content) {
		String code = UUID.randomUUID().toString();
		if (comments.insert(code, workItemCode.toString(), authorCode.toString(), content) == 0) {
			return Optional.empty();
		}
		return comments.findByCode(code).map(JpaCommentRepository::toDomain);
	}

	private static Comment toDomain(CommentEntity entity) {
		return new Comment(entity.code, entity.workItemCode, new UserRef(entity.authorCode, entity.authorName), entity.content, entity.createdAt);
	}

}
