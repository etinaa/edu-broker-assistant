package ai.lab.broker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Контекст поднимается на провайдере по умолчанию (ollama): клиент создаётся, но к серверу не обращается,
 * поэтому тест не требует ни запущенной Ollama, ни ключа Claude.
 */
@SpringBootTest(properties = "llm.provider=ollama")
class BrokerAssistantApplicationTests {

    @Test
    void contextLoads() {
    }
}
