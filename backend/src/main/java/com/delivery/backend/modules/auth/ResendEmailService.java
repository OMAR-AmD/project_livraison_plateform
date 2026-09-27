package com.delivery.backend.modules.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Service
public class ResendEmailService {

    private final String resendApiKey;
    private final RestTemplate restTemplate;

    public ResendEmailService(@Value("${resend.api.key:re_dummy_key}") String resendApiKey) {
        this.resendApiKey = resendApiKey;
        this.restTemplate = new RestTemplate();
    }

    public void sendVerificationEmail(String toEmail, String verificationToken) {
        String url = "https://api.resend.com/emails";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(resendApiKey);

        String verificationLink = "http://localhost:3000/api/auth/verify?token=" + verificationToken;
        // In a real app, you would point to a frontend page which then calls the backend API,
        // or directly to the backend API that returns a redirect response. For simplicity, we'll
        // point directly to the backend endpoint we will create, assuming backend runs on 8080.
        String backendLink = "http://localhost:8080/api/v1/auth/verify?token=" + verificationToken;

        String htmlContent = "<div style=\"font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;\">" +
                "<h2>Welcome to SwiftDeliver!</h2>" +
                "<p>Thank you for registering. Please click the button below to verify your email address. This link will expire in 24 hours.</p>" +
                "<a href=\"" + backendLink + "\" style=\"display: inline-block; padding: 10px 20px; color: #fff; background-color: #4F46E5; text-decoration: none; border-radius: 5px;\">Verify Email</a>" +
                "</div>";

        Map<String, Object> body = new HashMap<>();
        body.put("from", "SwiftDeliver <onboarding@resend.dev>");
        body.put("to", toEmail);
        body.put("subject", "Verify your SwiftDeliver account");
        body.put("html", htmlContent);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            // Note: If using the dummy key `re_dummy_key`, this will likely fail or be skipped.
            // We catch the exception so it doesn't break the registration flow during local dev without a real key.
            restTemplate.postForEntity(url, request, String.class);
            System.out.println("Verification email sent to " + toEmail);
        } catch (Exception e) {
            System.err.println("Failed to send verification email: " + e.getMessage());
            // In a production app, we might throw a custom exception or use a retry queue.
        }
    }
}
