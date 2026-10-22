package io.palmisano.aibuilder.core.agent;

/** The answer: a status, and a JSON body (null: none, as for a notification's 202). */
public record McpResponse(int status, String body) {
}
