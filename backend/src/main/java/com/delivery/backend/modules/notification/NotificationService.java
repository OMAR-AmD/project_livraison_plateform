package com.delivery.backend.modules.notification;

import com.delivery.backend.modules.auth.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final ConcurrentHashMap<UUID, SseEmitter> emitters = new ConcurrentHashMap<>();

    public NotificationService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    public SseEmitter createEmitter(UUID userId) {
        // Keep connection open for 1 hour (3600000L)
        SseEmitter emitter = new SseEmitter(3600000L);
        emitters.put(userId, emitter);

        emitter.onCompletion(() -> emitters.remove(userId));
        emitter.onTimeout(() -> emitters.remove(userId));
        emitter.onError((e) -> emitters.remove(userId));

        return emitter;
    }

    @Transactional
    public void createNotification(User user, String message) {
        if (user == null) return;
        Notification notification = new Notification(user, message);
        notificationRepository.save(notification);

        // Push real-time event if user is connected
        SseEmitter emitter = emitters.get(user.getId());
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event().name("notification").data("NEW_NOTIFICATION"));
            } catch (IOException e) {
                emitters.remove(user.getId());
            }
        }
    }

    public void sendLocationUpdate(User user, UUID deliveryId, Double latitude, Double longitude) {
        if (user == null) return;
        SseEmitter emitter = emitters.get(user.getId());
        if (emitter != null) {
            try {
                String payload = String.format("{\"deliveryId\":\"%s\", \"latitude\":%s, \"longitude\":%s}", 
                        deliveryId.toString(), latitude, longitude);
                emitter.send(SseEmitter.event().name("location_update").data(payload));
            } catch (IOException e) {
                emitters.remove(user.getId());
            }
        }
    }

    public List<NotificationDTO> getNotificationsForUser(User user) {
        return notificationRepository.findByUserOrderByCreatedAtDesc(user).stream()
                .map(NotificationDTO::new)
                .collect(Collectors.toList());
    }

    public long getUnreadCount(User user) {
        return notificationRepository.countByUserAndIsReadFalse(user);
    }

    @Transactional
    public void markAsRead(UUID notificationId, User user) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new IllegalArgumentException("Notification not found"));
        
        if (!notification.getUser().getId().equals(user.getId())) {
            throw new IllegalArgumentException("You do not have permission to modify this notification");
        }

        notification.setIsRead(true);
        notificationRepository.save(notification);
    }

    @Transactional
    public void markAllAsRead(User user) {
        notificationRepository.markAllAsReadForUser(user);
    }
}
