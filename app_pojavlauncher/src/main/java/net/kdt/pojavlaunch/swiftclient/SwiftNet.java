package net.kdt.pojavlaunch.swiftclient;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.value.MinecraftAccount;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * SwiftClient services, the same ones the Windows launcher uses: live counters and capes from
 * the cape server, and a plain Minecraft status ping for server player counts.
 * Every method blocks; call from a background thread.
 */
public final class SwiftNet {
    private static final String SESSION_JOIN = "https://sessionserver.mojang.com/session/minecraft/join";
    private static final int TIMEOUT = 8000;

    private SwiftNet() {}

    public static final class Stats { public int users, online; }

    public static final class Cape {
        public String id, name, description, imageUrl;
        public boolean owned;
    }

    public static final class CapeCatalog {
        public final List<Cape> capes = new ArrayList<>();
        public String selected;
    }

    public static final class Ping { public int online, max; public long latencyMs; }

    public static Stats stats() throws IOException {
        try {
            JSONObject o = new JSONObject(get(SwiftClientInstaller.CAPE_API + "/stats"));
            Stats s = new Stats();
            s.users = o.optInt("users");
            s.online = o.optInt("online");
            return s;
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    /** Counts this install as online for the live "players online" numbers (anonymous random id). */
    public static void heartbeat(String installId, String version) {
        try {
            JSONObject body = new JSONObject();
            body.put("id", installId);
            body.put("version", "android-" + version);
            post(SwiftClientInstaller.CAPE_API + "/stats/heartbeat", body.toString());
        } catch (IOException | JSONException ignored) {
            // Counters are best effort.
        }
    }

    /** All capes, marking the ones this player may wear. uuid may be null (signed out). */
    public static CapeCatalog capes(String uuid) throws IOException {
        CapeCatalog catalog = new CapeCatalog();
        Set<String> owned = new HashSet<>();
        if (uuid != null) {
            try {
                JSONObject mine = new JSONObject(get(SwiftClientInstaller.CAPE_API + "/players/" + undash(uuid) + "/available"));
                JSONArray ids = mine.optJSONArray("capes");
                if (ids != null) for (int i = 0; i < ids.length(); i++) owned.add(ids.getString(i));
                catalog.selected = mine.isNull("selected") ? null : mine.optString("selected", null);
            } catch (IOException | JSONException ignored) {
                // The per-player call failing must not hide the whole catalog.
            }
        }
        try {
            JSONArray list = new JSONArray(get(SwiftClientInstaller.CAPE_API + "/capes"));
            for (int i = 0; i < list.length(); i++) {
                JSONObject c = list.getJSONObject(i);
                Cape cape = new Cape();
                cape.id = c.getString("id");
                cape.name = c.optString("name", cape.id);
                cape.description = c.optString("description", "");
                cape.owned = c.optBoolean("public") || owned.contains(cape.id);
                cape.imageUrl = SwiftClientInstaller.CAPE_API + "/capes/" + cape.id + ".png";
                catalog.capes.add(cape);
            }
        } catch (JSONException e) {
            throw new IOException("The cape server sent an unexpected reply", e);
        }
        return catalog;
    }

    /**
     * Wear a cape (or none with null). Proves the player like a Minecraft server does: Mojang's
     * session join with a one-time serverId from the cape server, which checks it with hasJoined.
     * If Mojang ties that join to a different IP, retry with the access token, which the cape
     * server verifies with Mojang and never stores.
     */
    public static void selectCape(MinecraftAccount account, String capeId) throws IOException {
        if (account == null || account.isLocal() || !account.isMicrosoft)
            throw new IOException("Sign in with Microsoft to pick a cape.");
        String serverId;
        try {
            serverId = new JSONObject(post(SwiftClientInstaller.CAPE_API + "/auth/challenge", "{}")).getString("serverId");
            JSONObject join = new JSONObject();
            join.put("accessToken", account.accessToken);
            join.put("selectedProfile", undash(account.profileId));
            join.put("serverId", serverId);
            post(SESSION_JOIN, join.toString());
        } catch (JSONException e) {
            throw new IOException(e);
        } catch (HttpException e) {
            if (e.code == 401 || e.code == 403)
                throw new IOException("Mojang rejected the session for " + account.username + ". Sign out and back in, then try again.");
            throw e;
        }
        try {
            JSONObject body = new JSONObject();
            body.put("name", account.username);
            body.put("serverId", serverId);
            body.put("cape", capeId == null ? JSONObject.NULL : capeId);
            try {
                post(SwiftClientInstaller.CAPE_API + "/select", body.toString());
            } catch (HttpException e) {
                if (e.code != 403 || !e.body.toLowerCase().contains("could not verify")) throw e;
                body.put("accessToken", account.accessToken);
                post(SwiftClientInstaller.CAPE_API + "/select", body.toString());
            }
        } catch (JSONException e) {
            throw new IOException(e);
        } catch (HttpException e) {
            throw new IOException(serverError(e));
        }
    }

    /** Minecraft server list ping (1.7+ protocol): players online and latency. */
    public static Ping ping(String address) throws IOException {
        String host = address;
        int port = 25565;
        int colon = address.lastIndexOf(':');
        if (colon > 0) {
            host = address.substring(0, colon);
            port = Integer.parseInt(address.substring(colon + 1));
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), TIMEOUT);
            long start = System.currentTimeMillis(); // after DNS and connect: the server's reply time
            socket.setSoTimeout(TIMEOUT);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            DataOutputStream h = new DataOutputStream(handshake);
            h.writeByte(0x00);
            writeVarInt(h, 767); // protocol: any recent value works for a status request
            byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
            writeVarInt(h, hostBytes.length);
            h.write(hostBytes);
            h.writeShort(port);
            writeVarInt(h, 1); // next state: status
            writeVarInt(out, handshake.size());
            out.write(handshake.toByteArray());
            out.writeByte(0x01); // length
            out.writeByte(0x00); // status request
            out.flush();

            readVarInt(in); // packet length
            long latency = System.currentTimeMillis() - start;
            readVarInt(in); // packet id
            byte[] json = new byte[readVarInt(in)];
            in.readFully(json);
            JSONObject players = new JSONObject(new String(json, StandardCharsets.UTF_8)).optJSONObject("players");
            Ping ping = new Ping();
            ping.latencyMs = latency;
            if (players != null) {
                ping.online = players.optInt("online");
                ping.max = players.optInt("max");
            }
            return ping;
        } catch (JSONException e) {
            throw new IOException("Bad status reply from " + address, e);
        }
    }

    public static byte[] getBytes(String url) throws IOException {
        HttpURLConnection conn = open(url);
        try {
            if (conn.getResponseCode() != 200) throw new HttpException(conn.getResponseCode(), "");
            return readAll(conn.getInputStream());
        } finally {
            conn.disconnect();
        }
    }

    private static String get(String url) throws IOException {
        return new String(getBytes(url), StandardCharsets.UTF_8);
    }

    private static String post(String url, String json) throws IOException {
        HttpURLConnection conn = open(url);
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                InputStream err = conn.getErrorStream();
                throw new HttpException(code, err == null ? "" : new String(readAll(err), StandardCharsets.UTF_8));
            }
            InputStream is = conn.getInputStream();
            return is == null ? "" : new String(readAll(is), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(TIMEOUT);
        conn.setReadTimeout(TIMEOUT);
        conn.setRequestProperty("User-Agent", Tools.APP_NAME + "-Android");
        return conn;
    }

    private static byte[] readAll(InputStream is) throws IOException {
        try (InputStream in = is) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    private static String serverError(HttpException e) {
        try {
            String msg = new JSONObject(e.body).optString("error", "");
            if (!msg.isEmpty()) return msg;
        } catch (JSONException ignored) {}
        return "The cape server refused the request (HTTP " + e.code + ").";
    }

    private static String undash(String uuid) {
        return uuid.replace("-", "");
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0, position = 0;
        byte b;
        do {
            b = in.readByte();
            value |= (b & 0x7F) << position;
            position += 7;
            if (position > 35) throw new IOException("VarInt too big");
        } while ((b & 0x80) != 0);
        return value;
    }

    static final class HttpException extends IOException {
        final int code;
        final String body;
        HttpException(int code, String body) {
            super("HTTP " + code);
            this.code = code;
            this.body = body;
        }
    }
}
