package com.notenest.service;
import com.notenest.domain.Composer;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Service
public class ComposerService {

    public List<Composer> getAllComposerInfo() {

        String filePath = "composers_table.json";

        JSONParser parser = new JSONParser();
        List<Composer> composerInfoList = new ArrayList<>();
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(filePath);
             InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
            if (inputStream == null) {
                throw new FileNotFoundException("File not found: " + filePath);
            }

            JSONArray jsonArray = (JSONArray) parser.parse(reader);

            for (Object obj : jsonArray) {
                JSONObject composerData = (JSONObject) obj;
                String composer = (String) composerData.get("composer");
                Long entryCount = (Long) composerData.get("entry_count");
                Boolean popular = (Boolean) composerData.get("popular");
                Boolean steadyWork = (Boolean) composerData.get("steady_work");
                Boolean hitSong = (Boolean) composerData.get("hit_song");

                Composer composerInfo = new Composer();
                composerInfo.setComposer(composer);
                composerInfo.setEntryCount(entryCount);
                composerInfo.setPopular(popular);
                composerInfo.setSteadyWork(steadyWork);
                composerInfo.setHitSong(hitSong);

                composerInfoList.add(composerInfo);
            }
        } catch (IOException | ParseException e) {
            e.printStackTrace();
        }
        return composerInfoList;
    }

}
