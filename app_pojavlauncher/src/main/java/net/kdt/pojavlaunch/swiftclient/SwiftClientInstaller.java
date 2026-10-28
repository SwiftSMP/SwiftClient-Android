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

    /**
     * Performance mods that are safe with Android's OpenGL translation layers. Renderer
     * replacements (Sodium, ImmediatelyFast, Iris) are left out on purpose: they crash or
     * render wrong on GL4ES/MobileGlues/Zink.
     */
    public static final String[] PERFORMANCE_MODS = {"lithium", "ferrite-core", "modernfix", "entityculling"};
    /** Written once into a new instance: settings that matter most for phone GPUs and thermals. */
    private static final String[] PHONE_OPTIONS = {
            "renderDistance:8", "simulationDistance:6", "graphicsMode:0", "fancyGraphics:false",
            "renderClouds:\"false\"", "particles:1", "entityShadows:false", "maxFps:60",
            "biomeBlendRadius:0", "mipmapLevels:0", "entityDistanceScaling:0.75", "ao:false",
            "enableVsync:false", "guiScale:3", "fullscreen:false", "tutorialStep:none",
            "skipMultiplayerWarning:true", "onboardAccessibility:false",
    };
    private static final String MANAGED_LIST = ".swiftclient-managed";

    private static final String RELEASE_API = "https://api.github.com/repos/SwiftSMP/SwiftClient/releases/latest";
    private static final String MODRINTH_VERSIONS = "https://api.modrinth.com/v2/project/%s/version?loaders=%s&game_versions=%s";
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

            String gameDirName = gameDirName(mcVersion);
            File gameDir = new File(Tools.DIR_GAME_HOME, gameDirName);
            File modsDir = new File(gameDir, "mods");
            FileUtils.ensureDirectory(modsDir);
            // Remove what an earlier install put there; mods the player added stay.
            File managed = new File(gameDir, MANAGED_LIST);
            if (managed.isFile()) {
                for (String name : Tools.read(managed.getAbsolutePath()).split("\n")) {
                    if (!name.trim().isEmpty()) //noinspection ResultOfMethodCallIgnored
                        new File(modsDir, name.trim()).delete();
                }
            }
            removeOld(modsDir, "swiftclient-");
            StringBuilder installed = new StringBuilder();

            ProgressKeeper.submitProgress(ProgressLayout.INSTALL_MODPACK, 30, R.string.swiftclient_progress, "SwiftClient");
            String modName = modUrl.substring(modUrl.lastIndexOf('/') + 1).replace("%2B", "+");
            DownloadUtils.downloadFile(modUrl, new File(modsDir, modName));
            installed.append(modName).append('\n');

            String[] projects = new String[PERFORMANCE_MODS.length + 1];
            projects[0] = "fabric-api";
            System.arraycopy(PERFORMANCE_MODS, 0, projects, 1, PERFORMANCE_MODS.length);
            for (int i = 0; i < projects.length; i++) {
                ProgressKeeper.submitProgress(ProgressLayout.INSTALL_MODPACK, 40 + 55 * i / projects.length,
                        R.string.swiftclient_progress, projects[i]);
                String[] file = newestModrinthFile(projects[i], mcVersion);
                if (file == null) continue; // no build for this Minecraft version yet: skip it
                File target = new File(modsDir, file[0]);
                if (!target.exists()) DownloadUtils.downloadFile(file[1], target);
                installed.append(file[0]).append('\n');
            }
            Tools.write(managed.getAbsolutePath(), installed.toString());

            File options = new File(gameDir, "options.txt");
            if (!options.exists()) Tools.write(options.getAbsolutePath(), String.join("\n", PHONE_OPTIONS) + "\n");

            // A fixed key per version, so installing again updates the same profile instead of adding one.
            String key = profileKey(mcVersion);
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

    /** {file name, url} of the newest release of a Modrinth project for this version, or null if there is none. */
    private static String[] newestModrinthFile(String project, String mcVersion) throws IOException {
        String url = String.format(MODRINTH_VERSIONS, project,
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

    public static String gameDirName(String mcVersion) {
        return "custom_instances/swiftclient-" + mcVersion;
    }

    public static File gameDir(String mcVersion) {
        return new File(Tools.DIR_GAME_HOME, gameDirName(mcVersion));
    }

    /** A fixed profile key per version, so installing again updates the same profile instead of adding one. */
    public static String profileKey(String mcVersion) {
        return UUID.nameUUIDFromBytes(("swiftclient-" + mcVersion).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** True when this version's profile exists and its mods folder has the SwiftClient mod. */
    public static boolean isInstalled(String mcVersion) {
        LauncherProfiles.load();
        if (LauncherProfiles.mainProfileJson.profiles.get(profileKey(mcVersion)) == null) return false;
        File[] mods = new File(gameDir(mcVersion), "mods").listFiles();
        if (mods == null) return false;
        for (File f : mods) if (f.getName().startsWith("swiftclient-") && f.getName().endsWith(".jar")) return true;
        return false;
    }

    /**
     * Server to join straight from the launcher. The game runs in its own process, so the
     * address goes through a file that the next launch reads once and deletes.
     */
    public static void setQuickPlay(String address) throws IOException {
        File f = new File(Tools.DIR_GAME_HOME, ".swiftclient-quickplay");
        if (address == null) //noinspection ResultOfMethodCallIgnored
            f.delete();
        else Tools.write(f.getAbsolutePath(), address);
    }

    /** Game process: the pending quick-play address, or null. Consumes it. */
    public static String takeQuickPlay() {
        File f = new File(Tools.DIR_GAME_HOME, ".swiftclient-quickplay");
        if (!f.isFile()) return null;
        try {
            String address = Tools.read(f).trim();
            return address.isEmpty() ? null : address;
        } catch (IOException e) {
            return null;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    /**
     * Garbage collector tuning for SwiftClient profiles: short G1 pauses instead of long
     * stop-the-world hitches, which are what players feel as stutter on phones.
     * Skipped when the player chose a collector themselves (two collectors refuse to start).
     */
    public static List<String> gcArgs(String userArgs) {
        List<String> args = new ArrayList<>();
        if (userArgs != null && userArgs.contains("GC")) return args;
        Collections.addAll(args, "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=40", "-XX:G1HeapRegionSize=4M",
                "-XX:+ParallelRefProcEnabled", "-XX:+DisableExplicitGC", "-XX:+UseStringDeduplication");
        return args;
    }

    /** Makes this version's SwiftClient profile the one Play launches. */
    public static void select(String mcVersion) {
        LauncherPreferences.DEFAULT_PREF.edit().putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, profileKey(mcVersion)).apply();
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
