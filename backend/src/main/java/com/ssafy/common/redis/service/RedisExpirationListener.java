package com.ssafy.common.redis.service;


import com.ssafy.api.service.MatchingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
public class RedisExpirationListener implements ApplicationListener<SessionExpiredEvent> {

    private final MatchingService matchingService;
    private static final Logger log = LoggerFactory.getLogger(RedisExpirationListener.class);

    public RedisExpirationListener(MatchingService matchingService) {
        this.matchingService = matchingService;
        log.info("🔄 RedisExpirationListener initialized");  // 리스너 초기화 로그
    }

    @Override
    public void onApplicationEvent(SessionExpiredEvent event) {
        String expiredKey = event.getSessionId();
        log.info("⏳ Redis Key 만료 감지: {}", expiredKey);

        String[] parts = expiredKey.split(":");
        log.info("💡 Parsed key parts: {}", Arrays.toString(parts));  // 파싱된 키 부분들 로깅

        if (parts.length == 4) {
            Long exerciseType = Long.parseLong(parts[2]);
            String userId = parts[3];
            log.info("🎯 Attempting to remove user from waiting room - exerciseType: {}, userId: {}",
                    exerciseType, userId);
            matchingService.leaveWaitingRoom(userId, exerciseType);
            matchingService.deleteUsers();
        } else {
            log.warn("⚠️ Invalid key format: {}", expiredKey);
        }
    }
}
