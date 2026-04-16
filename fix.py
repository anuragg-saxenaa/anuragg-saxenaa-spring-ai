import re

with open('models/spring-ai-google-genai/src/main/java/org/springframework/ai/google/genai/GoogleGenAiChatModel.java', 'r') as f:
    content = f.read()

# Find the old code block and replace it
old_code = '''		boolean isFunctionCall = candidate.content().isPresent() && candidate.content().get().parts().isPresent()
				&& candidate.content().get().parts().get().stream().anyMatch(part -> part.functionCall().isPresent());

		if (isFunctionCall) {
			List<AssistantMessage.ToolCall> assistantToolCalls = candidate.content()
				.get()
				.parts()
				.orElse(List.of())
				.stream()
				.filter(part -> part.functionCall().isPresent())
				.map(part -> {
					FunctionCall functionCall = part.functionCall().get();
					var functionName = functionCall.name().orElse("");
					String functionArguments = mapToJson(functionCall.args().orElse(Map.of()));
					return new AssistantMessage.ToolCall("", "function", functionName, functionArguments);
				})
				.toList();

			AssistantMessage assistantMessage = AssistantMessage.builder()
				.content("")
				.properties(messageMetadata)
				.toolCalls(assistantToolCalls)
				.build();

			return List.of(new Generation(assistantMessage, chatGenerationMetadata));
		}'''

new_code = '''		boolean isFunctionCall = candidate.content().isPresent() && candidate.content().get().parts().isPresent()
				&& candidate.content().get().parts().get().stream().anyMatch(part -> part.functionCall().isPresent());

		// Check for mixed modality: both text content AND function calls in the same response
		boolean hasTextContent = candidate.content().isPresent() && candidate.content().get().parts().isPresent()
				&& candidate.content().get().parts().get().stream().anyMatch(part -> part.text().isPresent() && !part.text().get().isEmpty());

		if (isFunctionCall) {
			List<AssistantMessage.ToolCall> assistantToolCalls = candidate.content()
				.get()
				.parts()
				.orElse(List.of())
				.stream()
				.filter(part -> part.functionCall().isPresent())
				.map(part -> {
					FunctionCall functionCall = part.functionCall().get();
					var functionName = functionCall.name().orElse("");
					String functionArguments = mapToJson(functionCall.args().orElse(Map.of()));
					return new AssistantMessage.ToolCall("", "function", functionName, functionArguments);
				})
				.toList();

			// Extract text content from parts (for mixed modality case)
			String textContent = "";
			if (hasTextContent) {
				textContent = candidate.content()
					.get()
					.parts()
					.orElse(List.of())
					.stream()
					.filter(part -> part.text().isPresent())
					.map(part -> part.text().get())
					.filter(t -> !t.isEmpty())
					.collect(java.util.stream.Collectors.joining("\\n"));
			}

			AssistantMessage assistantMessage = AssistantMessage.builder()
				.content(textContent)
				.properties(messageMetadata)
				.toolCalls(assistantToolCalls)
				.build();

			return List.of(new Generation(assistantMessage, chatGenerationMetadata));
		}'''

content = content.replace(old_code, new_code)

with open('models/spring-ai-google-genai/src/main/java/org/springframework/ai/google/genai/GoogleGenAiChatModel.java', 'w') as f:
    f.write(content)

print("Done")
