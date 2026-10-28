package net.kdt.pojavlaunch.fragments;

import static net.kdt.pojavlaunch.Tools.dialogOnUiThread;
import static net.kdt.pojavlaunch.Tools.hasMods;
import static net.kdt.pojavlaunch.Tools.hasNoOnlineProfileDialog;
import static net.kdt.pojavlaunch.Tools.hasOnlineProfile;
import static net.kdt.pojavlaunch.Tools.openPath;
import static net.kdt.pojavlaunch.Tools.runOnUiThread;
import static net.kdt.pojavlaunch.Tools.shareLog;

import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.kdt.mcgui.mcVersionSpinner;

import net.kdt.pojavlaunch.CustomControlsActivity;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.modloaders.LWJGL3ifyUtils;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.swiftclient.SwiftClientInstaller;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.util.List;
import java.util.Map;

public class MainMenuFragment extends Fragment {
    public static final String TAG = "MainMenuFragment";

    private mcVersionSpinner mVersionSpinner;
    /** Selects a profile that was made current elsewhere, such as by the SwiftClient installer. */
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener mProfileListener = (prefs, key) -> {
        if (!LauncherPreferences.PREF_KEY_CURRENT_PROFILE.equals(key)) return;
        runOnUiThread(() -> {
            if (!isResumed()) return;
            mVersionSpinner.reloadProfiles();
            int index = mVersionSpinner.getProfileAdapter().resolveProfileIndex(prefs.getString(key, ""));
            if (index >= 0) mVersionSpinner.setProfileSelection(index);
        });
    };

    public MainMenuFragment(){
        super(R.layout.fragment_launcher);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Button mNewsButton = view.findViewById(R.id.news_button);
        Button mDiscordButton = view.findViewById(R.id.discord_button);
        Button mCustomControlButton = view.findViewById(R.id.custom_control_button);
        Button mSwiftClientButton = view.findViewById(R.id.swiftclient_button);
        Button mInstallJarButton = view.findViewById(R.id.install_jar_button);
        Button mShareLogsButton = view.findViewById(R.id.share_logs_button);
        Button mOpenDirectoryButton = view.findViewById(R.id.open_files_button);

        ImageButton mEditProfileButton = view.findViewById(R.id.edit_profile_button);
        Button mPlayButton = view.findViewById(R.id.play_button);
        mVersionSpinner = view.findViewById(R.id.mc_version_spinner);

        mNewsButton.setOnClickListener(v -> Tools.openURL(requireActivity(), Tools.URL_HOME));
        mDiscordButton.setOnClickListener(v -> Tools.openURL(requireActivity(), getString(R.string.discord_invite)));
        mSwiftClientButton.setOnClickListener(v -> pickSwiftClientVersion());
        mCustomControlButton.setOnClickListener(v -> startActivity(new Intent(requireContext(), CustomControlsActivity.class)));
        if (hasOnlineProfile()) {
            mInstallJarButton.setOnClickListener(v -> runInstallerWithConfirmation(false));
            mInstallJarButton.setOnLongClickListener(v -> {
                runInstallerWithConfirmation(true);
                return true;
            });
        } else mInstallJarButton.setOnClickListener(v -> hasNoOnlineProfileDialog(requireActivity()));
        mEditProfileButton.setOnClickListener(v -> mVersionSpinner.openProfileEditor(requireActivity()));

        mPlayButton.setOnClickListener(v -> {
            if (Tools.hasMods("sodium") && !(LauncherPreferences.DEFAULT_PREF.getBoolean("sodium_override", false))) {
                AlertDialog sodiumWarningDialog = new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.sodium_warning_title)
                        .setMessage(R.string.sodium_warning_message)
                        .setNeutralButton(R.string.delete_sodium, (d,w)-> {
                            Tools.deleteSodiumMods();
                            ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true);
                        })
                        .create();
                sodiumWarningDialog.show();
            } else ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true);
        });

        mShareLogsButton.setOnClickListener((v) -> shareLog(requireContext()));

        mOpenDirectoryButton.setOnClickListener((v)-> {
            if (Tools.isDemoProfile(v.getContext())){ // Say a different message when on demo profile since they might see the hidden demo folder
                hasNoOnlineProfileDialog(getActivity(), getString(R.string.demo_unsupported), getString(R.string.change_account));
            } else if (!hasOnlineProfile()) { // Otherwise display the generic pop-up to log in
                hasNoOnlineProfileDialog(requireActivity());
            } else openPath(v.getContext(), getCurrentProfileDirectory(), false);

        });


        mNewsButton.setOnLongClickListener((v)->{
            Tools.swapFragment(requireActivity(), GamepadMapperFragment.class, GamepadMapperFragment.TAG, null);
            return true;
        });
    }

    private File getCurrentProfileDirectory() {
        String currentProfile = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, null);
        if(!Tools.isValidString(currentProfile)) return new File(Tools.DIR_GAME_NEW);
        LauncherProfiles.load();
        MinecraftProfile profileObject = LauncherProfiles.mainProfileJson.profiles.get(currentProfile);
        if(profileObject == null) return new File(Tools.DIR_GAME_NEW);
        return Tools.getGameDirPath(profileObject);
    }

    @Override
    public void onResume() {
        super.onResume();
        mVersionSpinner.reloadProfiles();
        String current = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, "");
        int index = mVersionSpinner.getProfileAdapter().resolveProfileIndex(current);
        if (index >= 0) mVersionSpinner.setProfileSelection(index);
        LauncherPreferences.DEFAULT_PREF.registerOnSharedPreferenceChangeListener(mProfileListener);
    }

    @Override
    public void onPause() {
        LauncherPreferences.DEFAULT_PREF.unregisterOnSharedPreferenceChangeListener(mProfileListener);
        super.onPause();
    }

    /** Lists the Minecraft versions the latest SwiftClient release has a mod for, then installs the chosen one. */
    private void pickSwiftClientVersion() {
        if (ProgressKeeper.getTaskCount() != 0) {
            Toast.makeText(requireContext(), R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(requireContext(), R.string.swiftclient_loading, Toast.LENGTH_SHORT).show();
        PojavApplication.sExecutorService.execute(() -> {
            try {
                Map<String, String> versions = SwiftClientInstaller.fetchVersions();
                runOnUiThread(() -> {
                    if (!isAdded()) return;
                    String[] ids = versions.keySet().toArray(new String[0]);
                    String[] labels = new String[ids.length];
                    for (int i = 0; i < ids.length; i++) {
                        // Minecraft 26.x runs on Java 25; 1.21.x on Java 21.
                        labels[i] = ids[i].startsWith("1.") ? "Minecraft " + ids[i]
                                : "Minecraft " + ids[i] + "  · " + getString(R.string.swiftclient_java25);
                    }
                    new AlertDialog.Builder(requireContext())
                            .setTitle(R.string.swiftclient_pick_version)
                            .setItems(labels, (d, which) -> installSwiftClient(ids[which], versions.get(ids[which])))
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                });
            } catch (Exception e) {
                Tools.showErrorRemote(requireContext(), R.string.swiftclient_failed, e);
            }
        });
    }

    private void installSwiftClient(String mcVersion, String modUrl) {
        android.content.Context context = requireContext().getApplicationContext();
        PojavApplication.sExecutorService.execute(() -> {
            try {
                String profileName = SwiftClientInstaller.install(context, mcVersion, modUrl);
                // The visible menu (possibly a newer fragment than this one) follows through mProfileListener.
                runOnUiThread(() -> Toast.makeText(context, context.getString(R.string.swiftclient_done, profileName), Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                Tools.showErrorRemote(context, R.string.swiftclient_failed, e);
            }
        });
    }

    private void runInstallerWithConfirmation(boolean isCustomArgs) {
        if (ProgressKeeper.getTaskCount() == 0)
            Tools.installMod(requireActivity(), isCustomArgs);
        else
            Toast.makeText(requireContext(), R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
    }
}
