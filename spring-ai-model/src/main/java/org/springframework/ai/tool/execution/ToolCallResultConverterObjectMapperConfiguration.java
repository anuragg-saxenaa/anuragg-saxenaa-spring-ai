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

import tools.jackson.databind.ObjectMapper;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration that provides a {@link ToolCallResultConverter} bean backed by the
 * Spring-managed {@link ObjectMapper}.
 * <p>
 * This configuration is activated only when an {@link ObjectMapper} bean is present (i.e.,
 * when Spring Boot's Jackson auto-configuration is active). The resulting
 * {@link ToolCallResultConverter} respects Spring Boot's Jackson settings such as
 * {@code spring.jackson.default-property-inclusion}, {@code spring.jackson.serialization},
 * and any custom {@link ObjectMapper} {@code @Bean} definitions.
 * <p>
 * When no Spring-managed {@link ObjectMapper} is available (e.g., in unit tests using the
 * no-arg constructor), {@link DefaultToolCallResultConverter#DefaultToolCallResultConverter()}
 * falls back to {@link org.springframework.ai.util.json.JsonParser#getJsonMapper()}.
 *
 * @author Christian Tzolov
 * @since 2.0.0
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(ObjectMapper.class)
@ConditionalOnBean(ObjectMapper.class)
public class ToolCallResultConverterObjectMapperConfiguration {

	/**
	 * Creates a {@link ToolCallResultConverter} that delegates JSON serialization to the
	 * Spring-managed {@link ObjectMapper} bean. This allows the converter to respect Spring
	 * Boot Jackson configuration.
	 * @param objectMapper the Jackson ObjectMapper configured by Spring Boot
	 * @return a ToolCallResultConverter backed by the given ObjectMapper
	 */
	@Bean
	public ToolCallResultConverter toolCallResultConverter(ObjectMapper objectMapper) {
		return new DefaultToolCallResultConverter(objectMapper);
	}

}
