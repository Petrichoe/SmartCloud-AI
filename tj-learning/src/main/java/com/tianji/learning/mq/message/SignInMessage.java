package com.tianji.learning.mq.message;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@NoArgsConstructor
public class SignInMessage {
    private Long userId;
    private Integer points;
    private LocalDate signDate;
    private String eventId;

    public static SignInMessage of(Long userId, Integer points, LocalDate signDate) {
        SignInMessage message = new SignInMessage();
        message.setUserId(userId);
        message.setPoints(points);
        message.setSignDate(signDate);
        message.setEventId(buildEventId(userId, signDate));
        return message;
    }

    public static String buildEventId(Long userId, LocalDate signDate) {
        return "sign:" + userId + ":" + signDate;
    }
}
