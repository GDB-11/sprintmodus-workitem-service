package com.sprintmodus.workitem_service.integration;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Stands in for project-service over real HTTP: knows the sprints the test registers, records the velocities pushed to it
 * and can be switched off. Like the real one it refuses velocity updates of closed sprints with 409.
 */
final class ProjectServiceStub {

	record Sprint(UUID projectCode, String status) {
	}

	record Call(String method, String path, String authorization, String body) {
	}

	static final ProjectServiceStub INSTANCE = new ProjectServiceStub();

	private final HttpServer server;

	private final Map<UUID, Sprint> sprints = new ConcurrentHashMap<>();

	private final java.util.Set<UUID> projects = ConcurrentHashMap.newKeySet();

	private final Map<UUID, Integer> velocities = new ConcurrentHashMap<>();

	private final List<Call> calls = new CopyOnWriteArrayList<>();

	private volatile boolean down;

	private ProjectServiceStub() {
		try {
			server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			server.createContext("/", this::handle);
			server.start();
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	int port() {
		return server.getAddress().getPort();
	}

	void register(UUID sprintCode, UUID projectCode, String status) {
		sprints.put(sprintCode, new Sprint(projectCode, status));
	}

	void registerProject(UUID projectCode) {
		projects.add(projectCode);
	}

	/** The velocity last pushed for a sprint, or {@code null} if none was. */
	Integer velocityOf(UUID sprintCode) {
		return velocities.get(sprintCode);
	}

	List<Call> calls() {
		return calls;
	}

	void down(boolean down) {
		this.down = down;
	}

	private void handle(HttpExchange exchange) throws IOException {
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		String path = exchange.getRequestURI().getPath();
		calls.add(new Call(exchange.getRequestMethod(), path, exchange.getRequestHeaders().getFirst("Authorization"), body));
		if (down) {
			exchange.sendResponseHeaders(503, -1);
			exchange.close();
			return;
		}
		String[] parts = path.split("/");
		UUID code = UUID.fromString(parts[3]);
		if ("projects".equals(parts[2])) {
			if (projects.contains(code)) {
				byte[] json = ("{\"projectCode\":\"" + code + "\",\"name\":\"Project\",\"key\":\"PRJ\"}").getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				exchange.sendResponseHeaders(200, json.length);
				exchange.getResponseBody().write(json);
			}
			else {
				exchange.sendResponseHeaders(404, -1);
			}
			exchange.close();
			return;
		}
		Sprint sprint = sprints.get(code);
		if (sprint == null) {
			exchange.sendResponseHeaders(404, -1);
		}
		else if (path.endsWith("/velocity")) {
			if ("CLOSED".equals(sprint.status())) {
				exchange.sendResponseHeaders(409, -1);
			}
			else {
				velocities.put(code, Integer.parseInt(body.replaceAll("[^0-9]", "")));
				exchange.sendResponseHeaders(204, -1);
			}
		}
		else {
			byte[] json = ("{\"sprintCode\":\"" + code + "\",\"projectCode\":\"" + sprint.projectCode() + "\",\"name\":\"Sprint\",\"status\":\""
					+ sprint.status() + "\",\"startDate\":\"2026-01-05\",\"endDate\":\"2026-01-19\",\"velocity\":0}")
					.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, json.length);
			exchange.getResponseBody().write(json);
		}
		exchange.close();
	}

}
