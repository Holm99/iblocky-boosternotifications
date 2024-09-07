package net.holm.boosternoti;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class BoosterConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = Paths.get("config/iblocky_boosternoti.json");
    private static final Logger LOGGER = LoggerFactory.getLogger(BoosterConfig.class);

    public int windowX = 100; // Default X position
    public int windowY = 100; // Default Y position
    public int initialFetchDelaySeconds = 30; // Default fetch delay
    public int fetchIntervalSeconds = 120; // Default fetch interval

    // Singleton instance
    private static BoosterConfig instance;

    // Load configuration from file
    public static BoosterConfig load() {
        if (instance != null) {
            return instance;
        }

        if (CONFIG_PATH.toFile().exists()) {
            try (FileReader reader = new FileReader(CONFIG_PATH.toFile())) {
                JsonObject config = JsonParser.parseReader(reader).getAsJsonObject();
                instance = GSON.fromJson(config, BoosterConfig.class);
                return instance;
            } catch (JsonSyntaxException | IOException e) {
                LOGGER.error("Failed to load config, creating default config. Error: {}", e.getMessage(), e);
            }
        }

        // If config file doesn't exist or loading fails, return default config
        instance = new BoosterConfig();
        saveDefaultConfig(instance);  // Save the default configuration if it doesn't exist
        return instance;
    }

    // Save configuration to file
    public void save() {
        try (FileWriter writer = new FileWriter(CONFIG_PATH.toFile())) {
            JsonObject config = new JsonObject();
            config.addProperty("windowX", windowX);
            config.addProperty("windowY", windowY);
            config.addProperty("initialFetchDelaySeconds", initialFetchDelaySeconds);
            config.addProperty("fetchIntervalSeconds", fetchIntervalSeconds);
            writer.write(GSON.toJson(config));
        } catch (IOException e) {
            LOGGER.error("Failed to save config file: {}", e.getMessage(), e);
        }
    }

    // Save default configuration file
    private static void saveDefaultConfig(BoosterConfig defaultConfig) {
        defaultConfig.save();
    }
}