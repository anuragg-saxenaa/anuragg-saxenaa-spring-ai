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
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.util.Assert;

/**
 * An advisor that injects temporal context into the prompt to help LLMs understand
 * time-related information. This addresses the common issue where LLMs don't track
 * elapsed time well and may confidently give wrong answers about temporal context.
 *
 * <p>
 * The advisor injects the following temporal information:
 * <ul>
 * <li>Current ISO timestamp</li>
 * <li>Relative human-readable time description (e.g., "3 days ago")</li>
 * <li>Session duration if greater than 5 minutes</li>
 * </ul>
 *
 * @author Anurag Saxena
 * @since 1.0.0
 */
public class TimeAwareAdvisor implements BaseAdvisor {

	/**
	 * Context key for the current timestamp (ISO-8601 format).
	 */
	public static final String CURRENT_TIMESTAMP = "timeaware_current_timestamp";

	/**
	 * Context key for the relative time description.
	 */
	public static final String RELATIVE_TIME = "timeaware_relative_time";

	/**
	 * Context key for the session duration (if applicable).
	 */
	public static final String SESSION_DURATION = "timeaware_session_duration";

	/**
	 * Context key for the last interaction timestamp (for calculating relative time).
	 */
	public static final String LAST_INTERACTION = "timeaware_last_interaction";

	/**
	 * Default minimum duration threshold for reporting session duration (5 minutes).
	 */
	public static final Duration DEFAULT_SESSION_DURATION_THRESHOLD = Duration.ofMinutes(5);

	/**
	 * Default zone ID for timestamp generation.
	 */
	public static final ZoneId DEFAULT_ZONE_ID = ZoneId.systemDefault();

	private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_INSTANT;

	private final ZoneId zoneId;

	private final Duration sessionDurationThreshold;

	private final Duration sessionDuration;

	private final Instant lastInteraction;

	/**
	 * Creates a new TimeAwareAdvisor with default settings.
	 */
	public TimeAwareAdvisor() {
		this(DEFAULT_ZONE_ID, DEFAULT_SESSION_DURATION_THRESHOLD);
	}

	/**
	 * Creates a new TimeAwareAdvisor with custom zone ID.
	 * @param zoneId the zone ID for timestamp generation
	 */
	public TimeAwareAdvisor(ZoneId zoneId) {
		this(zoneId, DEFAULT_SESSION_DURATION_THRESHOLD);
	}

	/**
	 * Creates a new TimeAwareAdvisor with custom settings.
	 * @param zoneId the zone ID for timestamp generation
	 * @param sessionDurationThreshold minimum duration threshold for reporting session
	 * duration
	 */
	public TimeAwareAdvisor(ZoneId zoneId, Duration sessionDurationThreshold) {
		this.zoneId = zoneId;
		this.sessionDurationThreshold = sessionDurationThreshold;
		this.sessionDuration = null;
		this.lastInteraction = null;
	}

	private TimeAwareAdvisor(ZoneId zoneId, Duration sessionDurationThreshold, Duration sessionDuration,
			Instant lastInteraction) {
		this.zoneId = zoneId;
		this.sessionDurationThreshold = sessionDurationThreshold;
		this.sessionDuration = sessionDuration;
		this.lastInteraction = lastInteraction;
	}

	@Override
	public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
		Assert.notNull(chatClientRequest, "chatClientRequest cannot be null");
		Assert.notNull(advisorChain, "advisorChain cannot be null");

		Instant now = Instant.now();

		// Get current timestamp
		String currentTimestamp = generateTimestamp(now);

		// Calculate relative time
		String relativeTime = "just now";
		if (this.lastInteraction != null) {
			relativeTime = generateRelativeTime(this.lastInteraction, now);
		}

		// Calculate session duration
		String sessionDurationStr = null;
		if (this.sessionDuration != null && !this.sessionDuration.isZero()
				&& this.sessionDuration.compareTo(this.sessionDurationThreshold) >= 0) {
			sessionDurationStr = formatDuration(this.sessionDuration);
		}

		// Build temporal context string
		StringBuilder temporalContext = new StringBuilder();
		temporalContext.append("[Temporal Context]\n");
		temporalContext.append("- Current time: ").append(currentTimestamp).append("\n");
		temporalContext.append("- Relative to last interaction: ").append(relativeTime).append("\n");
		if (sessionDurationStr != null) {
			temporalContext.append("- Session duration: ").append(sessionDurationStr).append("\n");
		}
		temporalContext.append("[/Temporal Context]\n\n");

		// Update context map
		Map<String, Object> context = new HashMap<>(chatClientRequest.context());
		context.put(CURRENT_TIMESTAMP, currentTimestamp);
		context.put(RELATIVE_TIME, relativeTime);
		if (sessionDurationStr != null) {
			context.put(SESSION_DURATION, sessionDurationStr);
		}
		context.put(LAST_INTERACTION, now);

		// Prepend temporal context to user message
		UserMessage userMessage = chatClientRequest.prompt().getUserMessage();
		String originalText = userMessage.getText() != null ? userMessage.getText() : "";
		String augmentedText = temporalContext.toString() + originalText;

		return chatClientRequest.mutate()
			.prompt(chatClientRequest.prompt().mutate()
				.messages(chatClientRequest.prompt()
					.getMessages()
					.stream()
					.map(msg -> msg instanceof UserMessage um ? new UserMessage(augmentedText,
							um.getMedia(), um.getAnnotations()) : msg)
					.toList())
				.build())
			.context(context)
			.build();
	}

	@Override
	public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
		return chatClientResponse;
	}

	/**
	 * Generates an ISO-8601 formatted timestamp.
	 */
	private String generateTimestamp(Instant instant) {
		return ISO_FORMATTER.format(instant);
	}

	/**
	 * Generates a human-readable relative time description.
	 */
	private String generateRelativeTime(Instant past, Instant now) {
		long seconds = ChronoUnit.SECONDS.between(past, now);
		long minutes = seconds / 60;
		long hours = minutes / 60;
		long days = hours / 24;

		if (days > 0) {
			return days + " day" + (days > 1 ? "s" : "") + " ago";
		}
		else if (hours > 0) {
			return hours + " hour" + (hours > 1 ? "s" : "") + " ago";
		}
		else if (minutes > 0) {
			return minutes + " minute" + (minutes > 1 ? "s" : "") + " ago";
		}
		else {
			return "just now";
		}
	}

	/**
	 * Formats a duration as a human-readable string.
	 */
	private String formatDuration(Duration duration) {
		long hours = duration.toHours();
		long minutes = duration.toMinutesPart();

		if (hours > 0 && minutes > 0) {
			return hours + " hour" + (hours > 1 ? "s" : "") + " and " + minutes + " minute"
					+ (minutes > 1 ? "s" : "");
		}
		else if (hours > 0) {
			return hours + " hour" + (hours > 1 ? "s" : "");
		}
		else if (minutes > 0) {
			return minutes + " minute" + (minutes > 1 ? "s" : "");
		}
		else {
			long seconds = duration.toSecondsPart();
			return seconds + " second" + (seconds > 1 ? "s" : "");
		}
	}

	/**
	 * Creates a builder for constructing a TimeAwareAdvisor.
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for TimeAwareAdvisor.
	 */
	public static final class Builder {

		private ZoneId zoneId = DEFAULT_ZONE_ID;

		private Duration sessionDurationThreshold = DEFAULT_SESSION_DURATION_THRESHOLD;

		private @Nullable Duration sessionDuration;

		private @Nullable Instant lastInteraction;

		private Builder() {
		}

		/**
		 * Sets the zone ID for timestamp generation.
		 * @param zoneId the zone ID
		 * @return this builder
		 */
		public Builder zoneId(ZoneId zoneId) {
			this.zoneId = zoneId;
			return this;
		}

		/**
		 * Sets the minimum duration threshold for reporting session duration.
		 * @param threshold the minimum duration
		 * @return this builder
		 */
		public Builder sessionDurationThreshold(Duration threshold) {
			this.sessionDurationThreshold = threshold;
			return this;
		}

		/**
		 * Sets the session duration to be reported.
		 * @param duration the session duration
		 * @return this builder
		 */
		public Builder sessionDuration(Duration duration) {
			this.sessionDuration = duration;
			return this;
		}

		/**
		 * Sets the last interaction time for calculating relative time.
		 * @param lastInteraction the last interaction instant
		 * @return this builder
		 */
		public Builder lastInteraction(Instant lastInteraction) {
			this.lastInteraction = lastInteraction;
			return this;
		}

		/**
		 * Builds a new TimeAwareAdvisor.
		 * @return a new TimeAwareAdvisor instance
		 */
		public TimeAwareAdvisor build() {
			return new TimeAwareAdvisor(this.zoneId, this.sessionDurationThreshold, this.sessionDuration,
					this.lastInteraction);
		}

	}

}
