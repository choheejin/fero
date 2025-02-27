package com.ssafy.api.service;

import com.ssafy.api.request.*;
import com.ssafy.api.response.GameResultInfoRes;
import com.ssafy.api.response.RankUpdateRes;
import com.ssafy.common.util.JwtTokenUtil;
import com.ssafy.db.entity.User;
import com.ssafy.db.entity.UserCharacter;
import com.ssafy.db.entity.UserStats;
import com.ssafy.db.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.transaction.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class MatchingService {
    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final UserStatsRepository userStatsRepository;
    private final UserRepository userRepository;
    private final GameResultServiceImpl gameResultService;
    private final UserCharacterRepository userCharacterRepository;
    private final ExerciseLogRepository exerciseLogRepository;
    private final UserRankScoresRepository userRankScoresRepository;
    private final UserRankScoresServiceImpl userRankScoresServiceImpl;
    private final ExerciseLogService exerciseLogService;
    private boolean matchFound = false;

    @PersistenceContext
    private EntityManager entityManager;

    // Redis Key 상수
    private static final String WAITING_QUEUE = "waiting:queue:"; // 입장 순서
    private static final String SCORE_SORTED_SET = "score:sorted:set:"; // 점수 정렬
    private static final String USER_INFO = "user:info:"; // 유저 정보
    private static final String USER_JOIN_TIME = "waiting:expire:"; // 5분 타임아웃 체크 (주기적 실행)
    private final ExerciseStatsRatioRepository exerciseStatsRatioRepository;

    // Redis Key 생성 유틸리티 메서드들
    private String getQueueKey(Long exerciseType) {
        return String.format("waiting:queue%s", exerciseType);
    }
    private String getSortedSetKey(Long exerciseType) {
        return String.format("score:sorted:set%s", exerciseType);
    }
    private String getUserInfoKey(Long exerciseType) {
        return String.format("user:info%s", exerciseType);
    }
    private String getUserJoinTimeKey(Long exerciseType, String userToken) {
        return String.format("waiting:expire:%s:%s", exerciseType, userToken);
    }


    // 1-1. 유저를 대기열에 추가함
    public void enterWaitingRoom(String userToken, Long exerciseType, Short rankScore) {
        // 사용자 입장 이벤트 발생
        matchFound = false;
        eventPublisher.publishEvent(new EnterWaitingRoomEvent(userToken, exerciseType, rankScore));

        if(matchFound) return;

        WaitingUser waitingUser = new WaitingUser(userToken, exerciseType, rankScore, LocalDateTime.now());

        // 운동 타입별 키 생성
        String sortedKey = getSortedSetKey(exerciseType);
        String userInfoKey = getUserInfoKey(exerciseType);
        String expireKey = getUserJoinTimeKey(exerciseType, userToken);


        // 유저 정보 저장
        redisTemplate.opsForHash().put(userInfoKey, userToken, waitingUser);
        // 스코어 정렬셋에 추가
        redisTemplate.opsForZSet().add(sortedKey, userToken, (double) rankScore);
        // 입장 시간 TTL 설정 (5분 만료)
        stringRedisTemplate.opsForValue().set(expireKey, "EXPIRED", Duration.ofMinutes(1));

        log.info("✅ {} 사용자가 {} 대기열에 입장 (TTL 설정 완료)", userToken, exerciseType);
        logWaitingRoomStatus(getUserInfoKey(exerciseType));
    }

    // 1-2. 대기방 상태 => 전체 사용자를 출력하였던 기존의 코드를 상위 10명을 출력하게 수정
    private void logWaitingRoomStatus(String userInfoKey) {
        // 전체 유저 수 조회
        Long totalUsers = redisTemplate.opsForHash().size(userInfoKey);
        System.out.println("전체 유저 수: " + totalUsers);
        System.out.println("==========================================\n");
    }

    // 2-1. 대기방 퇴장 메서드 
    public void leaveWaitingRoom(String userToken, Long exerciseType) {
        String userInfoKey = getUserInfoKey(exerciseType);

        // 유저 정보 조회 시도
        Object userObj = redisTemplate.opsForHash().get(userInfoKey, userToken);
        if (userObj == null) {
            log.warn("User {} not found in waiting room {}", userToken, exerciseType);
            return;
        }
        log.info("Removing user {} from waiting room for {}", userToken, exerciseType);
        removeFromRedis(exerciseType, userToken);

    }
    
    // 2-2. 대기 상태 redis 에서 지우기
    private void removeFromRedis(Long exerciseType, String userToken) {
        String queueKey = getQueueKey(exerciseType);
        String sortedSetKey = getSortedSetKey(exerciseType);
        String userInfoKey = getUserInfoKey(exerciseType);
        String expireKey = getUserJoinTimeKey(exerciseType, userToken);

//        Long removedFromQueue = redisTemplate.opsForList().remove(queueKey, 1, userToken);
        Long removedFromHash = redisTemplate.opsForHash().delete(userInfoKey, userToken);
        Long removedFromSortedSet = redisTemplate.opsForZSet().remove(sortedSetKey, userToken);
        redisTemplate.delete(expireKey);

//        log.info("[REDIS REMOVE] Queue에서 제거된 아이템 수: {}", removedFromQueue); // 사실 얘는 삭제 안됨
        log.info("[REDIS REMOVE HASH] 해시에서 제거된 아이템 수: {}", removedFromHash);
        log.info("[REDIS REMOVE SET] 정렬 세트에서 제거된 아이템 수: {}", removedFromSortedSet);
    }

    // 매칭 처리 로직 (스케줄러로 주기적으로 실행할 것) => 전체 유저를 모두 불러서 삭제하는 걸 알 수 있음
    public void deleteUsers() {
        List<Long> exerciseTypes = exerciseStatsRatioRepository.findAllExerciseStatsRatioId();

        for (Long id : exerciseTypes) {
            String queueKey = getQueueKey(id);
            String sortedSetKey = getSortedSetKey(id);
            String userInfoKey = getUserInfoKey(id);

            List<Object> waitingUsers = redisTemplate.opsForList().range(queueKey, 0, -1);
            if (waitingUsers == null || waitingUsers.isEmpty()) continue;

            for(Object userToken : waitingUsers) {
                String expireKeyString = getUserJoinTimeKey(id, userToken.toString());

                Double score = redisTemplate.opsForZSet().score(sortedSetKey, userToken);
                if(score == null){  // ZSet에는 없지만 Queue에는 존재하는 경우(매칭이 됐을 때)
                    redisTemplate.opsForList().remove(queueKey, 1, userToken);
                    redisTemplate.opsForHash().delete(userInfoKey, userToken);
                    redisTemplate.delete(expireKeyString);
                    log.info("[Matching] ZSet에 존재하지 않는 사용자 {} -> Queue에서도 삭제 (운동 타입: {})", userToken, id);
                    continue;
                }

                Long ttl = redisTemplate.getExpire(expireKeyString, TimeUnit.SECONDS);
                if (ttl == null || ttl == -2) { // 남은 기본 대기 시간이 만료 되었을 때 삭제
                    log.info("[Matching] 키 만료됨: {}", expireKeyString);
                } else if (ttl <= 0) {
                    redisTemplate.opsForZSet().remove(sortedSetKey, userToken);
                    redisTemplate.opsForHash().delete(userInfoKey, userToken);
                    redisTemplate.delete(expireKeyString);
                    log.info("[Matching] 대기 시간이 초과된 사용자 {} 제거 완료 (운동 타입: {})", userToken, id);
                } else {
                    log.info("[Matching] 대기 시간이 남아있는 사용자 {} 발견 (남은 TTL: {}초, 운동 타입: {})", userToken, ttl, id);
                }
            }
        }
    }


    // 3-1. 매칭 시도 이벤트 리스너
    @EventListener
    public void processMatching(EnterWaitingRoomEvent event){
        log.info("🎯 매칭 프로세스 시작 - User: {}, Exercise: {}, Score: {}",
                event.getUserToken().replace("Bearer ", ""), event.getExerciseId(), event.getRankScore());

        processMatchingLogic(event.getExerciseId(), event.getUserToken(), event.getRankScore());
    }

    // 3-2. 실제 매칭 시도
    private void processMatchingLogic(Long exerciseId, String userToken, Short rankScore) {
        String queueKey = getQueueKey(exerciseId);
        String sortedSetKey = getSortedSetKey(exerciseId);
        String userInfoKey = getUserInfoKey(exerciseId);

        // 1. 새로운 유저의 점수 범위에 맞는 후보자 찾기 (rankScore ± 100 범위)
        Set<Object> candidates = redisTemplate.opsForZSet().rangeByScore(
                sortedSetKey,
                rankScore - 99,
                rankScore + 99);

        if (candidates == null || candidates.isEmpty()) {
            log.info("점수 범위 내({} ± 100)에 매칭 가능한 후보가 없습니다.", rankScore);
            return;
        }

        log.info("점수 범위 내({} ± 100) 후보자 수: {}", rankScore, candidates.size());

        // 2. 후보자들의 대기 시간 정보를 가져와서 정렬
        List<WaitingUser> eligibleUsers = new ArrayList<>();
        for (Object candidateObj : candidates) {
            String candidateToken = candidateObj.toString();
            // 자기 자신은 제외
            if (candidateToken.equals(userToken)) continue;

            log.info("매칭시도자의 정보: {}", userToken);
            log.info("후보자 정보: {}", candidateObj);

            // 후보자 정보 가져오기
            Object userInfoObj = redisTemplate.opsForHash().get(userInfoKey, candidateToken);
            log.info("가져온 후보자의 정보: {}", userInfoObj);
            log.info("가져온 후보자의 클래스: {}", userInfoObj.getClass());

            WaitingUser waitingUser = (WaitingUser) userInfoObj;

            log.info("waitingUser: {}", waitingUser);
            eligibleUsers.add(waitingUser);
        }


        // 3. 대기 시간이 오래된 순으로 정렬
        eligibleUsers.sort(Comparator.comparing(WaitingUser::getJoinTime));
        log.info("후보자 정보: {}", eligibleUsers);

        // 4. 가장 오래 기다린 유저와 매칭
        if (!eligibleUsers.isEmpty()) {
            WaitingUser matchedUser = eligibleUsers.get(0);
            handleMatchSuccess(matchedUser.getUserId(), userToken, exerciseId);
            matchFound = true;
            log.info("🎯 대기 시간이 가장 긴 사용자와 매칭 성공! User: {}, Wait Time: {}",
                    matchedUser.getUserId(),
                    Duration.between(matchedUser.getJoinTime(), LocalDateTime.now()).getSeconds());
        } else {
            log.info("적합한 매칭 상대를 찾지 못했습니다 (score: {})", rankScore);
        }
    }

    private void handleMatchSuccess(String userToken1, String userToken2, Long exerciseId) {
        removeFromRedis(exerciseId, userToken1);
//        removeFromRedis(exerciseId, userToken2);

        // eventPublisher 추가해야 함 -> WebSocket 으로
        eventPublisher.publishEvent(new MatchSuccessEvent(userToken1, userToken2, exerciseId));



        log.info("🎊 매칭 성공! User1: {}, User2: {}, Exercise: {}", userToken1, userToken2, exerciseId);
    }


    // 매칭된 게임에 대한 id 만들기
    public String makeGameId(String userToken, String date) {
        try {
        // **해시 입력값 생성**
        String input = userToken + "_" + date;

        // **SHA-256 해시 생성**
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));

        // **Base64 인코딩 (DB 저장을 위해 문자열 변환)**
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hashBytes);

    } catch (NoSuchAlgorithmException e) {
        throw new RuntimeException("SHA-256 알고리즘을 사용할 수 없음", e);
    }
    }

    // 게임 끝날 때 변화값 띄우기
    @Transactional
    public GameResultInfoRes saveGameResult(GameResultReq gameResultReq) {
        GameResultInfoRes gameResultInfoRes = new GameResultInfoRes();

        String userId = JwtTokenUtil.getUserIdFromJWT(gameResultReq.getUserToken1());
        String opponentId = JwtTokenUtil.getUserIdFromJWT(gameResultReq.getUserToken2());
        User user = userRepository.findById(userId).orElse(null);

        // Stats 깊은 복사
        UserStats currentStats = userStatsRepository.findByUser(user).orElse(null);
        UserStats beforeStats = null;
        if(currentStats != null) {
            beforeStats = new UserStats();
            beforeStats.setId(currentStats.getId());
            beforeStats.setAbsStats(currentStats.getAbsStats());
            beforeStats.setArmsStats(currentStats.getArmsStats());
            beforeStats.setBackStats(currentStats.getBackStats());
            beforeStats.setChestStats(currentStats.getChestStats());
            beforeStats.setLegsStats(currentStats.getLegsStats());
            beforeStats.setStaminaStats(currentStats.getStaminaStats());
            beforeStats.setUpdatedAt(currentStats.getUpdatedAt());
        }

        // Character 깊은 복사
        UserCharacter currentCharacter = userCharacterRepository.findByUser_UserId(userId).orElse(null);
        Short beforeLevel = null;
        Integer beforeExperience = null;
        if(currentCharacter != null) {
            beforeLevel = currentCharacter.getUserLevel();
            beforeExperience = currentCharacter.getUserExperience();
        }

        // 기본 정보 설정
        gameResultInfoRes.setUserId(userId);
        gameResultInfoRes.setOpponentId(opponentId);
        gameResultInfoRes.setUserScore(gameResultReq.getUser1Score());
        gameResultInfoRes.setOpponentScore(gameResultReq.getUser2Score());
        gameResultInfoRes.setExerciseId(gameResultReq.getExerciseId());

        // 복사해둔 before 상태 설정
        gameResultInfoRes.setBeforeStats(beforeStats);
        gameResultInfoRes.setBeforeUserLevel(beforeLevel);
        gameResultInfoRes.setBeforeUserExperience(beforeExperience);

        // DB 반영 - before 상태 완전히 저장
        entityManager.flush();
        entityManager.clear();

        // 운동 전적을 DB에 저장 및 스탯/경험치 업데이트
        ExerciseLogReq exerciseLogReq = new ExerciseLogReq();
        exerciseLogReq.setExerciseCnt(gameResultReq.getUser1Score());
        exerciseLogReq.setExerciseStatsRatioId(gameResultReq.getExerciseId());
        exerciseLogReq.setExerciseDuration(gameResultReq.getDuration());
        exerciseLogService.addExerciseLogAndUpdateStats(new EventExerciseLog(userId, exerciseLogReq));
        log.info(exerciseLogReq.toString());

        // DB 반영 - 업데이트된 상태 저장
        entityManager.flush();
        entityManager.clear();

        int result = 0;
        if(gameResultReq.getRemainTime() != 0) {
            result = 1;
        }
        if(gameResultReq.getRemainTime() == 0) {
            if(gameResultReq.getUser1Score() > gameResultReq.getUser1Score()) {
                result = 1;
            }
            if(gameResultReq.getUser2Score() > gameResultReq.getUser1Score()) {
                result = 2;
            }
        }


        ExerciseResultEvent exerciseResultEvent = new ExerciseResultEvent(
                userId,
                opponentId,
                gameResultReq.getUser1Score(),
                gameResultReq.getUser2Score(),
                result,
                gameResultReq.getExerciseId()
        );

        RankUpdateRes rankUpdateRes = userRankScoresServiceImpl.updateRankScore(exerciseResultEvent);
        log.info("@@@@@@@@===========================");

        gameResultInfoRes.setBeforeRankScore(rankUpdateRes.getUser1PreviousScore());
        gameResultInfoRes.setAfterRankScore(rankUpdateRes.getUser1NewScore());

//        // 새로운 트랜잭션에서 after 상태 조회를 위해 clear
        entityManager.clear();


        // 업데이트된 after 상태 조회
        UserStats afterStats = userStatsRepository.findByUser(user).orElse(null);
        gameResultInfoRes.setAfterStats(afterStats);

        // Character 정보도 새로 조회
        UserCharacter updatedCharacter = userCharacterRepository.findByUser_UserId(userId).get();
        gameResultInfoRes.setAfterUserLevel(updatedCharacter.getUserLevel());
        gameResultInfoRes.setAfterUserExperience(updatedCharacter.getUserExperience());

        return gameResultInfoRes;
    }
}
