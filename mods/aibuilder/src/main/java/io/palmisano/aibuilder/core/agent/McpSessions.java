package io.palmisano.aibuilder.core.agent;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The builds the MCP server answers for, by bearer token: 256 random bits per build, local and short-lived, never
 * logged (spec §14). A revoked token gets nothing more through, so a cancelled build can't touch the world.
 */
public final class McpSessions {
	private final Map<String, McpSession> sessions = new ConcurrentHashMap<>();
	private final SecureRandom random = new SecureRandom();

	public String open(McpSession session) {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		String token = HexFormat.of().formatHex(bytes);
		sessions.put(token, session);
		return token;
	}

	public void revoke(String token) {
		sessions.remove(token);
	}

	public Optional<McpSession> find(String token) {
		return token == null ? Optional.empty() : Optional.ofNullable(sessions.get(token));
	}
}
