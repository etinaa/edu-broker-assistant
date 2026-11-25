package ai.lab.broker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent limits from the {@code agent} section of {@code application.yml}.
 * <ul>
 *   <li>maxHistoryMessages — the session history limit in messages; older exchanges are dropped
 *       (see {@code SessionStore});</li>
 *   <li>maxSessions, maxTraces — how many sessions and traces are kept in memory; the least recently used session
 *       and the oldest trace are evicted first, so a client creating new sessionIds cannot exhaust the heap.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "agent")
public record AgentProperties(int maxSteps, double temperature, int maxHistoryMessages,
                              int maxSessions, int maxTraces) {}
