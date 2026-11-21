package re.bitwise.mcchatapi;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/*
 * Class containing helper functions to format packets as per the protocol :
 *
 * Handshake : php -> {"type":"hello"}  || API response -> {"type":"ack"}
 * Message : php -> {"type":"Wmsg", "msg":"this is the message" }
 * Broadcast : api -> {"type":"Mmsg","msg":"this is the message"}
 */
public final class Protocol {
    private Protocol() {} //no public constructor : this is a helper class

    // handles client packets
    public static void handlePacket(ClientConnection client, String line) {
        JsonObject packet = parse(line);
        String type = requiredString(packet, "type");

        switch (type) {
            case "hello" -> client.send(acknowledge());
            case "Wmsg" -> client.getOwner().injectMessage(requiredString(packet, "msg"));
            default -> throw new IllegalArgumentException("Unknown packet type: " + type);
        }
    }

    //new message packet
    public static String chat(String msg) {
        JsonObject packet = new JsonObject();
        packet.addProperty("type", "Mmsg");
        packet.addProperty("msg", msg);
        return packet.toString();
    }

    //format a string into JSON to be sent
    private static JsonObject parse(String line) {
        JsonElement element = JsonParser.parseString(line);
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException("packet is not a JSON object");
        }
        return element.getAsJsonObject();
    }

    //get property value in a packet, returns error if not found
    private static String requiredString(JsonObject packet, String property) {
        JsonElement value = packet.get(property);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("packet is missing property '" + property + "'");
        }
        return value.getAsString();
    }

    //handshake response packet
    private static String acknowledge() {
        JsonObject packet = new JsonObject();
        packet.addProperty("type", "ack");
        return packet.toString();
    }
}
