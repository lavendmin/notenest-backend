# [NB5] Elasticsearch 8.11.1 + 한국어 형태소 분석 플러그인(Nori).
# 8.11.1 은 Spring Data Elasticsearch 5.2.x(Spring Boot 3.2) 호환 기준 버전이다. 플러그인은 서버와 같은 버전이 설치된다.
FROM docker.elastic.co/elasticsearch/elasticsearch:8.11.1
RUN bin/elasticsearch-plugin install --batch analysis-nori
