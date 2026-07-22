package com.buildgraph.prototype.build;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 데모/운영 안정성을 위해 자주 쓰는 Build Chat 프롬프트를 서버가 스스로 캐시에 올린다.
 * 서버 기동 직후 1회 + 고정 주기로 재프리웜해 캐시 TTL이 지나도 항상 따뜻하게 유지하므로,
 * 배포 환경에서 별도 워밍 스크립트를 수동 실행할 필요가 없다.
 */
@Service
public class BuildChatCachePrewarmService {
    private static final Logger log = LoggerFactory.getLogger(BuildChatCachePrewarmService.class);

    // 데모/시연에서 자주 나오는 예산·용도 프롬프트. 예산 견적은 티어/그리디로, 용도-only 게임 추천은
    // deterministic 경로로 응답되며, 여기서 미리 호출해 exact/semantic 캐시까지 채운다.
    // 앞 4개는 홈 퀵스타트 칩(HomeQuickStartPanel)이 자동 제출하는 문장 원문이다 — 캐시 키는
    // message 원문 기준이라, 사용자가 실제로 누르는 문장과 한 글자라도 다르면 프리웜이 무의미하다.
    private static final List<String> PREWARM_PROMPTS = List.of(
            "QHD 해상도에서 144fps로 게임할 수 있는 PC 추천해줘",
            "영상 편집과 렌더링 작업에 맞는 PC 추천해줘, 저장공간도 넉넉하게",
            "개발과 AI 작업에 맞는 PC 추천해줘, 멀티코어와 메모리 여유를 우선해줘",
            "사무·일반 용도로 쓸 가성비 좋은 저전력 PC 추천해줘",
            "200만원 게이밍 PC 추천해줘",
            "300만원대 게임용 PC 추천해줘",
            "500만원 AI CUDA 학습용 워크스테이션 추천해줘",
            "800만원으로 최고급 PC 추천해줘",
            "1300만원 게이밍 PC 추천해줘",
            "영상편집용 PC 추천해줘",
            "발로란트 240Hz용 컴퓨터 맞춰줘",
            "로스트아크 레이드 돌릴 PC 추천",
            "디아블로4 돌릴 컴퓨터 추천",
            "개발용으로 도커 여러개 띄울 워크스테이션 추천",
            "저소음 사무용 PC 추천해줘",
            "AI 학습용 800만원 이하인데 소음 낮은 PC 추천해줘"
    );

    // 웹의 홈 요청이 항상 싣는 uiContext(AiBuildAssistant) — v72부터 캐시 키 서명에 들어가므로
    // 이것 없이 저장한 프리웜 항목은 실제 브라우저 트래픽에서 한 번도 조회되지 않는다.
    private static final Map<String, Object> HOME_UI_CONTEXT = Map.of(
            "surface", "HOME",
            "capabilities", List.of("PART_CANDIDATE_PANEL"));

    private final BuildChatService buildChatService;
    private final BuildChatCacheService buildChatCacheService;
    private final boolean enabled;
    private final Duration ttl;

    public BuildChatCachePrewarmService(
            BuildChatService buildChatService,
            BuildChatCacheService buildChatCacheService,
            @Value("${ai.build-chat.cache.prewarm.enabled:true}") boolean enabled,
            @Value("${ai.build-chat.cache.prewarm.ttl-seconds:3600}") long ttlSeconds
    ) {
        this.buildChatService = buildChatService;
        this.buildChatCacheService = buildChatCacheService;
        this.enabled = enabled;
        this.ttl = Duration.ofSeconds(Math.max(1, ttlSeconds));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void prewarmAfterReady() {
        if (!enabled) {
            log.info("Build Chat cache prewarm skipped: disabled");
            return;
        }
        CompletableFuture.runAsync(this::prewarm);
    }

    /**
     * 프리웜 TTL이 지나 캐시가 식기 전에 재프리웜한다. 캐시가 아직 살아 있으면 chat()이
     * CACHE_HIT로 즉시 반환하고 TTL만 갱신하므로 LLM을 다시 호출하지 않아 비용 부담이 작다.
     * 기본 주기는 프리웜 TTL(3600초)보다 짧은 2700초(45분)로, 항상 캐시를 유지한다.
     */
    @Scheduled(fixedDelayString = "${ai.build-chat.cache.prewarm.refresh-delay-ms:2700000}",
            initialDelayString = "${ai.build-chat.cache.prewarm.refresh-delay-ms:2700000}")
    public void prewarmOnSchedule() {
        if (!enabled) {
            return;
        }
        prewarm();
    }

    private void prewarm() {
        int warmed = 0;
        for (String prompt : PREWARM_PROMPTS) {
            Map<String, Object> request = Map.of("message", prompt, "uiContext", HOME_UI_CONTEXT);
            try {
                Map<String, Object> response = buildChatService.chat(request, null, null);
                buildChatCacheService.store(request, null, null, response, ttl);
                warmed += 1;
            } catch (Exception error) {
                log.warn("Build Chat cache prewarm skipped for prompt='{}': {}", prompt, error.getMessage());
            }
        }
        log.info("Build Chat cache prewarmed: prompts={}/{}, ttlSeconds={}", warmed, PREWARM_PROMPTS.size(), ttl.toSeconds());
    }
}
