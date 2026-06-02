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

package org.springframework.ai.tool.execution;

import java.awt.image.RenderedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Base64;
import java.util.Map;

import javax.imageio.ImageIO;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.ObjectMapper;

import org.springframework.ai.util.json.JsonParser;

/**
 * A default implementation of {@link ToolCallResultConverter}.
 *
 * @author Thomas Vitale
 * @since 1.0.0
 */
public final class DefaultToolCallResultConverter implements ToolCallResultConverter {

	private static final Logger logger = LoggerFactory.getLogger(DefaultToolCallResultConverter.class);

	private final ObjectMapper objectMapper;

	/**
	 * Default constructor. Uses {@link JsonParser#getJsonMapper()} for JSON serialization,
	 * which does not apply Spring Boot's {@code spring.jackson.default-property-inclusion}
	 * settings.
	 */
	public DefaultToolCallResultConverter() {
		this.objectMapper = JsonParser.getJsonMapper();
	}

	/**
	 * Constructor that accepts a Jackson {@link ObjectMapper} (typically Spring-managed).
	 * Allows the converter to respect Spring Boot Jackson configuration such as
	 * {@code spring.jackson.default-property-inclusion}.
	 * @param objectMapper the Jackson ObjectMapper (may be null, in which case the default
	 * mapper is used)
	 */
	public DefaultToolCallResultConverter(@Nullable ObjectMapper objectMapper) {
		this.objectMapper = objectMapper != null ? objectMapper : JsonParser.getJsonMapper();
	}

	private String toJson(@Nullable Object object) {
		if (object == null) {
			return "null";
		}
		try {
			return this.objectMapper.writeValueAsString(object);
		}
		catch (IOException ex) {
			throw new IllegalStateException("Conversion from Object to JSON failed", ex);
		}
	}

	@Override
	public String convert(@Nullable Object result, @Nullable Type returnType) {
		if (returnType == Void.TYPE) {
			logger.debug("The tool has no return type. Converting to conventional response.");
			return toJson("Done");
		}
		if (result instanceof RenderedImage) {
			final var buf = new ByteArrayOutputStream(1024 * 4);
			try {
				ImageIO.write((RenderedImage) result, "PNG", buf);
			}
			catch (IOException e) {
				return "Failed to convert tool result to a base64 image: " + e.getMessage();
			}
			final var imgB64 = Base64.getEncoder().encodeToString(buf.toByteArray());
			return toJson(Map.of("mimeType", "image/png", "data", imgB64));
		}
		else {
			logger.debug("Converting tool result to JSON.");
			return toJson(result);
		}
	}

}
