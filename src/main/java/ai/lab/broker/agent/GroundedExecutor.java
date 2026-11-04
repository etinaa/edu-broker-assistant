package ai.lab.broker.agent;

import ai.lab.broker.llm.ToolConversation.ToolCall;
import ai.lab.broker.llm.ToolConversation.ToolResult;

import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Сверка аргументов с тем, что модель действительно видела (перенесено из урока 1.4, задание 5).
 * Тикер в аргументах должен встречаться в сообщениях пользователя или в результатах предыдущих вызовов —
 * иначе модель его «исправила» или выдумала.
 * <p>
 * Задание 7: подключить в {@link AssistantService}. Подумай, что считать «увиденным» в сессии из нескольких
 * сообщений: только текущее сообщение или всю историю сессии? Экземпляр накапливает результаты своих вызовов.
 */
public final class GroundedExecutor implements Function<ToolCall, ToolResult> {

    /** Сверяются только идентификаторы — их модель не вправе выводить. */
    private static final List<String> IDENTIFIER_ARGUMENTS = List.of("ticker");

    private final Function<ToolCall, ToolResult> delegate;
    private final StringBuilder seenText;

    public GroundedExecutor(String seenSoFar, Function<ToolCall, ToolResult> delegate) {
        this.delegate = delegate;
        this.seenText = new StringBuilder(seenSoFar);
    }

    @Override
    public ToolResult apply(ToolCall call) {
        for (String argument : IDENTIFIER_ARGUMENTS) {
            String value = call.arguments().path(argument).asText();
            if (!value.isEmpty() && !wasSeen(value)) {
                return ToolResult.error(call, "Значение " + argument + "='" + value + "' не встречается ни в сообщениях "
                        + "пользователя, ни в результатах инструментов. Не подставляй и не исправляй идентификаторы — "
                        + "найди бумагу через find_security или уточни у пользователя.");
            }
        }
        ToolResult result = delegate.apply(call);
        if (!result.error()) {
            seenText.append('\n').append(result.content());
        }
        return result;
    }

    /** Целым токеном, без учёта регистра: trnc → TRNC — допустимая нормализация, TRN → TRNC — нет. */
    private boolean wasSeen(String value) {
        Pattern token = Pattern.compile("(?<![\\p{L}\\d-])" + Pattern.quote(value) + "(?![\\p{L}\\d-])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        return token.matcher(seenText).find();
    }
}
