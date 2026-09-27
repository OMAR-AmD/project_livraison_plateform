package com.delivery.backend.modules.ai;

import com.delivery.backend.modules.auth.User;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/client/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> chat(
            @RequestBody Map<String, String> request,
            @AuthenticationPrincipal User client) {
        String question = request.get("message");
        if (question == null || question.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        String response = chatService.chatWithClient(question, client);
        return ResponseEntity.ok(Map.of("response", response));
    }
}
