/*
 * Copyright 2023-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.ai.mcp.client.webflux.transport;

import java.lang.reflect.Method;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import reactor.util.function.Tuple2;

import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link WebClientStreamableHttpTransport} SSE parsing behavior.
 */
class WebClientStreamableHttpTransportSseParsingTest {

	@SuppressWarnings("unused")
	private final McpJsonMapper jsonMapper = McpJsonDefaults.getMapper();

	/**
	 * Regression test for issue #5780: SSE frames without explicit event: field should be
	 * accepted. Per SSE spec, omitting event: defaults to "message", but Spring AI only
	 * parsed frames where event.event() == "message" exactly.
	 */
	@Test
	void parseSseFrameWithoutEventFieldShouldBeAccepted() throws Exception {
		WebClientStreamableHttpTransport transport = WebClientStreamableHttpTransport.builder(WebClient.builder())
			.build();

		Method parseMethod = WebClientStreamableHttpTransport.class.getDeclaredMethod("parse", ServerSentEvent.class);
		parseMethod.setAccessible(true);

		// Create SSE frame WITHOUT event: field (event type is null)
		@SuppressWarnings("unchecked")
		ServerSentEvent<String> eventWithoutType = ServerSentEvent.<String>builder()
			.id("test-id-1")
			.data("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{}}")
			.build();

		var result = (Tuple2<java.util.Optional<String>, Iterable<McpSchema.JSONRPCMessage>>) parseMethod
			.invoke(transport, eventWithoutType);

		assertThat(result.getT1()).hasValue("test-id-1");
		assertThat(result.getT2()).hasSize(1);
		McpSchema.JSONRPCMessage message = result.getT2().iterator().next();
		assertThat(message).isInstanceOf(McpSchema.JSONRPCResponse.class);
		assertThat(((McpSchema.JSONRPCResponse) message).id()).isEqualTo("1");
	}

	@Test
	void parseSseFrameWithEmptyEventTypeShouldBeAccepted() throws Exception {
		WebClientStreamableHttpTransport transport = WebClientStreamableHttpTransport.builder(WebClient.builder())
			.build();

		Method parseMethod = WebClientStreamableHttpTransport.class.getDeclaredMethod("parse", ServerSentEvent.class);
		parseMethod.setAccessible(true);

		// Create SSE frame with empty event type
		@SuppressWarnings("unchecked")
		ServerSentEvent<String> eventWithEmptyType = ServerSentEvent.<String>builder()
			.id("test-id-2")
			.event("")
			.data("{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"result\":{\"success\":true}}")
			.build();

		var result = (Tuple2<java.util.Optional<String>, Iterable<McpSchema.JSONRPCMessage>>) parseMethod
			.invoke(transport, eventWithEmptyType);

		assertThat(result.getT1()).hasValue("test-id-2");
		assertThat(result.getT2()).hasSize(1);
		McpSchema.JSONRPCMessage message = result.getT2().iterator().next();
		assertThat(message).isInstanceOf(McpSchema.JSONRPCResponse.class);
		assertThat(((McpSchema.JSONRPCResponse) message).id()).isEqualTo("2");
	}

	@Test
	void parseSseFrameWithMessageEventTypeShouldBeAccepted() throws Exception {
		WebClientStreamableHttpTransport transport = WebClientStreamableHttpTransport.builder(WebClient.builder())
			.build();

		Method parseMethod = WebClientStreamableHttpTransport.class.getDeclaredMethod("parse", ServerSentEvent.class);
		parseMethod.setAccessible(true);

		// Create SSE frame with explicit "message" event type
		@SuppressWarnings("unchecked")
		ServerSentEvent<String> eventWithMessageType = ServerSentEvent.<String>builder()
			.id("test-id-3")
			.event("message")
			.data("{\"jsonrpc\":\"2.0\",\"id\":\"3\",\"result\":{\"value\":42}}")
			.build();

		var result = (Tuple2<java.util.Optional<String>, Iterable<McpSchema.JSONRPCMessage>>) parseMethod
			.invoke(transport, eventWithMessageType);

		assertThat(result.getT1()).hasValue("test-id-3");
		assertThat(result.getT2()).hasSize(1);
		McpSchema.JSONRPCMessage message = result.getT2().iterator().next();
		assertThat(message).isInstanceOf(McpSchema.JSONRPCResponse.class);
		assertThat(((McpSchema.JSONRPCResponse) message).id()).isEqualTo("3");
	}

	@Test
	void parseSseFrameWithUnknownEventTypeShouldBeIgnored() throws Exception {
		WebClientStreamableHttpTransport transport = WebClientStreamableHttpTransport.builder(WebClient.builder())
			.build();

		Method parseMethod = WebClientStreamableHttpTransport.class.getDeclaredMethod("parse", ServerSentEvent.class);
		parseMethod.setAccessible(true);

		// Create SSE frame with unknown event type - should be ignored
		@SuppressWarnings("unchecked")
		ServerSentEvent<String> eventWithUnknownType = ServerSentEvent.<String>builder()
			.id("test-id-4")
			.event("ping")
			.data("{\"jsonrpc\":\"2.0\",\"id\":\"4\",\"result\":{}}")
			.build();

		var result = (Tuple2<java.util.Optional<String>, Iterable<McpSchema.JSONRPCMessage>>) parseMethod
			.invoke(transport, eventWithUnknownType);

		assertThat(result.getT1()).isEmpty();
		assertThat(result.getT2()).isEmpty();
	}

	@Test
	void parseSseNotificationFrameWithoutEventField() throws Exception {
		WebClientStreamableHttpTransport transport = WebClientStreamableHttpTransport.builder(WebClient.builder())
			.build();

		Method parseMethod = WebClientStreamableHttpTransport.class.getDeclaredMethod("parse", ServerSentEvent.class);
		parseMethod.setAccessible(true);

		// Create SSE notification frame WITHOUT event: field
		@SuppressWarnings("unchecked")
		ServerSentEvent<String> notificationEvent = ServerSentEvent.<String>builder()
			.data("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/status\",\"params\":{\"status\":\"ok\"}}")
			.build();

		var result = (Tuple2<java.util.Optional<String>, Iterable<McpSchema.JSONRPCMessage>>) parseMethod
			.invoke(transport, notificationEvent);

		assertThat(result.getT1()).isEmpty();
		assertThat(result.getT2()).hasSize(1);
		McpSchema.JSONRPCMessage message = result.getT2().iterator().next();
		assertThat(message).isInstanceOf(McpSchema.JSONRPCNotification.class);
		assertThat(((McpSchema.JSONRPCNotification) message).method()).isEqualTo("notifications/status");
	}

}
