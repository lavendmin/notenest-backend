package com.notenest;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import com.notenest.service.BidServiceImpl;

@SpringBootTest(properties = {
        "spring.jwt.secret=test-secret-key-for-notenest-builds",
        "spring.task.scheduling.enabled=false"
})
class NoteNestApplicationTests {

    @MockBean
    private BidServiceImpl bidService;

    @Test
    void contextLoads() {
    }

}
