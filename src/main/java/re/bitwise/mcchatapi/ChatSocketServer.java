package re.bitwise.mcchatapi;

import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.channels.ClosedChannelException;
import java.io.UncheckedIOException;
import java.util.concurrent.RejectedExecutionException;
import java.nio.file.attribute.PosixFilePermissions;

// Handles the server the clients connect to in its own thread (listener)
// distribute threads to handle them asynchronously.
public final class ChatSocketServer {
    private final Path socketPath;
    private final String chatPrefix;
    private final Set<ClientConnection> clients = ConcurrentHashMap.newKeySet();
    private final MinecraftServer server;
    private final ServerSocketChannel listenChannel;
    private final Thread listeningThread;

    ChatSocketServer(Path _socketPath, String _chatPrefix, MinecraftServer _server) {
        socketPath = _socketPath;
        chatPrefix = _chatPrefix;
        server = _server;


        //setup path
        try {
            Files.createDirectories(socketPath.getParent());
            Files.deleteIfExists(socketPath);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not prepare " + socketPath, e);
        }
        //open
        try {
            listenChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not open socket", e);
        }
        //bind
        try {
            listenChannel.bind(UnixDomainSocketAddress.of(socketPath));
        } catch (IOException e) {
            try { listenChannel.close(); } catch (IOException ignored) { }
            throw new UncheckedIOException("Could not bind " + socketPath, e);
        }

        // DEV : this is temporary, socket creation / perms will be added later
        try {
            Files.setPosixFilePermissions(socketPath, PosixFilePermissions.fromString("rw-rw-rw-"));
        } catch (UnsupportedOperationException e) {
            MCChatAPI.LOGGER.warn("Filesystem has no POSIX permissions, skipping chmod on {}", socketPath);
        } catch (IOException e) {
            MCChatAPI.LOGGER.warn("Could not chmod 666 {}", socketPath, e);
        }

        //start server
        listeningThread = Thread.ofPlatform().name("mcchatapi-listener").daemon(true)
                .start(() -> listenLoop(listenChannel)); //start the listening thread

        MCChatAPI.LOGGER.info("Listening on {}", socketPath);
    }

    public void stop() {
        boolean success = true;

        try { listenChannel.close(); }
        catch (IOException e) { MCChatAPI.LOGGER.warn("Failed to close listener", e); success = false; }

        listeningThread.interrupt();
        try { listeningThread.join(1000); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        if (listeningThread.isAlive()) { MCChatAPI.LOGGER.warn("ListeningThread did not stop within 1s"); success = false; }

        for (ClientConnection client : clients) {
            client.close();
        }
        clients.clear();

        try { Files.deleteIfExists(socketPath); }
        catch (IOException e) { MCChatAPI.LOGGER.warn("Failed to delete {}", socketPath, e); success = false; }

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

    public void injectMessage(String body) { 
        try {
            server.executeIfPossible(() -> {
                String line = chatPrefix + body;
                try {
                    server.getPlayerList().broadcastSystemMessage(Component.literal(line), false);
                } catch (RuntimeException e) {
                    MCChatAPI.LOGGER.error("Failed to inject web chat message", e);
                }
            });
        } catch (RejectedExecutionException ignored) {} // server is shutting down
    }

    private void listenLoop(ServerSocketChannel channel) {
        while (true) {
            try { 
                SocketChannel clientChannel = channel.accept();
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
