package com.sprintmodus.workitem_service.adapter.websocket;

import java.util.UUID;

/** Someone with a board open. The email comes from the JWT; a display name would cost a database read per connection. */
public record OnlineUser(UUID userCode, String email) {
}
