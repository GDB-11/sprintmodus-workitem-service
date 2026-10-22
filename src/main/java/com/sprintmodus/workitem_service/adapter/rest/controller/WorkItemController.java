package com.sprintmodus.workitem_service.adapter.rest.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sprintmodus.common_lib.security.AuthenticatedUser;
import com.sprintmodus.workitem_service.adapter.rest.dto.ImportResponses;
import com.sprintmodus.workitem_service.adapter.rest.dto.Requests;
import com.sprintmodus.workitem_service.adapter.rest.dto.Responses;
import com.sprintmodus.workitem_service.adapter.websocket.BoardBroadcaster;
import com.sprintmodus.workitem_service.application.dto.Commands.ChangeStatus;
import com.sprintmodus.workitem_service.application.dto.Commands.CreateWorkItem;
import com.sprintmodus.workitem_service.application.dto.Commands.MoveToSprint;
import com.sprintmodus.workitem_service.application.dto.Commands.ReorderWorkItem;
import com.sprintmodus.workitem_service.application.dto.Commands.SetParent;
import com.sprintmodus.workitem_service.application.dto.Commands.UpdateWorkItem;
import com.sprintmodus.workitem_service.application.dto.Commands.WorkItemQuery;
import com.sprintmodus.workitem_service.application.dto.Commands.WorkItemQuery.SprintScope;
import com.sprintmodus.workitem_service.application.error.WorkItemError.InvalidWorkItemData;
import com.sprintmodus.workitem_service.application.service.ChangeWorkItemStatusUseCase;
import com.sprintmodus.workitem_service.application.service.CreateWorkItemUseCase;
import com.sprintmodus.workitem_service.application.service.DeleteWorkItemUseCase;
import com.sprintmodus.workitem_service.application.service.ExportWorkItemUseCase;
import com.sprintmodus.workitem_service.application.service.GetWorkItemUseCase;
import com.sprintmodus.workitem_service.application.service.ImportTemplates;
import com.sprintmodus.workitem_service.application.service.ImportWorkItemsUseCase;
import com.sprintmodus.workitem_service.application.service.ListWorkItemsUseCase;
import com.sprintmodus.workitem_service.application.service.MoveToSprintUseCase;
import com.sprintmodus.workitem_service.application.service.ReorderWorkItemUseCase;
import com.sprintmodus.workitem_service.application.service.SetParentUseCase;
import com.sprintmodus.workitem_service.application.service.UpdateWorkItemUseCase;
import com.sprintmodus.workitem_service.domain.model.ItemType;
import com.sprintmodus.workitem_service.domain.model.Priority;

/**
 * Maps HTTP to the work item use cases and their {@code Result}s back to HTTP. It holds no business logic. Work items are
 * addressed by their UUID code. A status change is also broadcast to the project's open boards. Removing a parent or a sprint is a {@code DELETE}, so an empty body can never clear one.
 */
@RestController
@RequestMapping("/api/work-items")
class WorkItemController {

	private final CreateWorkItemUseCase createWorkItem;

	private final ListWorkItemsUseCase listWorkItems;

	private final GetWorkItemUseCase getWorkItem;

	private final UpdateWorkItemUseCase updateWorkItem;

	private final DeleteWorkItemUseCase deleteWorkItem;

	private final SetParentUseCase setParent;

	private final ChangeWorkItemStatusUseCase changeStatus;

	private final MoveToSprintUseCase moveToSprint;

	private final ReorderWorkItemUseCase reorderWorkItem;

	private final ExportWorkItemUseCase exportWorkItem;

	private final ImportTemplates importTemplates;

	private final ImportWorkItemsUseCase importWorkItems;

	private final BoardBroadcaster board;

	WorkItemController(CreateWorkItemUseCase createWorkItem, ListWorkItemsUseCase listWorkItems, GetWorkItemUseCase getWorkItem,
			UpdateWorkItemUseCase updateWorkItem, DeleteWorkItemUseCase deleteWorkItem, SetParentUseCase setParent,
			ChangeWorkItemStatusUseCase changeStatus, MoveToSprintUseCase moveToSprint, ReorderWorkItemUseCase reorderWorkItem,
			ExportWorkItemUseCase exportWorkItem, ImportTemplates importTemplates, ImportWorkItemsUseCase importWorkItems, BoardBroadcaster board) {
		this.createWorkItem = createWorkItem;
		this.listWorkItems = listWorkItems;
		this.getWorkItem = getWorkItem;
		this.updateWorkItem = updateWorkItem;
		this.deleteWorkItem = deleteWorkItem;
		this.setParent = setParent;
		this.changeStatus = changeStatus;
		this.moveToSprint = moveToSprint;
		this.reorderWorkItem = reorderWorkItem;
		this.exportWorkItem = exportWorkItem;
		this.importTemplates = importTemplates;
		this.importWorkItems = importWorkItems;
		this.board = board;
	}

	/**
	 * {@code sprintCode} selects one sprint's items; otherwise {@code backlog=true} selects the items in no sprint.
	 * {@code q}, when given, is matched against title and description with the full-text index. {@code sort=board} lists in
	 * the order of a board column (priority, then manual rank, then number); the default is newest first.
	 */
	@GetMapping
	ResponseEntity<?> list(@RequestParam(required = false) UUID projectCode, @RequestParam(required = false) UUID sprintCode,
			@RequestParam(required = false) Boolean backlog, @RequestParam(required = false) ItemType type,
			@RequestParam(required = false) String status, @RequestParam(required = false) UUID parentCode,
			@RequestParam(required = false) UUID assignee, @RequestParam(required = false) Priority priority,
			@RequestParam(required = false) String q, @RequestParam(required = false) String sort, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "50") int size) {
		SprintScope scope = sprintCode != null ? SprintScope.SPRINT : Boolean.TRUE.equals(backlog) ? SprintScope.BACKLOG : SprintScope.ANY;
		return listWorkItems
				.execute(new WorkItemQuery(projectCode, scope, sprintCode, type, status, parentCode, assignee, priority, q, "board".equals(sort), page, size))
				.fold(result -> ResponseEntity.ok(Responses.Page.from(result)), ErrorMapper::toResponse);
	}

	@PostMapping
	ResponseEntity<?> create(@AuthenticationPrincipal AuthenticatedUser user, @RequestBody Requests.CreateWorkItem request) {
		return createWorkItem.execute(new CreateWorkItem(Actors.from(user), request.projectCode(), request.type(), request.title(),
				request.description(), request.acceptanceCriteria(), request.priority(), request.parentCode(), request.effortPoints(),
				request.estimatedHours(), request.remainingHours()))
				.fold(item -> ResponseEntity.status(HttpStatus.CREATED).body(Responses.WorkItem.from(item)), ErrorMapper::toResponse);
	}

	/**
	 * The Markdown template (Spanish) for a file whose roots are {@code root}, or with {@code format=ai-instructions} the
	 * prompt to paste into an LLM. {@code levels} is how many types the file includes, starting at the root.
	 */
	/**
	 * Creates the work items of a Markdown file ({@code text/markdown} body) under {@code projectCode}, or under
	 * {@code parentCode} when given, all or nothing. {@code dryRun=true} only previews. The screen decides the type of the
	 * roots (no parent: Epics). A file with errors creates nothing and answers 400 with the same body as the preview.
	 */
	@PostMapping(value = "/import", consumes = { "text/markdown", "text/plain" })
	ResponseEntity<?> importItems(@AuthenticationPrincipal AuthenticatedUser user, @RequestParam UUID projectCode,
			@RequestParam(required = false) UUID parentCode, @RequestParam(defaultValue = "false") boolean dryRun, @RequestBody String markdown) {
		return importWorkItems.execute(new ImportWorkItemsUseCase.Command(Actors.from(user), projectCode, parentCode, markdown, dryRun)).fold(
				outcome -> ResponseEntity.status(!outcome.dryRun() && outcome.hasErrors() ? HttpStatus.BAD_REQUEST : HttpStatus.OK)
						.body(ImportResponses.Preview.from(outcome)),
				ErrorMapper::toResponse);
	}

	@GetMapping("/import/template")
	ResponseEntity<?> importTemplate(@RequestParam ItemType root, @RequestParam(defaultValue = "1") int levels,
			@RequestParam(defaultValue = "template") String format) {
		var text = "ai-instructions".equals(format) ? importTemplates.aiInstructions(root, levels) : importTemplates.template(root, levels);
		return text.<ResponseEntity<?>>map(body -> ResponseEntity.ok().contentType(org.springframework.http.MediaType.parseMediaType("text/markdown;charset=UTF-8"))
				.body(body))
				.orElseGet(() -> ErrorMapper.toResponse(new InvalidWorkItemData("levels", "There is no template for that combination.")));
	}

	@GetMapping("/{workItemCode}")
	ResponseEntity<?> get(@PathVariable UUID workItemCode) {
		return getWorkItem.execute(workItemCode).fold(item -> ResponseEntity.ok(Responses.WorkItem.from(item)), ErrorMapper::toResponse);
	}

	/**
	 * The item and its subtree as Markdown (Spanish). {@code include} is a comma list of {@code children}, {@code links},
	 * {@code comments} (default: children); {@code depth} limits the levels of children. Never the audit history.
	 */
	@GetMapping("/{workItemCode}/export")
	ResponseEntity<?> export(@PathVariable UUID workItemCode, @RequestParam(defaultValue = "markdown") String format,
			@RequestParam(defaultValue = "children") String include, @RequestParam(required = false) Integer depth) {
		if (!"markdown".equals(format)) {
			return ErrorMapper.toResponse(new InvalidWorkItemData("format", "Only markdown is supported."));
		}
		java.util.List<String> parts = java.util.Arrays.stream(include.split(",")).map(String::strip).toList();
		var options = new ExportWorkItemUseCase.Options(parts.contains("children"), parts.contains("links"), parts.contains("comments"),
				depth == null || depth < 0 ? null : depth);
		return exportWorkItem.execute(workItemCode, options).fold(
				markdown -> ResponseEntity.ok().contentType(org.springframework.http.MediaType.parseMediaType("text/markdown;charset=UTF-8"))
						.body(markdown),
				ErrorMapper::toResponse);
	}

	@PutMapping("/{workItemCode}")
	ResponseEntity<?> update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID workItemCode,
			@RequestBody Requests.UpdateWorkItem request) {
		return updateWorkItem.execute(new UpdateWorkItem(Actors.from(user), workItemCode, request.title(), request.description(),
				request.acceptanceCriteria(), request.priority(), request.effortPoints(), request.estimatedHours(), request.remainingHours()))
				.fold(item -> ResponseEntity.ok(Responses.WorkItem.from(item)), ErrorMapper::toResponse);
	}

	@DeleteMapping("/{workItemCode}")
	ResponseEntity<?> delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID workItemCode) {
		return deleteWorkItem.execute(Actors.from(user), workItemCode).fold(_ -> ResponseEntity.noContent().build(), ErrorMapper::toResponse);
	}

	/** The response carries a warning if the parent's type is not a recommended one; the change is made regardless. */
	@PutMapping("/{workItemCode}/parent")
	ResponseEntity<?> setParent(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID workItemCode,
			@RequestBody Requests.Parent request) {
		if (request.parentCode() == null) {
			return ErrorMapper.toResponse(new InvalidWorkItemData("parentCode", "Choose the parent work item (or DELETE to remove it)."));
		}
		return setParent.execute(new SetParent(Actors.from(user), workItemCode, request.parentCode()))
				.fold(item -> ResponseEntity.ok(Responses.WorkItem.from(item)), ErrorMapper::toResponse);
	}

	@DeleteMapping("/{workItemCode}/parent")
	ResponseEntity<?> removeParent(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID workItemCode) {
		return setParent.execute(new SetParent(Actors.from(user), workItemCode, null))
				.fold(item -> ResponseEntity.ok(Responses.WorkItem.from(item)), ErrorMapper::toResponse);
	}

	@PostMapping("/{workItemCode}/status")
	ResponseEntity<?> changeStatus(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID workItemCode,
			@RequestBody Requests.Status request) {
		return changeStatus.execute(new ChangeStatus(Actors.from(user), workItemCode, request.status()))
				.tap(item -> board.statusChanged(user.tenantId(), item))
				.fold(item -> ResponseEntity.ok(Responses.WorkItem.from(item)), ErrorMapper::toResponse);
	}

	/** Owners and admins only. Other boards of the project are told to reload the column. */
	@PutMapping("/{workItemCode}/rank")
	ResponseEntity<?> reorder(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID workItemCode,
			@RequestBody Requests.Rank request) {
		return reorderWorkItem.execute(new ReorderWorkItem(Actors.from(user), workItemCode, request.beforeCode()))
				.tap(item -> board.itemsReordered(user.tenantId(), item))
				.fold(item -> ResponseEntity.ok(Responses.WorkItem.from(item)), ErrorMapper::toResponse);
	}

	@PutMapping("/{workItemCode}/sprint")
	ResponseEntity<?> moveToSprint(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID workItemCode,
			@RequestBody Requests.Sprint request) {
		if (request.sprintCode() == null) {
			return ErrorMapper.toResponse(new InvalidWorkItemData("sprintCode", "Choose the sprint (or DELETE to move the item to the backlog)."));
		}
		return moveToSprint.execute(new MoveToSprint(Actors.from(user), workItemCode, request.sprintCode()))
				.fold(item -> ResponseEntity.ok(Responses.WorkItem.from(item)), ErrorMapper::toResponse);
	}

	@DeleteMapping("/{workItemCode}/sprint")
	ResponseEntity<?> moveToBacklog(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID workItemCode) {
		return moveToSprint.execute(new MoveToSprint(Actors.from(user), workItemCode, null))
				.fold(item -> ResponseEntity.ok(Responses.WorkItem.from(item)), ErrorMapper::toResponse);
	}

}
