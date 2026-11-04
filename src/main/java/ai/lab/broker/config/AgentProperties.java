package ai.lab.broker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Границы агента из {@code application.yml}, раздел {@code agent}. */
@ConfigurationProperties(prefix = "agent")
public record AgentProperties(int maxSteps, double temperature) {}
