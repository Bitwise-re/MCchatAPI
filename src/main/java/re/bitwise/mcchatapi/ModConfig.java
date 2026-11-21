package re.bitwise.mcchatapi;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

// Loads (or creates) the mod's JSON config file and holds its values.
public final class ModConfig {
    private static final Path CONFIG_FILE = Path.of("config/mcchatapi.conf");
    private static final String KEY_SOCKET_PATH = "socket-path";
    private static final String KEY_SOCKET_ACCESS_GROUP = "socket-access-group";
    private static final Path DEFAULT_SOCKET_PATH = Path.of("mcchatapi-sockets/mcchat.sock");
    private static final String DEFAULT_SOCKET_ACCESS_GROUP = "mcchatapi";

    private final Path socketPath;
    private final String socketAccessGroup;

    private ModConfig(Path _socketPath, String _socketAccessGroup) {
        socketPath = _socketPath;
        socketAccessGroup = _socketAccessGroup;
    }

    public Path getSocketPath() {
        return socketPath;
    }

    public String getSocketAccessGroup() {
        return socketAccessGroup;
    }

    //static methods to create the class instances :

    public static ModConfig load(Path gameDir) {
        Path configFile = gameDir.resolve(CONFIG_FILE);

        if (!Files.exists(configFile)) {
            return createDefault(configFile, gameDir);
        }

        try {
            String content = Files.readString(configFile, StandardCharsets.UTF_8);
            JsonElement element = JsonParser.parseString(content);
            if (!element.isJsonObject()) {
                throw new JsonParseException("config is not a JSON object");
            }
            JsonObject json = element.getAsJsonObject();
            String path = readString(json, KEY_SOCKET_PATH, DEFAULT_SOCKET_PATH.toString());
            String group = readString(json, KEY_SOCKET_ACCESS_GROUP, DEFAULT_SOCKET_ACCESS_GROUP);
            return new ModConfig(resolveSocketPath(gameDir, Path.of(path)), group);
        } catch (IOException | JsonParseException | IllegalArgumentException e) {
            MCChatAPI.LOGGER.warn("Could not read config {}, using defaults", configFile, e);
            return defaults(gameDir);
        }
    }

    private static ModConfig createDefault(Path configFile, Path gameDir) {
        JsonObject json = new JsonObject();
        json.addProperty(KEY_SOCKET_PATH, DEFAULT_SOCKET_PATH.toString());
        json.addProperty(KEY_SOCKET_ACCESS_GROUP, DEFAULT_SOCKET_ACCESS_GROUP);

        try {
            Files.createDirectories(configFile.getParent());
            String text = new GsonBuilder().setPrettyPrinting().create().toJson(json)
                    + System.lineSeparator();
            Files.writeString(configFile, text, StandardCharsets.UTF_8);
            MCChatAPI.LOGGER.info("Created default config at {}", configFile);
        } catch (IOException e) {
            MCChatAPI.LOGGER.warn("Could not write default config {}, using defaults in memory", configFile, e);
        }
        return defaults(gameDir);
    }

    private static Path resolveSocketPath(Path gameDir, Path path) {
        return path.isAbsolute() ? path : gameDir.resolve(path);
    }

    private static String readString(JsonObject json, String key, String fallback) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            return fallback;
        }
        return value.getAsString();
    }

    private static ModConfig defaults(Path gameDir) {
        return new ModConfig(resolveSocketPath(gameDir, DEFAULT_SOCKET_PATH), DEFAULT_SOCKET_ACCESS_GROUP);
    }
}
