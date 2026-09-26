package com.notenest.search.it;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

import java.nio.file.Path;
import java.time.Duration;

/**
 * [NB5] 통합 테스트용 Elasticsearch 8.11.1 + Nori — 저장소의 docker/elasticsearch/Dockerfile 로 이미지를 만들고
 * docker-compose 의 notenest-es 와 같은 설정(단일 노드·보안 끔·힙 512MB·메모리 상한 1.5GiB)으로 JVM 당 한 번 띄운다.
 * 로컬 notenest-es 컨테이너나 기존 인덱스를 쓰지 않는다. 컨테이너는 테스트 JVM 이 끝나면 Testcontainers 가 정리한다.
 */
public final class ElasticsearchTestContainer {

    private static final GenericContainer<?> CONTAINER = new GenericContainer<>(
            new ImageFromDockerfile("notenest-es-it:8.11.1-nori", false)
                    .withDockerfile(Path.of("docker", "elasticsearch", "Dockerfile")))
            .withEnv("discovery.type", "single-node")
            .withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(1536L * 1024 * 1024))
            .withExposedPorts(9200)
            .waitingFor(Wait.forHttp("/_cluster/health?wait_for_status=yellow&timeout=60s").forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    private ElasticsearchTestContainer() {
    }

    public static synchronized String url() {
        if (!CONTAINER.isRunning()) {
            CONTAINER.start();
            // Ryuk 을 끄고 돌리므로(build.gradle nb5IntegrationTest) JVM 이 끝날 때 직접 멈추고 지운다.
            Runtime.getRuntime().addShutdownHook(new Thread(CONTAINER::stop));
        }
        return "http://" + CONTAINER.getHost() + ":" + CONTAINER.getMappedPort(9200);
    }

    /** 장애 재현 — 프로세스를 얼려 연결은 되지만 응답하지 않게 한다(포트 매핑 유지). 클라이언트는 응답 타임아웃으로 실패한다. */
    public static void pause() {
        DockerClientFactory.instance().client().pauseContainerCmd(CONTAINER.getContainerId()).exec();
    }

    public static void unpause() {
        DockerClientFactory.instance().client().unpauseContainerCmd(CONTAINER.getContainerId()).exec();
    }

    /** Spring 컨텍스트 없이 쓰는 클라이언트(품질 테스트용). */
    public static ElasticsearchClient client() {
        RestClient rest = RestClient.builder(HttpHost.create(url())).build();
        return new ElasticsearchClient(new RestClientTransport(rest, new JacksonJsonpMapper()));
    }
}
