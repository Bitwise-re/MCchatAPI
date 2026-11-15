package dev.bitwise.mcchatapi;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.Writer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

// Handles a client (unique socket connection)
public final class ClientConnection implements Closeable {
    private final ChatSocketServer owner;
    private final SocketChannel channel;
    private final BlockingQueue<String> outbound = new LinkedBlockingQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean(); //need atomic cause W/R on separate threads
    private final Thread readerThread;
    private final Thread writerThread;

    ClientConnection(ChatSocketServer _owner, SocketChannel _channel) {
        owner = _owner;
        channel = _channel;
        readerThread = Thread.ofVirtual().name("mcchatapi-client-read").unstarted(this::readLoop);
        writerThread = Thread.ofVirtual().name("mcchatapi-client-write").unstarted(this::writeLoop);
    }

    // start the threads
    public void start() {
        writerThread.start();
        readerThread.start();
    }

    // add packet to the fifo
    public void send(String packet) {
        if (!closed.get()) {
            outbound.offer(packet);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        try {
            channel.close();
        } catch (IOException e) {
            MCChatAPI.LOGGER.debug("Error closing client socket: {}", e.getMessage());
        }

        // free writer thread if stuck on outboud.take() (not awoken by channel.close())
        if (Thread.currentThread() != writerThread) {
            writerThread.interrupt();
        }

        owner.remove(this);
    }

    // get method for owner property
    public ChatSocketServer getOwner() {
        return owner;
    }


    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(Channels.newReader(channel, StandardCharsets.UTF_8))) {
            String line;
            while (!closed.get() && (line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                Protocol.handlePacket(this, line);
            }
        } catch (IOException e) {
            if (!closed.get()) {
                MCChatAPI.LOGGER.debug("Client read failed: {}", e.getMessage());
            }
        } catch (RuntimeException e) {
            MCChatAPI.LOGGER.warn("Closing client after protocol error: {}", e.getMessage());
        } finally {
            close();
        }
    }

    private void writeLoop() {
        try (Writer writer = Channels.newWriter(channel, StandardCharsets.UTF_8)) {
            while (!closed.get()) { //write loop : take from fifo and serve it through the pipe
                String packet = outbound.take();
                writer.write(packet);
                writer.write('\n');
                writer.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            if (!closed.get()) {
                MCChatAPI.LOGGER.debug("Client write failed: {}", e.getMessage());
            }
        } finally {
            close();
        }
    }
}
