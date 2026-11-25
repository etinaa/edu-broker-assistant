package ai.lab.broker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The context starts on the default provider (ollama): the client is created but never calls the server,
 * so the test needs neither a running Ollama nor a Claude key.
 */
@SpringBootTest(properties = "llm.provider=ollama")
class BrokerAssistantApplicationTests {

    @Test
    void contextLoads() {
    }
}
