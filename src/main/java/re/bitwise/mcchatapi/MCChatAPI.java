package re.bitwise.mcchatapi;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.UncheckedIOException;

import java.nio.file.Path;

public final class MCChatAPI implements ModInitializer {
    //mod identification to send logs into the console
    public static final String MOD_ID = "mcchatapi";
    private static final Path SOCKET_PATH = Path.of("/run/mcchat/mcchat.sock");
    private static final String PREFIX = "[bitwise.re Livechat] ";
    // TODO : make the strings above part of a config file
    // CAUTION : static vars are declared before any config can be read
    // drop static final keywords and init in onInit() after cfg read

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    //communication socket server
    // TODO : make socket creation part of the mod
    private ChatSocketServer socketServer;


    //Starting point when minecraft launches
    @Override
    public void onInitialize() {
        LOGGER.info("Initializing");

        //Start on server start and stop on server stop
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                socketServer = new ChatSocketServer(SOCKET_PATH,PREFIX,server);
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
