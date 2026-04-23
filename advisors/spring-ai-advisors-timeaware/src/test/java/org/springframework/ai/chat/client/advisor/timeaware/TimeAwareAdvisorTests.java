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

package org.springframework.ai.chat.client.advisor.timeaware;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link TimeAwareAdvisor}.
 *
 * @author Anurag Saxena
 * @since 1.0.0
 */
public class TimeAwareAdvisorTests {

	private static final ZoneOffset UTC = ZoneOffset.UTC;

	@Test
	void shouldInjectCurrentTimestamp() {
		TimeAwareAdvisor advisor = new TimeAwareAdvisor(UTC);
		ChatClientRequest request = createRequest("Hello");

		ChatClientRequest result = advisor.before(request, mockAdvisorChain());

		assertThat(result.context()).containsKey(TimeAwareAdvisor.CURRENT_TIMESTAMP);
		String timestamp = (String) result.context().get(TimeAwareAdvisor.CURRENT_TIMESTAMP);
		assertThat(timestamp).isNotNull();
		assertThat(Instant.parse(timestamp)).isNotNull();
	}

	@Test
	void shouldInjectRelativeTimeForRecentInteraction() {
		Instant threeMinutesAgo = Instant.now().minus(Duration.ofMinutes(3));
		TimeAwareAdvisor advisor = TimeAwareAdvisor.builder()
			.lastInteraction(threeMinutesAgo)
			.zoneId(UTC)
			.build();
		ChatClientRequest request = createRequest("Hello");

		ChatClientRequest result = advisor.before(request, mockAdvisorChain());

		assertThat(result.context()).containsKey(TimeAwareAdvisor.RELATIVE_TIME);
		String relativeTime = (String) result.context().get(TimeAwareAdvisor.RELATIVE_TIME);
		assertThat(relativeTime).isEqualTo("3 minutes ago");
	}

	@Test
	void shouldInjectRelativeTimeForDaysAgo() {
		Instant fiveDaysAgo = Instant.now().minus(Duration.ofDays(5));
		TimeAwareAdvisor advisor = TimeAwareAdvisor.builder()
			.lastInteraction(fiveDaysAgo)
			.zoneId(UTC)
			.build();
		ChatClientRequest request = createRequest("Hello");

		ChatClientRequest result = advisor.before(request, mockAdvisorChain());

		assertThat(result.context()).containsKey(TimeAwareAdvisor.RELATIVE_TIME);
		String relativeTime = (String) result.context().get(TimeAwareAdvisor.RELATIVE_TIME);
		assertThat(relativeTime).isEqualTo("5 days ago");
	}

	@Test
	void shouldShowJustNowWhenNoLastInteraction() {
		TimeAwareAdvisor advisor = new TimeAwareAdvisor(UTC);
		ChatClientRequest request = createRequest("Hello");

		ChatClientRequest result = advisor.before(request, mockAdvisorChain());

		assertThat(result.context()).containsKey(TimeAwareAdvisor.RELATIVE_TIME);
		String relativeTime = (String) result.context().get(TimeAwareAdvisor.RELATIVE_TIME);
		assertThat(relativeTime).isEqualTo("just now");
	}

	@Test
	void shouldInjectSessionDurationWhenThresholdExceeded() {
		Duration sessionDuration = Duration.ofMinutes(15);
		TimeAwareAdvisor advisor = TimeAwareAdvisor.builder()
			.sessionDuration(sessionDuration)
			.sessionDurationThreshold(Duration.ofMinutes(5))
			.zoneId(UTC)
			.build();
		ChatClientRequest request = createRequest("Hello");

		ChatClientRequest result = advisor.before(request, mockAdvisorChain());

		assertThat(result.context()).containsKey(TimeAwareAdvisor.SESSION_DURATION);
		String sessionDurationStr = (String) result.context().get(TimeAwareAdvisor.SESSION_DURATION);
		assertThat(sessionDurationStr).contains("15 minutes");
	}

	@Test
	void shouldNotInjectSessionDurationWhenBelowThreshold() {
		Duration sessionDuration = Duration.ofMinutes(2);
		TimeAwareAdvisor advisor = TimeAwareAdvisor.builder()
			.sessionDuration(sessionDuration)
			.sessionDurationThreshold(Duration.ofMinutes(5))
			.zoneId(UTC)
			.build();
		ChatClientRequest request = createRequest("Hello");

		ChatClientRequest result = advisor.before(request, mockAdvisorChain());

		assertThat(result.context()).doesNotContainKey(TimeAwareAdvisor.SESSION_DURATION);
	}

	@Test
	void shouldPrependTemporalContextToUserMessage() {
		TimeAwareAdvisor advisor = new TimeAwareAdvisor(UTC);
		ChatClientRequest request = createRequest("What is the weather?");

		ChatClientRequest result = advisor.before(request, mockAdvisorChain());

		Message userMessage = result.prompt().getUserMessage();
		assertThat(userMessage.getText()).contains("[Temporal Context]");
		assertThat(userMessage.getText()).contains("What is the weather?");
		assertThat(userMessage.getText()).startsWith("[Temporal Context]");
	}

	@Test
	void shouldUpdateLastInteractionAfterCall() {
		TimeAwareAdvisor advisor = new TimeAwareAdvisor(UTC);
		ChatClientRequest request = createRequest("Hello");

		ChatClientRequest result = advisor.before(request, mockAdvisorChain());

		assertThat(result.context()).containsKey(TimeAwareAdvisor.LAST_INTERACTION);
		assertThat(result.context().get(TimeAwareAdvisor.LAST_INTERACTION)).isInstanceOf(Instant.class);
	}

	@Test
	void shouldHandleNullUserMessageText() {
		TimeAwareAdvisor advisor = new TimeAwareAdvisor(UTC);
		ChatClientRequest request = createRequest(null);

		ChatClientRequest result = advisor.before(request, mockAdvisorChain());

		assertThat(result.prompt().getUserMessage().getText()).contains("[Temporal Context]");
	}

	@Test
	void shouldPreserveExistingContextEntries() {
		TimeAwareAdvisor advisor = new TimeAwareAdvisor(UTC);
		Map<String, Object> existingContext = new HashMap<>();
		existingContext.put("custom_key", "custom_value");
		ChatClientRequest request = createRequest("Hello", existingContext);

		ChatClientRequest result = advisor.before(request, mockAdvisorChain());

		assertThat(result.context()).containsEntry("custom_key", "custom_value");
		assertThat(result.context()).containsKey(TimeAwareAdvisor.CURRENT_TIMESTAMP);
	}

	@Test
	void shouldFormatHoursAndMinutesSessionDuration() {
		Duration sessionDuration = Duration.ofHours(1).plusMinutes(30);
		TimeAwareAdvisor advisor = TimeAwareAdvisor.builder()
			.sessionDuration(sessionDuration)
			.sessionDurationThreshold(Duration.ofMinutes(5))
			.zoneId(UTC)
			.build();
		ChatClientRequest request = createRequest("Hello");

		ChatClientRequest result = advisor.before(request, mockAdvisorChain());

		String sessionDurationStr = (String) result.context().get(TimeAwareAdvisor.SESSION_DURATION);
		assertThat(sessionDurationStr).isEqualTo("1 hour and 30 minutes");
	}

	@Test
	void shouldHandlePastInteractionExactlyNow() {
		Instant justNow = Instant.now();
		TimeAwareAdvisor advisor = TimeAwareAdvisor.builder()
			.lastInteraction(justNow)
			.zoneId(UTC)
			.build();
		ChatClientRequest request = createRequest("Hello");

		ChatClientRequest result = advisor.before(request, mockAdvisorChain());

		String relativeTime = (String) result.context().get(TimeAwareAdvisor.RELATIVE_TIME);
		assertThat(relativeTime).isEqualTo("just now");
	}

	@Test
	void shouldHandleHoursAgo() {
		Instant twoHoursAgo = Instant.now().minus(Duration.ofHours(2));
		TimeAwareAdvisor advisor = TimeAwareAdvisor.builder()
			.lastInteraction(twoHoursAgo)
			.zoneId(UTC)
			.build();
		ChatClientRequest request = createRequest("Hello");

		ChatClientRequest result = advisor.before(request, mockAdvisorChain());

		String relativeTime = (String) result.context().get(TimeAwareAdvisor.RELATIVE_TIME);
		assertThat(relativeTime).isEqualTo("2 hours ago");
	}

	// Helper methods

	private ChatClientRequest createRequest(String userText) {
		return createRequest(userText, new HashMap<>());
	}

	private ChatClientRequest createRequest(String userText, Map<String, Object> context) {
		Prompt prompt = new Prompt(new UserMessage(userText != null ? userText : ""));
		return ChatClientRequest.builder().prompt(prompt).context(context).build();
	}

	@SuppressWarnings("unchecked")
	private AdvisorChain mockAdvisorChain() {
		AdvisorChain chain = mock(AdvisorChain.class);
		when(chain.nextCall(any(ChatClientRequest.class))).thenReturn(mock(ChatClientRequest.class));
		return chain;
	}

}
