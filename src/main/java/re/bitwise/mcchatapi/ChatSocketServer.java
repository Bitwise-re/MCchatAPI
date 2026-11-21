package re.bitwise.mcchatapi;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

// Handles the server the clients connect to in its own thread (listener)
// distribute threads to handle them asynchronously.
public final class ChatSocketServer {
    private final APISocket socket;
    private final Set<ClientConnection> clients = ConcurrentHashMap.newKeySet();
    private final MinecraftServer server;
    private final Thread listeningThread;

    ChatSocketServer(Path socketPath, String accessGroup, MinecraftServer _server) {
        server = _server;
        socket = new APISocket(socketPath, accessGroup);

        //start server
        listeningThread = Thread.ofPlatform().name("mcchatapi-listener").daemon(true)
                .start(this::listenLoop); //start the listening thread

        MCChatAPI.LOGGER.info("Listening on {}", socket.path());
    }

    public void stop() {
        boolean success = true;

        try { socket.close(); }
        catch (IOException e) { MCChatAPI.LOGGER.warn("Failed to close socket", e); success = false; }

        listeningThread.interrupt();
        try { listeningThread.join(1000); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        if (listeningThread.isAlive()) { MCChatAPI.LOGGER.warn("ListeningThread did not stop within 1s"); success = false; }

        for (ClientConnection client : clients) {
            client.close();
        }
        clients.clear();

        if (success) { MCChatAPI.LOGGER.info("Sockets closed"); }
    }

    public void broadcastChat(String text) {
        if (clients.isEmpty()) {
            return;
        }

        String packet = Protocol.chat(text);
        for (ClientConnection client : clients) {
            client.send(packet);
        }
    }

    public void remove(ClientConnection client) {
        // synchronize remove+size so the debug count can't skip under concurrent disconnects
        synchronized (clients) {
            if (clients.remove(client)) {
                MCChatAPI.LOGGER.debug("Client disconnected ({} open)", clients.size());
            }
        }
    }

    public void injectMessage(String msg) { 
        try {
            server.executeIfPossible(() -> {
                try {
                    server.getPlayerList().broadcastSystemMessage(Component.literal(msg), false);
                } catch (RuntimeException e) {
                    MCChatAPI.LOGGER.error("Failed to inject web chat message", e);
                }
            });
        } catch (RejectedExecutionException ignored) {} // server is shutting down
    }

    private void listenLoop() {
        while (true) {
            try { 
                SocketChannel clientChannel = socket.accept();
                ClientConnection client = new ClientConnection(this, clientChannel);
                clients.add(client);
                client.start();
                MCChatAPI.LOGGER.debug("Client connected ({} connection open)", clients.size());
            } catch (ClosedChannelException e) {
                // when the channel is closed, stop the thread
                if (!server.isStopped()) {
                    MCChatAPI.LOGGER.warn("Listener channel closed unexpectedly", e);
                }
                break;
            } catch (IOException e) {
                MCChatAPI.LOGGER.error("Socket accept failed", e);
            } catch (RuntimeException e) {
                MCChatAPI.LOGGER.error("Unexpected socket error", e);
            }
        }
    }
}
