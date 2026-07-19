package com.notenest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
@EnableJpaAuditing
public class NoteNestApplication {

    public static void main(String[] args) {
        // 톰캣 Timezone 설정
        // EC2에서도 Tomcat 서버의 시간을 서울 시간으로 변경해야 한다.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        SpringApplication.run(NoteNestApplication.class, args);
    }

}
