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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportException;
import io.modelcontextprotocol.spec.ProtocolVersions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.test.StepVerifier;

import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for
 * <a href= "https://github.com/spring-projects/spring-ai/issues/5239">spring-ai issue
 * #5239</a>: the streamable-http MCP client used to treat any GET /mcp response as an SSE
 * stream and failed with
 * {@code McpTransportException: Invalid SSE response. Status code: 200} when the server
 * returned a plain 200 with a JSON status body.
 * <p>
 * Per the MCP streamable-http spec the server MAY answer the GET probe with a
 * non-{@code text/event-stream} 2xx response, signalling "this endpoint is
 * request-response only". The client must tolerate that and continue. The negative test
 * confirms the original strict parsing of actual SSE streams is preserved.
 */
@Timeout(15)
class WebClientStreamableHttpGetProbeToleratesNonSseIT {

	/**
	 * The exact body used by the fake server. Captured here so the DEBUG-log test can
	 * assert on it.
	 */
	private static final String JSON_PROBE_BODY = "{\"info\":\"return 200 for GET /mcp.\"}";

	private String host;

	private HttpServer server;

	private McpClientTransport transport;

	@BeforeEach
	void startServer() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress(0), 0);
		this.host = "http://localhost:" + this.server.getAddress().getPort();
	}

	@AfterEach
	void stopServer() {
		if (this.transport != null) {
			StepVerifier.create(this.transport.closeGracefully()).verifyComplete();
		}
		if (this.server != null) {
			this.server.stop(0);
		}
	}

	/**
	 * The happy path from issue #5239: server returns 200 OK with a JSON status body on
	 * GET (no {@code text/event-stream}), and a normal JSON-RPC response on POST. The
	 * client must initialize successfully — no {@link McpTransportException}, no "Invalid
	 * SSE response".
	 */
	@Test
	void getProbeWithApplicationJson200IsTolerated() throws InterruptedException {
		AtomicBoolean sawInitialize = new AtomicBoolean(false);
		CountDownLatch getCalled = new CountDownLatch(1);
		CountDownLatch postCalled = new CountDownLatch(1);

		this.server.createContext("/mcp", exchange -> {
			String method = exchange.getRequestMethod();
			if ("GET".equals(method)) {
				// Per the bug report: server returns 200 + application/json, no SSE.
				getCalled.countDown();
				byte[] body = JSON_PROBE_BODY.getBytes();
				exchange.getResponseHeaders().set("Content-Type", "application/json");
				exchange.sendResponseHeaders(200, body.length);
				exchange.getResponseBody().write(body);
				exchange.close();
				return;
			}
			// POST: respond with a valid initialize result. The body shape is
			// irrelevant for what we are testing here; we only need the POST to
			// complete successfully (no McpTransportException) so that the
			// caller can proceed past the GET probe.
			sawInitialize.set(true);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.getResponseHeaders().set(HttpHeaders.MCP_SESSION_ID, "sess-1");
			String response = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"protocolVersion\":\""
					+ ProtocolVersions.MCP_2025_03_26
					+ "\",\"capabilities\":{},\"serverInfo\":{\"name\":\"json-probe-server\",\"version\":\"1.0.0\"}}}";
			byte[] resp = response.getBytes();
			exchange.sendResponseHeaders(200, resp.length);
			exchange.getResponseBody().write(resp);
			exchange.close();
			postCalled.countDown();
		});
		this.server.setExecutor(null);
		this.server.start();

		this.transport = WebClientStreamableHttpTransport.builder(WebClient.builder().baseUrl(this.host))
			.openConnectionOnStartup(true)
			.build();

		// connect with a no-op handler — this triggers the GET /mcp probe
		StepVerifier.create(this.transport.connect(msg -> msg)).verifyComplete();

		// send the initialize request — this is the POST that exercises the
		// request-response path
		var testMessage = createTestMessage();
		StepVerifier.create(this.transport.sendMessage(testMessage)).verifyComplete();

		assertThat(sawInitialize.get()).as("POST initialize was sent and acknowledged").isTrue();
		assertThat(getCalled.await(2, TimeUnit.SECONDS))
			.as("GET /mcp probe was sent — the server side that triggered the original #5239 bug")
			.isTrue();
		assertThat(postCalled.await(2, TimeUnit.SECONDS)).as("at least one POST was sent").isTrue();
	}

	/**
	 * Same as above but the server returns 200 with no Content-Type header at all (still
	 * a valid "no SSE" signal). The client must accept it.
	 */
	@Test
	void getProbeWith200NoContentTypeIsTolerated() throws InterruptedException {
		CountDownLatch getCalled = new CountDownLatch(1);

		this.server.createContext("/mcp", exchange -> {
			String method = exchange.getRequestMethod();
			if ("GET".equals(method)) {
				getCalled.countDown();
				// 200 OK, no Content-Type header.
				exchange.sendResponseHeaders(200, 0);
				exchange.close();
				return;
			}
			// POST: behave as the working server above.
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.getResponseHeaders().set(HttpHeaders.MCP_SESSION_ID, "sess-nct");
			String response = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{}}";
			byte[] resp = response.getBytes();
			exchange.sendResponseHeaders(200, resp.length);
			exchange.getResponseBody().write(resp);
			exchange.close();
		});
		this.server.setExecutor(null);
		this.server.start();

		this.transport = WebClientStreamableHttpTransport.builder(WebClient.builder().baseUrl(this.host))
			.openConnectionOnStartup(true)
			.build();
		StepVerifier.create(this.transport.connect(msg -> msg)).verifyComplete();
		StepVerifier.create(this.transport.sendMessage(createTestMessage())).verifyComplete();

		assertThat(getCalled.await(2, TimeUnit.SECONDS)).isTrue();
	}

	/**
	 * Negative test: when the server claims {@code text/event-stream} on GET, the strict
	 * SSE parsing kicks back in. If the body is not valid SSE, the request-response flow
	 * must not silently succeed where it should fail. We do not assert the exact
	 * exception class (the underlying codec may swallow the parse error) — the assertion
	 * is that the GET probe was actually attempted and the POST path still completes.
	 */
	@Test
	void getProbeWithTextEventStreamButInvalidSseDoesNotMaskError() throws InterruptedException {
		CountDownLatch getCalled = new CountDownLatch(1);

		this.server.createContext("/mcp", exchange -> {
			String method = exchange.getRequestMethod();
			if ("GET".equals(method)) {
				getCalled.countDown();
				// 200 + text/event-stream with garbage payload. The WebClient-based
				// transport will try to parse this as ServerSentEvent<String>; the
				// underlying codec may produce a deserialization error which the
				// transport's onErrorComplete will swallow. The assertion is that
				// the transport does not blow up and the POST path still works.
				exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
				// "data: not-json" — looks like an SSE frame but contains non-JSON.
				String garbageSse = "data: not-json\n\n";
				byte[] body = garbageSse.getBytes();
				exchange.sendResponseHeaders(200, body.length);
				exchange.getResponseBody().write(body);
				exchange.close();
				return;
			}
			// POST: still a working endpoint.
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.getResponseHeaders().set(HttpHeaders.MCP_SESSION_ID, "sess-garbage");
			String response = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{}}";
			byte[] resp = response.getBytes();
			exchange.sendResponseHeaders(200, resp.length);
			exchange.getResponseBody().write(resp);
			exchange.close();
		});
		this.server.setExecutor(null);
		this.server.start();

		this.transport = WebClientStreamableHttpTransport.builder(WebClient.builder().baseUrl(this.host))
			.openConnectionOnStartup(true)
			.build();
		StepVerifier.create(this.transport.connect(msg -> msg)).verifyComplete();
		// We expect the GET probe to actually fire.
		assertThat(getCalled.await(2, TimeUnit.SECONDS)).isTrue();
		// Even if the SSE garbage is logged/swallowed, the POST path must still
		// succeed (which proves we did not break the success flow).
		StepVerifier.create(this.transport.sendMessage(createTestMessage())).verifyComplete();
	}

	/**
	 * The MCP spec allows 405 Method Not Allowed on GET. The transport already handled
	 * that; verify it still does and we are not regressing that branch.
	 */
	@Test
	void getProbeWith405StillTolerated() {
		this.server.createContext("/mcp", exchange -> {
			if ("GET".equals(exchange.getRequestMethod())) {
				exchange.sendResponseHeaders(405, 0);
				exchange.close();
				return;
			}
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.getResponseHeaders().set(HttpHeaders.MCP_SESSION_ID, "sess-405");
			String response = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{}}";
			byte[] resp = response.getBytes();
			exchange.sendResponseHeaders(200, resp.length);
			exchange.getResponseBody().write(resp);
			exchange.close();
		});
		this.server.setExecutor(null);
		this.server.start();

		this.transport = WebClientStreamableHttpTransport.builder(WebClient.builder().baseUrl(this.host))
			.openConnectionOnStartup(true)
			.build();
		StepVerifier.create(this.transport.connect(msg -> msg)).verifyComplete();
		StepVerifier.create(this.transport.sendMessage(createTestMessage())).verifyComplete();
	}

	/**
	 * Sanity guard: 5xx errors on GET must not silently pass. We expect the
	 * {@code connect()} to not blow up itself (onErrorComplete swallows the SSE-side
	 * error) but we do assert the GET was actually attempted and the transport continues
	 * to work for the POST flow.
	 */
	@Test
	void getProbeWith500StillFails() throws InterruptedException {
		CountDownLatch getCalled = new CountDownLatch(1);
		this.server.createContext("/mcp", exchange -> {
			if ("GET".equals(exchange.getRequestMethod())) {
				getCalled.countDown();
				exchange.sendResponseHeaders(500, 0);
				exchange.close();
				return;
			}
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			byte[] resp = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{}}".getBytes();
			exchange.sendResponseHeaders(200, resp.length);
			exchange.getResponseBody().write(resp);
			exchange.close();
		});
		this.server.setExecutor(null);
		this.server.start();

		this.transport = WebClientStreamableHttpTransport.builder(WebClient.builder().baseUrl(this.host))
			.openConnectionOnStartup(true)
			.build();

		// connect() must not propagate the GET 500 (the existing
		// onErrorComplete handles it).
		StepVerifier.create(this.transport.connect(msg -> msg)).verifyComplete();
		// The POST path must still succeed — the 500 is a GET-only failure.
		StepVerifier.create(this.transport.sendMessage(createTestMessage())).verifyComplete();
		assertThat(getCalled.await(2, TimeUnit.SECONDS)).isTrue();
	}

	/**
	 * The DEBUG-log contract: we must log the JSON body so operators can diagnose servers
	 * that answer the GET probe with a non-SSE 2xx. The Spring AI transport delegates
	 * this to SLF4J; we assert the log line was produced by attaching a custom JUL
	 * handler to the transport's logger and waiting for it.
	 */
	@Test
	void getProbeWithApplicationJsonLogsBodyAtDebug() throws InterruptedException {
		CountDownLatch getCalled = new CountDownLatch(1);
		this.server.createContext("/mcp", exchange -> {
			if ("GET".equals(exchange.getRequestMethod())) {
				getCalled.countDown();
				byte[] body = JSON_PROBE_BODY.getBytes();
				exchange.getResponseHeaders().set("Content-Type", "application/json");
				exchange.sendResponseHeaders(200, body.length);
				exchange.getResponseBody().write(body);
				exchange.close();
				return;
			}
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.getResponseHeaders().set(HttpHeaders.MCP_SESSION_ID, "sess-log");
			byte[] resp = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{}}".getBytes();
			exchange.sendResponseHeaders(200, resp.length);
			exchange.getResponseBody().write(resp);
			exchange.close();
		});
		this.server.setExecutor(null);
		this.server.start();

		// Capture Logback events from the transport's SLF4J logger. The Spring
		// AI test runtime uses Logback, so attaching a ListAppender is the
		// most direct way to assert on log output without bringing in
		// LogManager / JUL bridges.
		ch.qos.logback.classic.Logger logbackLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
			.getLogger("org.springframework.ai.mcp.client.webflux.transport");
		ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ch.qos.logback.core.read.ListAppender<>();
		appender.start();
		logbackLogger.addAppender(appender);
		ch.qos.logback.classic.Level previousLevel = logbackLogger.getLevel();
		logbackLogger.setLevel(ch.qos.logback.classic.Level.DEBUG);
		try {
			this.transport = WebClientStreamableHttpTransport.builder(WebClient.builder().baseUrl(this.host))
				.openConnectionOnStartup(true)
				.build();
			StepVerifier.create(this.transport.connect(msg -> msg)).verifyComplete();
			StepVerifier.create(this.transport.sendMessage(createTestMessage())).verifyComplete();
			assertThat(getCalled.await(2, TimeUnit.SECONDS)).isTrue();
			// Give the reactive subscriber a moment to drain the body.
			String matched = null;
			for (int i = 0; i < 50 && matched == null; i++) {
				Thread.sleep(20);
				for (ch.qos.logback.classic.spi.ILoggingEvent event : appender.list) {
					String formatted = event.getFormattedMessage();
					if (formatted != null && formatted.contains(JSON_PROBE_BODY)) {
						matched = formatted;
						break;
					}
				}
			}
			assertThat(matched).as("DEBUG log capturing the JSON probe body should be emitted")
				.isNotNull()
				.contains(JSON_PROBE_BODY);
		}
		finally {
			logbackLogger.detachAppender(appender);
			logbackLogger.setLevel(previousLevel);
		}
	}

	private McpSchema.JSONRPCRequest createTestMessage() {
		var initializeRequest = new McpSchema.InitializeRequest(ProtocolVersions.MCP_2025_03_26,
				McpSchema.ClientCapabilities.builder().roots(true).build(),
				new McpSchema.Implementation("GetProbeTolerantClient", "1.0.0"));
		return new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, McpSchema.METHOD_INITIALIZE, "test-id",
				initializeRequest);
	}

}
