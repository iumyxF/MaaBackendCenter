package plus.maa.backend.task;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import plus.maa.backend.repository.CopilotRepository;
import plus.maa.backend.repository.RedisCache;
import plus.maa.backend.repository.entity.Copilot;
import plus.maa.backend.repository.entity.Rating;
import plus.maa.backend.service.CopilotService;
import plus.maa.backend.service.model.RatingCount;
import plus.maa.backend.service.model.RatingType;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 作业热度值刷入任务，每日执行，用于计算基于时间的热度值
 *
 * @author dove
 * created on 2023.05.03
 */
@Component
@RequiredArgsConstructor
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
public class CopilotScoreRefreshTask {

    RedisCache redisCache;
    CopilotRepository copilotRepository;
    MongoTemplate mongoTemplate;

    /**
     * 热度值刷入任务，每日三点、十九点执行
     */
    @Scheduled(cron = "0 0 3,19 * * ?")
    public void refreshHotScores() {
        // 分页获取所有未删除的作业
        Pageable pageable = Pageable.ofSize(1000);
        Page<Copilot> copilots = copilotRepository.findAllByDeleteIsFalse(pageable);

        // 循环读取直到没有未删除的作业为止
        while (copilots.hasContent()) {
            List<String> copilotIdSTRs = copilots.stream()
                    .map(copilot -> Long.toString(copilot.getCopilotId()))
                    .collect(Collectors.toList());
            // 批量获取最近七天的点赞和点踩数量
            LocalDateTime now = LocalDateTime.now();
            List<RatingCount> likeCounts = counts(copilotIdSTRs, RatingType.LIKE, now.minusDays(7));
            List<RatingCount> dislikeCounts = counts(copilotIdSTRs, RatingType.DISLIKE, now.minusDays(7));
            Map<String, Long> likeCountMap = likeCounts.stream().collect(Collectors.toMap(RatingCount::getKey, RatingCount::getCount));
            Map<String, Long> dislikeCountMap = dislikeCounts.stream().collect(Collectors.toMap(RatingCount::getKey, RatingCount::getCount));
            // 计算热度值
            for (Copilot copilot : copilots) {
                long likeCount = likeCountMap.getOrDefault(Long.toString(copilot.getCopilotId()), 1L);
                long dislikeCount = dislikeCountMap.getOrDefault(Long.toString(copilot.getCopilotId()), 0L);
                double hotScore = CopilotService.getHotScore(copilot, likeCount, dislikeCount);
                copilot.setHotScore(hotScore);
            }
            // 批量更新热度值
            copilotRepository.saveAll(copilots);
            // 获取下一页
            if (!copilots.hasNext()) {
                // 没有下一页了，跳出循环
                break;
            }
            pageable = copilots.nextPageable();
            copilots = copilotRepository.findAllByDeleteIsFalse(pageable);
        }

        // 移除首页热度缓存
        redisCache.removeCacheByPattern("home:hot:*");
    }

    private List<RatingCount> counts(Collection<String> keys, RatingType rating, LocalDateTime startTime) {
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria
                        .where("type").is(Rating.KeyType.COPILOT)
                        .and("key").in(keys)
                        .and("rating").is(rating)
                        .and("rateTime").gte(startTime)
                ),
                Aggregation.group("key").count().as("count")
                        .first("key").as("key"),
                Aggregation.project("key", "count")
        ).withOptions(Aggregation.newAggregationOptions().allowDiskUse(true).build());  // 放弃内存优化，使用磁盘优化，免得内存炸了
        return mongoTemplate.aggregate(aggregation, Rating.class, RatingCount.class).getMappedResults();
    }

}
