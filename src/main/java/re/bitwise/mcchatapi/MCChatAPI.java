package re.bitwise.mcchatapi;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UncheckedIOException;
import java.util.Locale;

public final class MCChatAPI implements ModInitializer {
    //mod identification to send logs into the console
    public static final String MOD_ID = "mcchatapi";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // loaded at startup from gamedir/config/mcchatapi.conf
    private static ModConfig config;

    //communication socket server
    // TODO : make socket creation part of the mod
    private ChatSocketServer socketServer;


    //Starting point when minecraft launches
    @Override
    public void onInitialize() {
        LOGGER.info("Initializing");

        // Unix domain sockets need a Unix-like host. Bail out before creating anything
        // (config file, event callbacks, socket) when running on Windows.
        String osName = System.getProperty("os.name", "");
        if (osName.toLowerCase(Locale.ROOT).contains("win")) {
            LOGGER.error("OS not supported: {} - Unix-like host required, mod disabled", osName);
            return;
        }

        config = ModConfig.load(FabricLoader.getInstance().getGameDir());

        //Start on server start and stop on server stop
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                socketServer = new ChatSocketServer(config.getSocketPath(), config.getSocketAccessGroup(), server);
            } catch (UncheckedIOException e) {
                LOGGER.error("mod disabled : could not open socket", e);
                socketServer = null;
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (socketServer != null) {
                socketServer.stop();
                socketServer = null;
            }
        });

        //Broadcast each public text that appears in chat to the sockets (php clients)
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) ->
                broadcast(params.decorate(message.decoratedContent()).getString()));
        ServerMessageEvents.COMMAND_MESSAGE.register((message, source, params) ->
                broadcast(params.decorate(message.decoratedContent()).getString()));
        ServerMessageEvents.GAME_MESSAGE.register((server, message, overlay) -> {
            if (!overlay) {
                broadcast(message.getString());
            }
        });
    }

    private void broadcast(String text) {
        if (socketServer == null) { return; }
        socketServer.broadcastChat(text);
    }
}
