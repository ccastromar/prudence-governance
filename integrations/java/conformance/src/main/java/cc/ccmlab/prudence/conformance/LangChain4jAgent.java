package cc.ccmlab.prudence.conformance;

import cc.ccmlab.prudence.langchain4j.PrudenceTools;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.tool.ToolExecutor;
import java.util.Map;

/** A LangChain4j AI service with one tool governed by prudence-langchain4j. */
final class LangChain4jAgent {
  interface Assistant {
    @SystemMessage("Use your tools to do what the user asks.")
    String chat(String message);
  }

  private LangChain4jAgent() {}

  static void run() {
    OpenAiChatModel model =
        OpenAiChatModel.builder()
            .baseUrl(Story.LLM_URL)
            .apiKey(Story.LLM_API_KEY)
            .modelName(Story.LLM_MODEL)
            .build();

    // The tool is named at run time (TOOL), so it is declared as a specification and an executor
    // rather than an @Tool method.
    ToolSpecification spec =
        ToolSpecification.builder()
            .name(Story.TOOL)
            .description(Story.DESCRIPTION)
            .parameters(
                JsonObjectSchema.builder()
                    .addStringProperty("reference")
                    .required("reference")
                    .build())
            .build();
    ToolExecutor sendLetter =
        (request, memoryId) -> {
          String reference;
          try {
            reference =
                new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(request.arguments())
                    .path("reference")
                    .asText("");
          } catch (Exception e) {
            reference = "";
          }
          return Story.runTool(reference);
        };

    Assistant assistant =
        AiServices.builder(Assistant.class)
            .chatModel(model)
            .tools(PrudenceTools.govern(Story.governance(), Map.of(spec, sendLetter)))
            .build();
    Story.finish(assistant.chat(Story.TASK));
  }
}
