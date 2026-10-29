package com.loopers.testcontainers;

import org.springframework.context.annotation.Configuration;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 테스트용 단일 broker. MySqlTestContainersConfig 와 같은 방식으로 클래스 초기화 때 컨테이너를 띄우고
 * 주소를 시스템 속성으로 주입한다. JVM 당 broker 1개를 모든 테스트가 공유하므로 격리는 고유 topic·group 으로 한다.
 */
@Configuration
public class KafkaTestContainersConfig {

    private static final KafkaContainer kafkaContainer;

    static {
        kafkaContainer = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));
        kafkaContainer.start();

        // producer·consumer·admin 이 같은 테스트 broker 를 쓰게 한다. test profile 의 admin 주소(kafka:9092)는
        // 컨테이너 내부 주소라 호스트에서 해석되지 않으므로 시스템 속성으로 덮는다 (시스템 속성이 yml 보다 우선).
        System.setProperty("spring.kafka.bootstrap-servers", kafkaContainer.getBootstrapServers());
        System.setProperty("spring.kafka.admin.properties.bootstrap.servers", kafkaContainer.getBootstrapServers());
    }

    /** Spring 밖에서 KafkaConsumer·AdminClient 를 직접 만들 때 쓰는 실제 host 주소. */
    public static String bootstrapServers() {
        return kafkaContainer.getBootstrapServers();
    }
}
