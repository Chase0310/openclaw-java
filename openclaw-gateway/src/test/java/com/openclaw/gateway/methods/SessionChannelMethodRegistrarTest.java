package com.openclaw.gateway.methods;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openclaw.common.config.ConfigService;
import com.openclaw.gateway.session.SessionStore;
import com.openclaw.gateway.websocket.GatewayConnection;
import com.openclaw.gateway.websocket.GatewayMethodRouter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.socket.WebSocketSession;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SessionChannelMethodRegistrarTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void skillsStatus_returnsResolvedSkillInventory() throws Exception {
        Files.writeString(tempDir.resolve("config.json"), "{}");
        Path skillDir = tempDir.resolve("skills").resolve("gateway-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                description: Gateway visible skill
                ---
                Gateway body
                """);

        GatewayMethodRouter router = new GatewayMethodRouter();
        ConfigService configService = new ConfigService(tempDir.resolve("config.json"));
        SessionChannelMethodRegistrar registrar = new SessionChannelMethodRegistrar(
                router,
                configService,
                new SessionStore());
        registrar.registerMethods();

        GatewayConnection connection = new GatewayConnection("test", mock(WebSocketSession.class));
        ObjectNode params = MAPPER.createObjectNode();
        params.put("agentId", "default");

        Object response = router.dispatch("skills.status", params, connection).get();
        assertInstanceOf(Map.class, response);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response;
        assertEquals(tempDir.toString(), result.get("workspaceDir"));
        assertNotNull(result.get("managedSkillsDir"));
        assertNotNull(result.get("limits"));

        @SuppressWarnings("unchecked")
        List<Object> skills = (List<Object>) result.get("skills");
        assertFalse(skills.isEmpty());
        Map<String, Object> entry = skills.stream()
                .map(skill -> MAPPER.convertValue(skill, Map.class))
                .filter(skill -> "gateway-skill".equals(skill.get("name")))
                .findFirst()
                .orElseThrow();

        assertEquals("openclaw-workspace", entry.get("source"));
        assertEquals(true, entry.get("eligible"));
        assertEquals(false, entry.get("disableModelInvocation"));
        assertEquals(skillDir.resolve("SKILL.md").toRealPath().toString(), entry.get("filePath"));
    }
}
