package cc.ccmlab.prudence.conformance;

import cc.ccmlab.prudence.springai.PrudenceTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

/** A Spring AI agent (ChatClient) with one tool governed by prudence-spring-ai. */
final class SpringAiAgent {
  /** The tool's arguments. */
  public record Letter(String reference) {}

  private SpringAiAgent() {}

  static void run() {
    OpenAiChatModel model =
        OpenAiChatModel.builder()
            .options(
                OpenAiChatOptions.builder()
                    .baseUrl(Story.LLM_URL)
                    .apiKey(Story.LLM_API_KEY)
                    .model(Story.LLM_MODEL)
                    .build())
            .build();

    ToolCallback sendLetter =
        FunctionToolCallback.builder(
                Story.TOOL, (Letter letter) -> Story.runTool(letter.reference()))
            .description(Story.DESCRIPTION)
            .inputType(Letter.class)
            .build();
    ToolCallback[] tools = PrudenceTools.govern(Story.governance(), sendLetter);

    String answer =
        ChatClient.create(model)
            .prompt()
            .system("Use your tools to do what the user asks.")
            .user(Story.TASK)
            .toolCallbacks(tools)
            .call()
            .content();
    Story.finish(answer);
  }
}
