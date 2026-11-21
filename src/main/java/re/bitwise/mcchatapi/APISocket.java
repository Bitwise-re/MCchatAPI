package re.bitwise.mcchatapi;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

// Owns the Unix domain socket : directory, ACLs, binding and teardown.
public final class APISocket implements Closeable {
    private static final Pattern GROUP_NAME = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final long COMMAND_TIMEOUT_SECONDS = 5;

    private final Path path;
    private final ServerSocketChannel channel;

    APISocket(Path _path, String accessGroup) {
        path = _path;

        // Check the group before any ACL command : setfacl fails on unknown groups.
        if (accessGroup == null || !GROUP_NAME.matcher(accessGroup).matches()) {
            MCChatAPI.LOGGER.warn("Invalid socket access group '{}', socket will be owner-only", accessGroup);
            accessGroup = null;
        } else if (!groupExists(accessGroup)) {
            MCChatAPI.LOGGER.warn("Socket access group '{}' does not exist, socket will be owner-only", accessGroup);
            accessGroup = null;
        }

        //setup path
        try {
            Files.createDirectories(path.getParent());
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not prepare " + path, e);
        }

        //share the folder before the socket is created so it inherits the default ACL
        boolean folderShared = accessGroup != null && prepareFolder(path.getParent(), accessGroup);

        //open
        try {
            channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not open socket", e);
        }
        //bind
        try {
            channel.bind(UnixDomainSocketAddress.of(path));
        } catch (IOException e) {
            try { channel.close(); } catch (IOException ignored) { }
            throw new UncheckedIOException("Could not bind " + path, e);
        }

        //minimal permissions : owner rw + named group rw (mode 660), or owner only (600)
        if (folderShared) {
            secureSocket(accessGroup);
        } else {
            setPermissions(path, "rw-------", "600");
        }
    }

    public Path path() {
        return path;
    }

    public SocketChannel accept() throws IOException {
        return channel.accept();
    }

    @Override
    public void close() throws IOException {
        try {
            channel.close();
        } finally {
            Files.deleteIfExists(path);
        }
    }

    //returns true only when the group was verified and the ACLs were applied
    private static boolean prepareFolder(Path folder, String group) {
        String defaultAcl = "u::rwx,g::---,g:" + group + ":rw-,m::rw-,o::---";
        String accessAcl = "u::rwx,g::---,g:" + group + ":--x,m::--x,o::---";

        if (!setAcl(true, defaultAcl, folder) || !setAcl(false, accessAcl, folder)) {
            MCChatAPI.LOGGER.warn("Could not share socket folder {} with group '{}', using owner-only permissions", folder, group);
            setPermissions(folder, "rwx------", "700");
            return false;
        }
        return true;
    }

    private void secureSocket(String group) {
        String accessAcl = "u::rw-,g::---,g:" + group + ":rw-,m::rw-,o::---";
        if (!setAcl(false, accessAcl, path)) {
            MCChatAPI.LOGGER.warn("Could not set socket ACL, using owner-only permissions");
            setPermissions(path, "rw-------", "600");
        }
    }

    private static boolean groupExists(String group) {
        CommandResult result = run(List.of("getent", "group", group));
        if (result == null) {
            MCChatAPI.LOGGER.warn("Could not check if group '{}' exists, socket will be owner-only", group);
            return false;
        }
        return result.exitCode() == 0;
    }

    private static boolean setAcl(boolean defaultAcl, String spec, Path target) {
        List<String> command = new ArrayList<>();
        command.add("setfacl");
        if (defaultAcl) {
            command.add("-d");
        }
        command.add("-m");
        command.add(spec);
        command.add(target.toString());

        CommandResult result = run(command);
        if (result == null || result.exitCode() != 0) {
            MCChatAPI.LOGGER.warn("Could not set ACL on {} : {}", target,
                    result == null ? "setfacl unavailable" : result.output());
            return false;
        }
        return true;
    }

    private static CommandResult run(List<String> command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                MCChatAPI.LOGGER.warn("Command timed out : {}", String.join(" ", command));
                return null;
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            return new CommandResult(process.exitValue(), output);
        } catch (IOException e) {
            MCChatAPI.LOGGER.warn("Could not run {} : {}", command.get(0), e.toString());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static void setPermissions(Path target, String posix, String label) {
        try {
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString(posix));
        } catch (UnsupportedOperationException e) {
            MCChatAPI.LOGGER.warn("Filesystem has no POSIX permissions, skipping chmod {} on {}", label, target);
        } catch (IOException e) {
            MCChatAPI.LOGGER.warn("Could not chmod {} on {} : {}", label, target, e.toString());
        }
    }

    private record CommandResult(int exitCode, String output) { }
}
