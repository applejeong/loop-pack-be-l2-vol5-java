package com.loopers.config.cache;

import com.loopers.utils.RedisCleanUp;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 상품 목록 캐시 전용 연결이 테스트 Redis 컨테이너(RedisTestContainersConfig 가 System property 로 알려준 주소)를
 * 가리키는지 확인한다. 로컬 docker 의 localhost:6379 등 다른 Redis 를 보고 있으면 실패해야 한다.
 */
@SpringBootTest
class ProductCacheRedisConfigTest {

    private final RedisTemplate<String, String> cacheRedisTemplate;
    private final RedisCleanUp redisCleanUp;

    @Autowired
    public ProductCacheRedisConfigTest(
        @Qualifier(ProductCacheRedisConfig.PRODUCT_CACHE_REDIS_TEMPLATE) RedisTemplate<String, String> cacheRedisTemplate,
        RedisCleanUp redisCleanUp
    ) {
        this.cacheRedisTemplate = cacheRedisTemplate;
        this.redisCleanUp = redisCleanUp;
    }

    @AfterEach
    void tearDown() {
        redisCleanUp.truncateAll();
    }

    @DisplayName("캐시 전용 연결의 호스트·포트는 테스트 Redis 컨테이너 주소와 같다.")
    @Test
    void pointsToTestContainer() {
        // act
        LettuceConnectionFactory factory = (LettuceConnectionFactory) cacheRedisTemplate.getConnectionFactory();

        // assert
        assertThat(factory).isNotNull();
        assertThat(factory.getHostName()).isEqualTo(containerHost());
        assertThat(factory.getPort()).isEqualTo(containerPort());
    }

    @DisplayName("캐시 전용 템플릿으로 SET 한 키는 같은 컨테이너에 직접 접속해 조회된다.")
    @Test
    void writesToTestContainer() {
        // arrange
        String key = "product-cache-connection-test";

        // act
        cacheRedisTemplate.opsForValue().set(key, "written-by-cache-template");

        // assert: 스프링 설정을 거치지 않는 별도 클라이언트로 컨테이너에 직접 접속해 읽는다
        RedisClient client = RedisClient.create(RedisURI.create(containerHost(), containerPort()));
        try (StatefulRedisConnection<String, String> connection = client.connect()) {
            assertThat(connection.sync().get(key)).isEqualTo("written-by-cache-template");
        } finally {
            client.shutdown();
        }
    }

    /**
     * RedisTestContainersConfig 가 컨테이너를 띄운 뒤 System property 로 남긴 주소. 컨텍스트가 뜬 뒤에 읽어야 한다.
     */
    private static String containerHost() {
        return System.getProperty("datasource.redis.master.host");
    }

    private static int containerPort() {
        return Integer.parseInt(System.getProperty("datasource.redis.master.port"));
    }
}
