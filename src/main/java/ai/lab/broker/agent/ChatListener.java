package ai.lab.broker.agent;

import ai.lab.broker.llm.ToolConversation.ToolCall;
import ai.lab.broker.llm.ToolConversation.ToolResult;

/**
 * What the agent loop reports while a message is being answered (task 10, streaming): each tool call before it runs
 * and its result after. The final response is not reported here: it is what {@link AssistantService#chat} returns.
 * <p>
 * Called on the thread that answers the message. An implementation should not throw: a failure to report progress
 * must not fail the message, so the loop only logs what a listener throws and goes on.
 */
public interface ChatListener {

    /** Reports nothing: the plain /chat. */
    ChatListener NONE = new ChatListener() {};

    default void toolCall(ToolCall call) {
    }

    /** Also for calls the guards refused or that were not run at all: whatever the model gets back. */
    default void toolResult(ToolResult result) {
    }
}
