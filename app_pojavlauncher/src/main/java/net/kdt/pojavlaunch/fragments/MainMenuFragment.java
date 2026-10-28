package net.kdt.pojavlaunch.fragments;

import static net.kdt.pojavlaunch.Tools.hasNoOnlineProfileDialog;
import static net.kdt.pojavlaunch.Tools.hasOnlineProfile;
import static net.kdt.pojavlaunch.Tools.openPath;
import static net.kdt.pojavlaunch.Tools.runOnUiThread;
import static net.kdt.pojavlaunch.Tools.shareLog;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.Fragment;

import com.kdt.mcgui.mcVersionSpinner;

import net.kdt.pojavlaunch.CustomControlsActivity;
import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.PojavProfile;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.swiftclient.SwiftClientInstaller;
import net.kdt.pojavlaunch.swiftclient.SwiftNet;
import net.kdt.pojavlaunch.value.MinecraftAccount;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * SwiftClient home screen. Four tabs like the Windows launcher: Play (one button that installs
 * SwiftClient for the chosen version if needed, then launches), Servers (live pings, one-tap
 * join), Mods (switch the instance's mods on and off, add jars) and Cloaks.
 */
public class MainMenuFragment extends Fragment {
    public static final String TAG = "MainMenuFragment";

    private static final String PREF_VERSION = "swiftclient_version";
    private static final String PREF_INSTALL_ID = "swiftclient_install_id";
    private static final String[][] SERVERS = {
            // name, address, icon, tag
            {"SwiftSMP", SwiftClientInstaller.SERVER_ADDRESS, "home"},
            {"CyanRealms", "play.cyanrealms.com", "partner"},
            {"ValenMC", "play.valenmc.fun", "partner"},
    };
    /** Versions the latest SwiftClient release has a mod for; kept for the life of the process. */
    private static Map<String, String> sVersions;
    private static boolean sHeartbeatSent;
    /** Last live counters, shown at once while fresh ones load. */
    private static SwiftNet.Stats sStats;
    private static SwiftNet.Ping sSwiftSmpPing;
    private static final Map<String, String> MOD_NAMES = new java.util.HashMap<>();
    static {
        MOD_NAMES.put("swiftclient", "SwiftClient");
        MOD_NAMES.put("fabric api", "Fabric API");
        MOD_NAMES.put("entityculling", "EntityCulling");
        MOD_NAMES.put("ferritecore", "FerriteCore");
        MOD_NAMES.put("modernfix", "ModernFix");
        MOD_NAMES.put("lithium", "Lithium");
    }

    private mcVersionSpinner mVersionSpinner;
    private View[] mPanels;
    private TextView[] mTabs;
    private TextView mVersionText, mVersionStatus;
    private String mVersion;

    private final ActivityResultLauncher<String[]> mAddModLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::importMod);

    /** Selects a profile that was made current elsewhere, such as by the SwiftClient installer. */
    private final SharedPreferences.OnSharedPreferenceChangeListener mProfileListener = (prefs, key) -> {
        if (!LauncherPreferences.PREF_KEY_CURRENT_PROFILE.equals(key)) return;
        runOnUiThread(() -> {
            if (!isResumed()) return;
            selectCurrentProfile();
        });
    };

    public MainMenuFragment(){
        super(R.layout.fragment_launcher);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mVersionSpinner = view.findViewById(R.id.mc_version_spinner);
        mVersionText = view.findViewById(R.id.sc_version);
        mVersionStatus = view.findViewById(R.id.sc_version_status);
        mPanels = new View[]{view.findViewById(R.id.sc_panel_play), view.findViewById(R.id.sc_panel_servers),
                view.findViewById(R.id.sc_panel_mods), view.findViewById(R.id.sc_panel_cloaks)};
        mTabs = new TextView[]{view.findViewById(R.id.sc_tab_play), view.findViewById(R.id.sc_tab_servers),
                view.findViewById(R.id.sc_tab_mods), view.findViewById(R.id.sc_tab_cloaks)};
        for (int i = 0; i < mTabs.length; i++) {
            final int tab = i;
            mTabs[i].setOnClickListener(v -> showTab(tab));
        }
        showTab(0);

        mVersion = LauncherPreferences.DEFAULT_PREF.getString(PREF_VERSION, null);
        view.findViewById(R.id.sc_version_card).setOnClickListener(v -> pickVersion());
        view.findViewById(R.id.play_button).setOnClickListener(v -> play(null));
        view.findViewById(R.id.sc_join_swiftsmp).setOnClickListener(v -> play(SwiftClientInstaller.SERVER_ADDRESS));

        view.findViewById(R.id.custom_control_button).setOnClickListener(v -> startActivity(new Intent(requireContext(), CustomControlsActivity.class)));
        view.findViewById(R.id.share_logs_button).setOnClickListener(v -> shareLog(requireContext()));
        Button installJar = view.findViewById(R.id.install_jar_button);
        if (hasOnlineProfile()) {
            installJar.setOnClickListener(v -> runInstallerWithConfirmation(false));
            installJar.setOnLongClickListener(v -> {
                runInstallerWithConfirmation(true);
                return true;
            });
        } else installJar.setOnClickListener(v -> hasNoOnlineProfileDialog(requireActivity()));
        view.findViewById(R.id.open_files_button).setOnClickListener(v -> {
            if (Tools.isDemoProfile(v.getContext())) {
                hasNoOnlineProfileDialog(getActivity(), getString(R.string.demo_unsupported), getString(R.string.change_account));
            } else if (!hasOnlineProfile()) {
                hasNoOnlineProfileDialog(requireActivity());
            } else openPath(v.getContext(), getCurrentProfileDirectory(), false);
        });
        // Tool tiles: icons of different sources come in different sizes; draw them all at 26dp.
        for (int id : new int[]{R.id.custom_control_button, R.id.open_files_button, R.id.share_logs_button, R.id.install_jar_button}) {
            Button tile = view.findViewById(id);
            android.graphics.drawable.Drawable icon = tile.getCompoundDrawables()[1];
            if (icon != null) {
                icon.setBounds(0, 0, dp(26), dp(26));
                tile.setCompoundDrawables(null, icon, null, null);
            }
        }
        ImageButton editProfile = view.findViewById(R.id.edit_profile_button);
        editProfile.setOnClickListener(v -> mVersionSpinner.openProfileEditor(requireActivity()));

        view.findViewById(R.id.sc_add_mod).setOnClickListener(v -> mAddModLauncher.launch(new String[]{"*/*"}));
        view.findViewById(R.id.sc_update_mods).setOnClickListener(v -> {
            if (mVersion != null && sVersions != null) install(mVersion, null, false);
        });

        buildServerList(view.findViewById(R.id.sc_server_list));
        loadVersions();
        loadStats();
    }

    // ---------------------------------------------------------------- tabs

    private void showTab(int index) {
        for (int i = 0; i < mPanels.length; i++) {
            mPanels[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
            mTabs[i].setSelected(i == index);
        }
        View scroll = getView() == null ? null : getView().findViewById(R.id.sc_scroll);
        if (scroll != null) scroll.scrollTo(0, 0);
        if (index == 1) pingServers();
        if (index == 2) buildModList();
        if (index == 3) loadCloaks();
    }

    // ---------------------------------------------------------------- play

    private void loadVersions() {
        if (sVersions != null) {
            onVersionsLoaded();
            return;
        }
        PojavApplication.sExecutorService.execute(() -> {
            try {
                Map<String, String> versions = SwiftClientInstaller.fetchVersions();
                runOnUiThread(() -> {
                    sVersions = versions;
                    if (isAdded()) onVersionsLoaded();
                });
            } catch (IOException e) {
                runOnUiThread(() -> {
                    if (!isAdded()) return;
                    mVersionText.setText(mVersion != null ? "Minecraft " + mVersion : "—");
                    mVersionStatus.setText(R.string.sc_net_error);
                });
            }
        });
    }

    private void onVersionsLoaded() {
        if (mVersion == null || !sVersions.containsKey(mVersion)) {
            // Default to the newest 1.21.x: it runs on Java 21, which phones handle best.
            mVersion = null;
            for (String v : sVersions.keySet()) if (v.startsWith("1.")) { mVersion = v; break; }
            if (mVersion == null && !sVersions.isEmpty()) mVersion = sVersions.keySet().iterator().next();
        }
        refreshVersionCard();
    }

    private void refreshVersionCard() {
        if (mVersion == null) return;
        mVersionText.setText("Minecraft " + mVersion + "  ▾");
        PojavApplication.sExecutorService.execute(() -> {
            boolean installed = SwiftClientInstaller.isInstalled(mVersion);
            int mods = installed ? enabledModCount(mVersion) : 0;
            runOnUiThread(() -> {
                if (!isAdded()) return;
                mVersionStatus.setText(installed ? getString(R.string.sc_installed, mods) : getString(R.string.sc_not_installed));
                TextView sub = requireView().findViewById(R.id.sc_mods_sub);
                sub.setText(getString(R.string.sc_mods_sub, mVersion));
            });
        });
    }

    private void pickVersion() {
        if (sVersions == null || sVersions.isEmpty()) {
            loadVersions();
            return;
        }
        String[] ids = sVersions.keySet().toArray(new String[0]);
        String[] labels = new String[ids.length];
        int checked = 0;
        for (int i = 0; i < ids.length; i++) {
            // Minecraft 26.x runs on Java 25; 1.21.x on Java 21.
            labels[i] = ids[i].startsWith("1.") ? "Minecraft " + ids[i]
                    : "Minecraft " + ids[i] + "  · " + getString(R.string.swiftclient_java25);
            if (ids[i].equals(mVersion)) checked = i;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.swiftclient_pick_version)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    mVersion = ids[which];
                    LauncherPreferences.DEFAULT_PREF.edit().putString(PREF_VERSION, mVersion).apply();
                    refreshVersionCard();
                    d.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Play, joining serverAddress when it is not null. Installs SwiftClient first when needed. */
    private void play(@Nullable String serverAddress) {
        if (ProgressKeeper.getTaskCount() != 0) {
            Toast.makeText(requireContext(), R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
            return;
        }
        if (mVersion == null || sVersions == null) {
            loadVersions();
            Toast.makeText(requireContext(), R.string.sc_loading, Toast.LENGTH_SHORT).show();
            return;
        }
        PojavApplication.sExecutorService.execute(() -> {
            boolean installed = SwiftClientInstaller.isInstalled(mVersion);
            runOnUiThread(() -> {
                if (installed) launch(mVersion, serverAddress);
                else install(mVersion, serverAddress, true);
            });
        });
    }

    private void install(String version, @Nullable String serverAddress, boolean launchAfter) {
        if (ProgressKeeper.getTaskCount() != 0) {
            Toast.makeText(requireContext(), R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
            return;
        }
        Context context = requireContext().getApplicationContext();
        String modUrl = sVersions.get(version);
        PojavApplication.sExecutorService.execute(() -> {
            try {
                String profileName = SwiftClientInstaller.install(context, version, modUrl);
                runOnUiThread(() -> {
                    Toast.makeText(context, context.getString(R.string.swiftclient_done, profileName), Toast.LENGTH_SHORT).show();
                    if (!isAdded()) return;
                    refreshVersionCard();
                    if (mPanels[2].getVisibility() == View.VISIBLE) buildModList();
                    if (launchAfter) launch(version, serverAddress);
                });
            } catch (Exception e) {
                Tools.showErrorRemote(context, R.string.swiftclient_failed, e);
            }
        });
    }

    private void launch(String version, @Nullable String serverAddress) {
        try {
            SwiftClientInstaller.setQuickPlay(serverAddress);
        } catch (IOException e) {
            Tools.showError(requireContext(), e);
            return;
        }
        SwiftClientInstaller.select(version);
        LauncherProfiles.load();
        ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true);
    }

    private void loadStats() {
        showStats();
        PojavApplication.sExecutorService.execute(() -> {
            if (!sHeartbeatSent) {
                sHeartbeatSent = true;
                String id = LauncherPreferences.DEFAULT_PREF.getString(PREF_INSTALL_ID, null);
                if (id == null) {
                    id = UUID.randomUUID().toString();
                    LauncherPreferences.DEFAULT_PREF.edit().putString(PREF_INSTALL_ID, id).apply();
                }
                SwiftNet.heartbeat(id, "1.0.0");
            }
            try { sStats = SwiftNet.stats(); } catch (IOException ignored) {}
            try { sSwiftSmpPing = SwiftNet.ping(SwiftClientInstaller.SERVER_ADDRESS); } catch (Exception ignored) {}
            runOnUiThread(() -> {
                if (isAdded()) showStats();
            });
        });
    }

    private void showStats() {
        TextView online = requireView().findViewById(R.id.sc_chip_online);
        TextView installs = requireView().findViewById(R.id.sc_chip_installs);
        TextView swiftsmp = requireView().findViewById(R.id.sc_chip_swiftsmp);
        online.setVisibility(sStats == null ? View.GONE : View.VISIBLE);
        installs.setVisibility(sStats == null ? View.GONE : View.VISIBLE);
        swiftsmp.setVisibility(sSwiftSmpPing == null ? View.GONE : View.VISIBLE);
        if (sStats != null) {
            online.setText(getString(R.string.sc_online_chip, sStats.online));
            installs.setText(getString(R.string.sc_installs_chip, sStats.users));
        }
        if (sSwiftSmpPing != null) swiftsmp.setText(getString(R.string.sc_swiftsmp_chip, sSwiftSmpPing.online));
    }

    // ---------------------------------------------------------------- servers

    private final List<TextView> mServerStatus = new ArrayList<>();

    private void buildServerList(LinearLayout list) {
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (String[] server : SERVERS) {
            View card = inflater.inflate(R.layout.item_sc_server, list, false);
            ((TextView) card.findViewById(R.id.sc_server_name)).setText(server[0]);
            ((TextView) card.findViewById(R.id.sc_server_address)).setText(server[1]);
            TextView tag = card.findViewById(R.id.sc_server_tag);
            boolean home = "home".equals(server[2]);
            tag.setText(home ? R.string.sc_home : R.string.sc_partner);
            if (home) card.setBackgroundResource(R.drawable.sc_card_hero);
            ImageView icon = card.findViewById(R.id.sc_server_icon);
            icon.setImageResource(server[0].equals("CyanRealms") ? R.drawable.srv_cyanrealms
                    : server[0].equals("ValenMC") ? R.drawable.srv_valenmc : R.drawable.ic_swiftclient);
            card.findViewById(R.id.sc_server_join).setOnClickListener(v -> play(server[1]));
            mServerStatus.add(card.findViewById(R.id.sc_server_status));
            list.addView(card);
        }
    }

    private void pingServers() {
        for (int i = 0; i < SERVERS.length; i++) {
            TextView status = mServerStatus.get(i);
            String address = SERVERS[i][1];
            status.setText(R.string.sc_pinging);
            PojavApplication.sExecutorService.execute(() -> {
                SwiftNet.Ping ping = null;
                try { ping = SwiftNet.ping(address); } catch (Exception ignored) {}
                SwiftNet.Ping p = ping;
                runOnUiThread(() -> {
                    if (!isAdded()) return;
                    if (p == null) {
                        status.setText(R.string.sc_offline);
                        status.setTextColor(ContextCompat.getColor(requireContext(), R.color.sc_faint));
                    } else {
                        status.setText(getString(R.string.sc_players, p.online, p.max, p.latencyMs));
                        status.setTextColor(ContextCompat.getColor(requireContext(), R.color.sc_green));
                    }
                });
            });
        }
    }

    // ---------------------------------------------------------------- mods

    private int enabledModCount(String version) {
        File[] files = new File(SwiftClientInstaller.gameDir(version), "mods").listFiles();
        int n = 0;
        if (files != null) for (File f : files) if (f.getName().endsWith(".jar")) n++;
        return n;
    }

    private void buildModList() {
        LinearLayout list = requireView().findViewById(R.id.sc_mod_list);
        list.removeAllViews();
        if (mVersion == null || !SwiftClientInstaller.isInstalled(mVersion)) {
            TextView empty = new TextView(requireContext());
            empty.setText(R.string.sc_mods_none);
            empty.setTextColor(ContextCompat.getColor(requireContext(), R.color.sc_muted));
            empty.setPadding(0, dp(12), 0, 0);
            list.addView(empty);
            return;
        }
        File gameDir = SwiftClientInstaller.gameDir(mVersion);
        File modsDir = new File(gameDir, "mods");
        Set<String> managed = new HashSet<>();
        try {
            managed.addAll(Arrays.asList(Tools.read(new File(gameDir, ".swiftclient-managed")).split("\n")));
        } catch (IOException ignored) {}
        File[] files = modsDir.listFiles((d, name) -> name.endsWith(".jar") || name.endsWith(".jar.disabled"));
        if (files == null) return;
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (File file : files) {
            String name = file.getName();
            boolean enabled = name.endsWith(".jar");
            String jarName = enabled ? name : name.substring(0, name.length() - ".disabled".length());
            View row = inflater.inflate(R.layout.item_sc_mod, list, false);
            ((TextView) row.findViewById(R.id.sc_mod_name)).setText(prettyModName(jarName));
            TextView kind = row.findViewById(R.id.sc_mod_kind);
            boolean core = jarName.startsWith("swiftclient-") || jarName.startsWith("fabric-api-");
            kind.setText(core ? R.string.sc_mod_core : managed.contains(jarName) ? R.string.sc_mod_perf : R.string.sc_mod_yours);
            Switch toggle = row.findViewById(R.id.sc_mod_toggle);
            toggle.setChecked(enabled);
            toggle.setOnCheckedChangeListener((b, on) -> {
                File target = new File(modsDir, on ? jarName : jarName + ".disabled");
                File current = new File(modsDir, on ? jarName + ".disabled" : jarName);
                //noinspection ResultOfMethodCallIgnored
                current.renameTo(target);
                refreshVersionCard();
            });
            if (!core && !managed.contains(jarName)) {
                row.setOnLongClickListener(v -> {
                    new AlertDialog.Builder(requireContext())
                            .setTitle(prettyModName(jarName))
                            .setMessage(R.string.global_delete)
                            .setPositiveButton(R.string.global_delete, (d, w) -> {
                                //noinspection ResultOfMethodCallIgnored
                                file.delete();
                                buildModList();
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                    return true;
                });
            }
            list.addView(row);
        }
    }

    private static String prettyModName(String jar) {
        String base = jar.replaceAll("\\.jar$", "");
        // "lithium-fabric-0.15.1+mc1.21.11" -> "Lithium"
        int cut = base.length();
        for (int i = 0; i < base.length() - 1; i++) {
            if (base.charAt(i) == '-' && Character.isDigit(base.charAt(i + 1))) { cut = i; break; }
        }
        String name = base.substring(0, cut).replace("-fabric", "").replace("_", " ").replace("-", " ");
        String known = MOD_NAMES.get(name.toLowerCase());
        if (known != null) return known;
        return name.isEmpty() ? base : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private void importMod(@Nullable Uri uri) {
        if (uri == null || mVersion == null) return;
        Context context = requireContext();
        String name = "mod.jar";
        try (Cursor c = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        }
        if (!name.toLowerCase().endsWith(".jar")) {
            Toast.makeText(context, R.string.sc_add_mod, Toast.LENGTH_SHORT).show();
            return;
        }
        File target = new File(new File(SwiftClientInstaller.gameDir(mVersion), "mods"), name.replace("/", "_"));
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(target)) {
            if (in == null) throw new IOException("Could not open " + name);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            Toast.makeText(context, getString(R.string.sc_mod_added, name), Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Tools.showError(context, e);
        }
        buildModList();
        refreshVersionCard();
    }

    // ---------------------------------------------------------------- cloaks

    private void loadCloaks() {
        LinearLayout list = requireView().findViewById(R.id.sc_cloak_list);
        TextView status = requireView().findViewById(R.id.sc_cloak_status);
        MinecraftAccount account = currentAccount();
        boolean canPick = account != null && account.isMicrosoft && !account.isLocal();
        status.setText(canPick ? "" : getString(R.string.sc_cloak_sign_in));
        status.setVisibility(canPick ? View.GONE : View.VISIBLE);
        if (list.getChildCount() == 0) {
            TextView loading = new TextView(requireContext());
            loading.setText(R.string.sc_loading);
            loading.setTextColor(ContextCompat.getColor(requireContext(), R.color.sc_muted));
            list.addView(loading);
        }
        PojavApplication.sExecutorService.execute(() -> {
            try {
                SwiftNet.CapeCatalog catalog = SwiftNet.capes(canPick ? account.profileId : null);
                List<Bitmap> images = new ArrayList<>();
                for (SwiftNet.Cape cape : catalog.capes) images.add(capeFront(cape.imageUrl));
                runOnUiThread(() -> {
                    if (isAdded()) showCloaks(list, catalog, images, canPick ? account : null);
                });
            } catch (IOException e) {
                runOnUiThread(() -> {
                    if (!isAdded()) return;
                    list.removeAllViews();
                    status.setVisibility(View.VISIBLE);
                    status.setText(R.string.sc_net_error);
                });
            }
        });
    }

    private void showCloaks(LinearLayout list, SwiftNet.CapeCatalog catalog, List<Bitmap> images, @Nullable MinecraftAccount account) {
        list.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        List<View> cards = new ArrayList<>();
        cards.add(cloakCard(inflater, list, null, getString(R.string.sc_cloak_none), "", true, catalog.selected == null, account));
        for (int i = 0; i < catalog.capes.size(); i++) {
            SwiftNet.Cape c = catalog.capes.get(i);
            cards.add(cloakCard(inflater, list, images.get(i), c.name, c.description, c.owned, c.id.equals(catalog.selected), account, c.id));
        }
        // Two columns
        for (int i = 0; i < cards.size(); i += 2) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowParams.bottomMargin = dp(10);
            row.setLayoutParams(rowParams);
            for (int j = i; j < i + 2; j++) {
                View card = j < cards.size() ? cards.get(j) : new View(requireContext());
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if (j == i + 1) p.leftMargin = dp(10);
                card.setLayoutParams(p);
                row.addView(card);
            }
            list.addView(row);
        }
    }

    private View cloakCard(LayoutInflater inflater, ViewGroup parent, @Nullable Bitmap image, String name, String description,
                           boolean owned, boolean selected, @Nullable MinecraftAccount account, String... capeId) {
        View card = inflater.inflate(R.layout.item_sc_cloak, parent, false);
        ImageView img = card.findViewById(R.id.sc_cloak_image);
        if (image != null) {
            BitmapDrawable d = new BitmapDrawable(getResources(), image);
            d.setFilterBitmap(false); // keep the pixels crisp
            img.setImageDrawable(d);
        } else img.setImageDrawable(null);
        ((TextView) card.findViewById(R.id.sc_cloak_name)).setText(name);
        TextView state = card.findViewById(R.id.sc_cloak_state);
        if (selected) state.setText(R.string.sc_cloak_wearing);
        else if (!owned) state.setText(R.string.sc_cloak_locked);
        else state.setText(description);
        if (selected) card.setBackgroundResource(R.drawable.sc_card_hero);
        card.setAlpha(owned ? 1f : 0.45f);
        String id = capeId.length > 0 ? capeId[0] : null;
        card.setOnClickListener(v -> {
            if (account == null) {
                Toast.makeText(requireContext(), R.string.sc_cloak_sign_in, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!owned || selected) return;
            Context context = requireContext().getApplicationContext();
            Toast.makeText(context, R.string.sc_loading, Toast.LENGTH_SHORT).show();
            PojavApplication.sExecutorService.execute(() -> {
                try {
                    SwiftNet.selectCape(account, id);
                    runOnUiThread(() -> {
                        Toast.makeText(context, R.string.sc_cloak_set, Toast.LENGTH_LONG).show();
                        if (isAdded()) loadCloaks();
                    });
                } catch (IOException e) {
                    runOnUiThread(() -> Toast.makeText(context, e.getMessage(), Toast.LENGTH_LONG).show());
                }
            });
        });
        return card;
    }

    /** The front face of a 64x32 cape texture (10x16 at 1,1), or null. */
    @Nullable
    private static Bitmap capeFront(String url) {
        try {
            byte[] png = SwiftNet.getBytes(url);
            Bitmap full = BitmapFactory.decodeByteArray(png, 0, png.length);
            if (full == null) return null;
            int scale = Math.max(1, full.getWidth() / 64);
            return Bitmap.createBitmap(full, scale, scale, 10 * scale, 16 * scale);
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private MinecraftAccount currentAccount() {
        try {
            return PojavProfile.getCurrentProfileContent(requireContext(), null);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- misc

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private File getCurrentProfileDirectory() {
        String currentProfile = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, null);
        if(!Tools.isValidString(currentProfile)) return new File(Tools.DIR_GAME_NEW);
        LauncherProfiles.load();
        MinecraftProfile profileObject = LauncherProfiles.mainProfileJson.profiles.get(currentProfile);
        if(profileObject == null) return new File(Tools.DIR_GAME_NEW);
        return Tools.getGameDirPath(profileObject);
    }

    private void selectCurrentProfile() {
        mVersionSpinner.reloadProfiles();
        String current = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, "");
        int index = mVersionSpinner.getProfileAdapter().resolveProfileIndex(current);
        if (index >= 0) mVersionSpinner.setProfileSelection(index);
    }

    @Override
    public void onResume() {
        super.onResume();
        selectCurrentProfile();
        if (sVersions != null) refreshVersionCard();
        LauncherPreferences.DEFAULT_PREF.registerOnSharedPreferenceChangeListener(mProfileListener);
    }

    @Override
    public void onPause() {
        LauncherPreferences.DEFAULT_PREF.unregisterOnSharedPreferenceChangeListener(mProfileListener);
        super.onPause();
    }

    private void runInstallerWithConfirmation(boolean isCustomArgs) {
        if (ProgressKeeper.getTaskCount() == 0)
            Tools.installMod(requireActivity(), isCustomArgs);
        else
            Toast.makeText(requireContext(), R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
    }
}
