package net.kdt.pojavlaunch.swiftclient;

import android.content.Context;
import android.util.Base64;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.modloaders.FabricVersion;
import net.kdt.pojavlaunch.modloaders.FabriclikeUtils;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.utils.DownloadUtils;
import net.kdt.pojavlaunch.utils.FileUtils;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One-tap SwiftClient setup: Fabric for the chosen Minecraft version, a "SwiftClient &lt;version&gt;"
 * profile with its own game folder, and the SwiftClient mod plus Fabric API in that folder's mods.
 * The mod jars come from the SwiftClient GitHub release, Fabric API from Modrinth.
 */
public class SwiftClientInstaller {
    public static final String CAPE_API = "https://capes.swiftsmp.com";
    public static final String SERVER_ADDRESS = "swiftsmp.net";
    public static final String SERVER_NAME = "SwiftSMP";
    /** System properties the SwiftClient mod reads; added at launch whenever the mod is installed. */
    public static final String[] JVM_PROPERTIES = {
            "-Dswiftclient.capeApi=" + CAPE_API,
            "-Dswiftclient.server.address=" + SERVER_ADDRESS,
            "-Dswiftclient.server.name=" + SERVER_NAME,
    };

    private static final String RELEASE_API = "https://api.github.com/repos/SwiftSMP/SwiftClient/releases/latest";
    private static final String FABRIC_API_VERSIONS = "https://api.modrinth.com/v2/project/fabric-api/version?loaders=%s&game_versions=%s";
    // swiftclient-1.4.0+1.21.8.jar -> 1.21.8
    private static final Pattern MOD_ASSET = Pattern.compile("^swiftclient-[^+]+\\+(.+)\\.jar$");

    /** Minecraft version -> SwiftClient mod download URL, newest Minecraft first. */
    public static Map<String, String> fetchVersions() throws IOException {
        try {
            JSONArray assets = new JSONObject(DownloadUtils.downloadString(RELEASE_API)).getJSONArray("assets");
            List<String[]> found = new ArrayList<>();
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                Matcher m = MOD_ASSET.matcher(asset.getString("name"));
                if (m.matches()) found.add(new String[]{m.group(1), asset.getString("browser_download_url")});
            }
            Collections.sort(found, (a, b) -> compareVersions(b[0], a[0]));
            Map<String, String> versions = new LinkedHashMap<>();
            for (String[] v : found) versions.put(v[0], v[1]);
            return versions;
        } catch (JSONException e) {
            throw new IOException("Unexpected reply from GitHub", e);
        }
    }

    /** Runs on a background thread. Returns the name of the created profile. */
    public static String install(Context context, String mcVersion, String modUrl) throws IOException {
        try {
            ProgressKeeper.submitProgress(ProgressLayout.INSTALL_MODPACK, 0, R.string.swiftclient_progress, "Fabric");
            String loader = newestStableLoader(mcVersion);
            String fabricJson = DownloadUtils.downloadString(FabriclikeUtils.FABRIC_UTILS.createJsonDownloadUrl(mcVersion, loader));
            String versionId;
            try {
                versionId = new JSONObject(fabricJson).getString("id");
            } catch (JSONException e) {
                throw new IOException("Fabric returned an invalid profile for " + mcVersion, e);
            }
            File versionDir = new File(Tools.DIR_HOME_VERSION, versionId);
            FileUtils.ensureDirectory(versionDir);
            Tools.write(new File(versionDir, versionId + ".json").getAbsolutePath(), fabricJson);

            String gameDirName = "custom_instances/swiftclient-" + mcVersion;
            File modsDir = new File(Tools.DIR_GAME_HOME, gameDirName + "/mods");
            FileUtils.ensureDirectory(modsDir);
            removeOld(modsDir, "swiftclient-");
            removeOld(modsDir, "fabric-api-");

            ProgressKeeper.submitProgress(ProgressLayout.INSTALL_MODPACK, 40, R.string.swiftclient_progress, "SwiftClient");
            String modName = modUrl.substring(modUrl.lastIndexOf('/') + 1).replace("%2B", "+");
            DownloadUtils.downloadFile(modUrl, new File(modsDir, modName));

            ProgressKeeper.submitProgress(ProgressLayout.INSTALL_MODPACK, 70, R.string.swiftclient_progress, "Fabric API");
            String[] fabricApi = newestFabricApi(mcVersion);
            if (fabricApi != null) DownloadUtils.downloadFile(fabricApi[1], new File(modsDir, fabricApi[0]));

            // A fixed key per version, so installing again updates the same profile instead of adding one.
            String key = UUID.nameUUIDFromBytes(("swiftclient-" + mcVersion).getBytes(StandardCharsets.UTF_8)).toString();
            LauncherProfiles.load();
            MinecraftProfile profile = LauncherProfiles.mainProfileJson.profiles.get(key);
            if (profile == null) profile = new MinecraftProfile();
            profile.name = "SwiftClient " + mcVersion;
            profile.lastVersionId = versionId;
            profile.gameDir = Tools.LAUNCHERPROFILES_RTPREFIX + gameDirName;
            profile.icon = icon(context);
            LauncherProfiles.mainProfileJson.profiles.put(key, profile);
            LauncherProfiles.write();
            LauncherPreferences.DEFAULT_PREF.edit().putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, key).apply();
            return profile.name;
        } finally {
            ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
        }
    }

    private static String newestStableLoader(String mcVersion) throws IOException {
        FabricVersion[] loaders = FabriclikeUtils.FABRIC_UTILS.downloadLoaderVersions(mcVersion);
        if (loaders == null || loaders.length == 0) throw new IOException("Fabric does not support Minecraft " + mcVersion + " yet");
        for (FabricVersion v : loaders) if (v.stable) return v.version;
        return loaders[0].version;
    }

    /** {file name, url} of the newest Fabric API release for this version, or null if there is none. */
    private static String[] newestFabricApi(String mcVersion) throws IOException {
        String url = String.format(FABRIC_API_VERSIONS,
                URLEncoder.encode("[\"fabric\"]", "UTF-8"), URLEncoder.encode("[\"" + mcVersion + "\"]", "UTF-8"));
        try {
            JSONArray versions = new JSONArray(DownloadUtils.downloadString(url));
            JSONObject pick = null;
            for (int i = 0; i < versions.length() && pick == null; i++) {
                if ("release".equals(versions.getJSONObject(i).optString("version_type"))) pick = versions.getJSONObject(i);
            }
            if (pick == null && versions.length() > 0) pick = versions.getJSONObject(0);
            if (pick == null) return null;
            JSONArray files = pick.getJSONArray("files");
            JSONObject file = files.getJSONObject(0);
            for (int i = 0; i < files.length(); i++) if (files.getJSONObject(i).optBoolean("primary")) file = files.getJSONObject(i);
            return new String[]{file.getString("filename"), file.getString("url")};
        } catch (JSONException e) {
            throw new IOException("Unexpected reply from Modrinth", e);
        }
    }

    private static void removeOld(File modsDir, String prefix) {
        File[] files = modsDir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName().toLowerCase();
            if (name.startsWith(prefix) && name.endsWith(".jar")) //noinspection ResultOfMethodCallIgnored
                f.delete();
        }
    }

    private static String icon(Context context) {
        try (InputStream in = context.getResources().openRawResource(R.raw.swiftclient_icon)) {
            return "data:image/png;base64," + Base64.encodeToString(IOUtils.toByteArray(in), Base64.NO_WRAP);
        } catch (IOException e) {
            return "fabric";
        }
    }

    /** Compares dotted Minecraft versions numerically (1.21.10 > 1.21.9, 26.1 > 1.21.11). */
    static int compareVersions(String a, String b) {
        String[] pa = a.split("\\."), pb = b.split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            int x = i < pa.length ? parse(pa[i]) : 0, y = i < pb.length ? parse(pb[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static int parse(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }
}
