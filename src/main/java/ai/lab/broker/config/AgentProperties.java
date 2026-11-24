package ai.lab.broker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Agent limits from the {@code agent} section of {@code application.yml}. */
@ConfigurationProperties(prefix = "agent")
/** maxHistoryMessages — the session history limit in messages; older exchanges are dropped (see SessionStore). */
public record AgentProperties(int maxSteps, double temperature, int maxHistoryMessages) {}
