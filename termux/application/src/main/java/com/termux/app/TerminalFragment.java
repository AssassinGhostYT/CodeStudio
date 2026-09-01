package com.termux.app;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.TypedValue;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.autofill.AutofillManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.viewpager.widget.ViewPager;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.button.MaterialButton;
import com.termux.R;
import com.termux.app.activities.HelpActivity;
import com.termux.app.api.file.FileReceiverActivity;
import com.termux.app.terminal.TermuxActivityRootView;
import com.termux.app.terminal.TermuxSessionsListViewController;
import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.app.terminal.io.TerminalToolbarViewPager;
import com.termux.app.terminal.io.TermuxTerminalExtraKeys;
import com.termux.shared.activities.ReportActivity;
import com.termux.shared.activity.ActivityUtils;
import com.termux.shared.android.PermissionUtils;
import com.termux.shared.data.DataUtils;
import com.termux.shared.data.IntentUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.extrakeys.ExtraKeysConstants;
import com.termux.shared.termux.extrakeys.ExtraKeysInfo;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.shared.termux.interact.TextInputDialogUtils;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.termux.settings.properties.TermuxSharedProperties;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.theme.TermuxThemeUtils;
import com.termux.shared.theme.NightMode;
import com.termux.shared.view.ViewUtils;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;
import com.tom.rv2ide.projects.internal.ProjectManagerImpl;
import java.io.File;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import org.json.JSONException;

/**
 * A reusable terminal emulator fragment that can be embedded in any activity.
 */
public class TerminalFragment extends Fragment implements ServiceConnection {

    protected TermuxService mTermuxService;
    protected TerminalView mTerminalView;
    protected TermuxTerminalViewClient mTermuxTerminalViewClient;
    protected TermuxTerminalSessionActivityClient mTermuxTerminalSessionActivityClient;
    protected TermuxAppSharedPreferences mPreferences;
    protected TermuxAppSharedProperties mProperties;
    protected TermuxActivityRootView mTermuxActivityRootView;
    protected View mTermuxActivityBottomSpaceView;
    protected ExtraKeysView mExtraKeysView;
    protected TabLayout mSessionTabLayout;
    protected com.termux.shared.termux.terminal.io.TerminalExtraKeys mTermuxTerminalExtraKeys;
    protected ExtraKeysInfo mExtraKeysInfo;
    protected TermuxSessionsListViewController mTermuxSessionListViewController;
    protected Toast mLastToast;
    protected boolean mIsVisible;
    protected boolean mIsInvalidState;
    protected int mNavBarHeight;
    protected float mTerminalToolbarDefaultHeight;
    protected final android.os.Handler mHandler = new android.os.Handler();

    protected static final int CONTEXT_MENU_SELECT_URL_ID = 0;
    protected static final int CONTEXT_MENU_SHARE_TRANSCRIPT_ID = 1;
    protected static final int CONTEXT_MENU_SHARE_SELECTED_TEXT = 10;
    protected static final int CONTEXT_MENU_AUTOFILL_ID = 2;
    protected static final int CONTEXT_MENU_RESET_TERMINAL_ID = 3;
    protected static final int CONTEXT_MENU_KILL_PROCESS_ID = 4;
    protected static final int CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON = 6;
    protected static final int CONTEXT_MENU_HELP_ID = 7;
    protected static final int CONTEXT_MENU_REPORT_ID = 9;

    protected static final String ARG_TERMINAL_TOOLBAR_TEXT_INPUT = "terminal_toolbar_text_input";
    protected static final String LOG_TAG = "TerminalFragment";

    public interface TerminalFragmentListener {
        void onTerminalInitialized();
        void onTerminalError(String error);
        void onSessionChanged(TerminalSession session);
    }

    private TerminalFragmentListener mListener;

    public TerminalFragment() {
    }

    public static TerminalFragment newInstance() {
        return new TerminalFragment();
    }

    public void setTerminalFragmentListener(TerminalFragmentListener listener) {
        mListener = listener;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        Logger.logDebug(LOG_TAG, "onAttach");
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Logger.logDebug(LOG_TAG, "onCreate");

        setHasOptionsMenu(true);

        mProperties = TermuxAppSharedProperties.getProperties();
        reloadProperties();

        mPreferences = TermuxAppSharedPreferences.build(requireContext(), true);
        if (mPreferences == null) {
            mIsInvalidState = true;
            if (mListener != null) {
                mListener.onTerminalError("Failed to load Termux preferences");
            }
            return;
        }
    }

    @SuppressLint("InflateParams")
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        Logger.logDebug(LOG_TAG, "onCreateView");

        if (mIsInvalidState) {
            return null;
        }

        View rootView = inflater.inflate(R.layout.frag_terminal, container, false);

        mTermuxActivityRootView = rootView.findViewById(R.id.activity_termux_root_view);
        mTermuxActivityBottomSpaceView = rootView.findViewById(R.id.activity_termux_bottom_space_view);
        mTerminalView = rootView.findViewById(R.id.terminal_view);

        setMargins(rootView);

        return rootView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Logger.logDebug(LOG_TAG, "onViewCreated");

        if (mIsInvalidState) {
            return;
        }

        setTerminalViewClient();
        registerForContextMenu(mTerminalView);
        addSessionTabBar(view);
        bindToTermuxService();
    }

    protected void addSessionTabBar(View rootView) {
        ViewGroup rootLayout = rootView.findViewById(R.id.activity_termux_root_view);
        if (rootLayout == null) return;

        Context context = requireContext();
        
        LinearLayout tabBarLayout = new LinearLayout(context);
        tabBarLayout.setOrientation(LinearLayout.HORIZONTAL);
        tabBarLayout.setId(View.generateViewId());
        
        RelativeLayout.LayoutParams tabBarParams = new RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        );
        tabBarParams.addRule(RelativeLayout.BELOW, R.id.terminal_toolbar_view_pager);
        tabBarLayout.setLayoutParams(tabBarParams);
        
        mSessionTabLayout = new TabLayout(context);
        mSessionTabLayout.setTabMode(TabLayout.MODE_SCROLLABLE);
        LinearLayout.LayoutParams tabLayoutParams = new LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1.0f
        );
        mSessionTabLayout.setLayoutParams(tabLayoutParams);
        
        tabBarLayout.addView(mSessionTabLayout);
        
        int padding = (int) (8 * context.getResources().getDisplayMetrics().density);
        com.google.android.material.button.MaterialButton addButton = 
            new com.google.android.material.button.MaterialButton(context, null, 
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        addButton.setText("+");
        addButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        addButton.setCornerRadius((int) (28 * context.getResources().getDisplayMetrics().density));
        
        TypedValue btnTypedValue = new TypedValue();
        context.getTheme().resolveAttribute(com.google.android.material.R.attr.colorPrimaryContainer, btnTypedValue, true);
        addButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(btnTypedValue.data));
        context.getTheme().resolveAttribute(com.google.android.material.R.attr.colorOnPrimaryContainer, btnTypedValue, true);
        addButton.setTextColor(btnTypedValue.data);
        addButton.setStrokeWidth(0);
        
        LinearLayout.LayoutParams addButtonParams = new LinearLayout.LayoutParams(
            (int) (56 * context.getResources().getDisplayMetrics().density),
            (int) (56 * context.getResources().getDisplayMetrics().density)
        );
        addButtonParams.setMargins(padding, 0, 0, 0);
        addButton.setLayoutParams(addButtonParams);
        
        addButton.setOnClickListener(v -> {
            if (mTermuxTerminalSessionActivityClient != null) {
                mTermuxTerminalSessionActivityClient.addNewSession(false, null);
                updateSessionTabs();
                
                mHandler.postDelayed(() -> {
                    TerminalSession currentSession = getCurrentSession();
                    if (currentSession != null) {
                        navigateToProjectDirectory(currentSession);
                    }
                }, 200);
            }
        });
        
        tabBarLayout.addView(addButton);
        
        mSessionTabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                int position = tab.getPosition();
                if (mTermuxService != null && position < mTermuxService.getTermuxSessions().size()) {
                    TerminalSession session = mTermuxService.getTermuxSessions().get(position).getTerminalSession();
                    if (mTerminalView != null && session != null) {
                        mTerminalView.attachSession(session);
                        if (mListener != null) {
                            mListener.onSessionChanged(session);
                        }
                    }
                }
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });
        
        rootLayout.addView(tabBarLayout, 0);
        
        View terminalView = rootView.findViewById(R.id.terminal_view);
        if (terminalView != null) {
            ViewGroup.LayoutParams params = terminalView.getLayoutParams();
            if (params instanceof RelativeLayout.LayoutParams) {
                RelativeLayout.LayoutParams terminalParams = (RelativeLayout.LayoutParams) params;
                terminalParams.addRule(RelativeLayout.BELOW, tabBarLayout.getId());
                terminalView.setLayoutParams(terminalParams);
            }
        }
    }

    protected void updateSessionTabs() {
        if (mSessionTabLayout == null || mTermuxService == null) return;
        
        mSessionTabLayout.removeAllTabs();
        
        java.util.List<TermuxSession> sessions = mTermuxService.getTermuxSessions();
        TerminalSession currentSession = getCurrentSession();
        
        for (int i = 0; i < sessions.size(); i++) {
            TermuxSession termuxSession = sessions.get(i);
            TerminalSession session = termuxSession.getTerminalSession();
            
            String sessionName = termuxSession.getExecutionCommand().shellName;
            if (sessionName == null || sessionName.isEmpty()) {
                sessionName = "Session " + (i + 1);
            }
            
            TabLayout.Tab tab = mSessionTabLayout.newTab();
            tab.setText(sessionName);
            mSessionTabLayout.addTab(tab);
            
            if (currentSession != null && session.equals(currentSession)) {
                mSessionTabLayout.selectTab(tab);
            }
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        Logger.logDebug(LOG_TAG, "onStart");

        if (mIsInvalidState) return;

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onStart();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onStart();
    }

    @Override
    public void onResume() {
        super.onResume();
        Logger.logDebug(LOG_TAG, "onResume");

        if (mIsInvalidState) return;

        mIsVisible = true;

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onResume();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onResume();

        if (mTerminalView != null)
            mTerminalView.onScreenUpdated();
    }

    @Override
    public void onStop() {
        super.onStop();
        Logger.logDebug(LOG_TAG, "onStop");

        if (mIsInvalidState) return;

        mIsVisible = false;

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onStop();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onStop();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        Logger.logDebug(LOG_TAG, "onDestroyView");

        if (mTerminalView != null)
            unregisterForContextMenu(mTerminalView);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Logger.logDebug(LOG_TAG, "onDestroy");

        if (mIsInvalidState) return;

        // Clean up handler callbacks
        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null);
        }

        unbindFromTermuxService();
    }

    @Override
    public void onDetach() {
        super.onDetach();
        Logger.logDebug(LOG_TAG, "onDetach");
        mListener = null;
    }

    protected void bindToTermuxService() {
        Intent serviceIntent = new Intent(requireContext(), TermuxService.class);
        requireContext().startService(serviceIntent);

        if (!requireContext().bindService(serviceIntent, this, 0)) {
            Logger.logError(LOG_TAG, "Failed to bind to TermuxService");
            if (mListener != null) {
                mListener.onTerminalError("Failed to bind to TermuxService");
            }
        }
    }

    protected void unbindFromTermuxService() {
        if (mTermuxService != null) {
            try {
                requireContext().unbindService(this);
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Error unbinding from service", e);
            }
            mTermuxService = null;
        }
    }

    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {
        Logger.logDebug(LOG_TAG, "onServiceConnected");

        mTermuxService = ((TermuxService.LocalBinder) service).service;

        setTermuxSessionClient();

        if (mTermuxService.getTermuxSessions().isEmpty()) {
            if (mTermuxTerminalSessionActivityClient != null) {
                mTermuxTerminalSessionActivityClient.addNewSession(false, null);
            }
        }

        if (mTerminalView != null && !mTermuxService.getTermuxSessions().isEmpty()) {
            TermuxSession termuxSession = mTermuxService.getTermuxSessions().get(0);
            mTerminalView.attachSession(termuxSession.getTerminalSession());

            // Automatically navigate to project directory
            navigateToProjectDirectory(termuxSession.getTerminalSession());

            if (mListener != null) {
                mListener.onSessionChanged(termuxSession.getTerminalSession());
            }
        }

        setTerminalToolbarView();
        
        updateSessionTabs();

        if (mListener != null) {
            mListener.onTerminalInitialized();
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName componentName) {
        Logger.logDebug(LOG_TAG, "onServiceDisconnected");
        mTermuxService = null;
    }

    /**
     * Navigate terminal to the current project directory
     */
    protected void navigateToProjectDirectory(TerminalSession session) {
        if (session == null || !session.isRunning()) {
            return;
        }

        try {
            File projectDir = ProjectManagerImpl.getInstance().getProjectDir();
            if (projectDir != null && projectDir.exists()) {
                String cdCommand = "cd " + projectDir.getAbsolutePath() + "\n";
                session.write(cdCommand);
                Logger.logDebug(LOG_TAG, "Navigated to project directory: " + projectDir.getAbsolutePath());
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to navigate to project directory: " + e.getMessage());
        }
    }

    protected void setTerminalViewClient() {
        if (mTerminalView != null) {
            // Create a standalone terminal view client that doesn't require TermuxActivity
            mTermuxTerminalViewClient = new StandaloneTerminalViewClient(this);
            mTerminalView.setTerminalViewClient(mTermuxTerminalViewClient);
        }
    }

    protected void setTermuxSessionClient() {
        if (mTermuxService != null) {
            mTermuxTerminalSessionActivityClient = new StandaloneTerminalSessionClient(this);
            mTermuxService.setTermuxTerminalSessionClient(mTermuxTerminalSessionActivityClient);
            
            if (mTermuxTerminalViewClient != null && mTermuxTerminalViewClient instanceof StandaloneTerminalViewClient) {
                ((StandaloneTerminalViewClient)mTermuxTerminalViewClient).onCreate();
            }
        }
    }

    protected void setTerminalToolbarView() {
        if (getView() == null) return;

        final ViewPager terminalToolbarViewPager = getView().findViewById(R.id.terminal_toolbar_view_pager);
        if (terminalToolbarViewPager == null) return;

        if (mPreferences.shouldShowTerminalToolbar()) {
            terminalToolbarViewPager.setVisibility(View.VISIBLE);
        } else {
            terminalToolbarViewPager.setVisibility(View.GONE);
        }

        ViewGroup.LayoutParams layoutParams = terminalToolbarViewPager.getLayoutParams();
        mTerminalToolbarDefaultHeight = layoutParams.height;

        setTerminalToolbarHeight();

        String savedTextInput = null;
        if (getArguments() != null)
            savedTextInput = getArguments().getString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT);

        terminalToolbarViewPager.setAdapter(new StandaloneToolbarAdapter(
            this, savedTextInput));
        terminalToolbarViewPager.addOnPageChangeListener(new StandaloneToolbarPageListener(
            this, terminalToolbarViewPager));

        setTerminalToolbarExtraKeys();
    }

    protected void setTerminalToolbarExtraKeys() {
        if (getView() == null) return;

        // ExtraKeysView is set by the PageAdapter when the ViewPager inflates the extra keys page
        // We get it from the fragment after the adapter has been set
        StandaloneTerminalExtraKeys extraKeys = new StandaloneTerminalExtraKeys(this, mTerminalView,
            mTermuxTerminalViewClient, mTermuxTerminalSessionActivityClient);
        mTermuxTerminalExtraKeys = extraKeys;
        mExtraKeysInfo = extraKeys.getExtraKeysInfo();
    }

    protected void setMargins(View rootView) {
        RelativeLayout relativeLayout = rootView.findViewById(R.id.activity_termux_root_relative_layout);
        if (relativeLayout == null) return;

        // Since this is embedded in a bottom sheet fragment, we don't need action bar margins
        // Set margins to 0 to avoid creating a massive gap at the top
        ViewGroup.LayoutParams layoutParams = relativeLayout.getLayoutParams();
        if (layoutParams instanceof RelativeLayout.LayoutParams) {
            RelativeLayout.LayoutParams relativeLayoutParams = (RelativeLayout.LayoutParams) layoutParams;
            relativeLayoutParams.setMargins(0, 0, 0, 0);
            relativeLayout.setLayoutParams(relativeLayoutParams);
        } else if (layoutParams instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams marginLayoutParams = (ViewGroup.MarginLayoutParams) layoutParams;
            marginLayoutParams.setMargins(0, 0, 0, 0);
            relativeLayout.setLayoutParams(marginLayoutParams);
        }
    }

    protected void setTerminalToolbarHeight() {
        if (getView() == null) return;

        final ViewPager terminalToolbarViewPager = getView().findViewById(R.id.terminal_toolbar_view_pager);
        if (terminalToolbarViewPager == null) return;

        ViewGroup.LayoutParams layoutParams = terminalToolbarViewPager.getLayoutParams();
        layoutParams.height = Math.round(mTerminalToolbarDefaultHeight *
            (mProperties.getTerminalToolbarHeightScaleFactor()));
        terminalToolbarViewPager.setLayoutParams(layoutParams);
    }

    protected void reloadProperties() {
        mProperties.loadTermuxPropertiesFromDisk();
    }

    @Override
    public void onCreateContextMenu(@NonNull ContextMenu menu, @NonNull View v,
                                    ContextMenuInfo menuInfo) {
        if (v == mTerminalView) {
            menu.add(Menu.NONE, CONTEXT_MENU_SELECT_URL_ID, Menu.NONE, "Select URL");
            menu.add(Menu.NONE, CONTEXT_MENU_SHARE_TRANSCRIPT_ID, Menu.NONE, "Share transcript");
            menu.add(Menu.NONE, CONTEXT_MENU_SHARE_SELECTED_TEXT, Menu.NONE, "Share selected text");
            menu.add(Menu.NONE, CONTEXT_MENU_RESET_TERMINAL_ID, Menu.NONE, "Reset terminal");
            menu.add(Menu.NONE, CONTEXT_MENU_KILL_PROCESS_ID, Menu.NONE, "Kill process");
            menu.add(Menu.NONE, CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON, Menu.NONE, "Toggle keep screen on");
            menu.add(Menu.NONE, CONTEXT_MENU_HELP_ID, Menu.NONE, "Help");
            menu.add(Menu.NONE, CONTEXT_MENU_REPORT_ID, Menu.NONE, "Report issue");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_ID, Menu.NONE, "Autofill");
            }
        }
    }

    @Override
    public boolean onContextItemSelected(@NonNull MenuItem item) {
        TerminalSession session = getCurrentSession();

        switch (item.getItemId()) {
            case CONTEXT_MENU_SELECT_URL_ID:
                showUrlSelection();
                return true;
            case CONTEXT_MENU_SHARE_TRANSCRIPT_ID:
                shareTranscript();
                return true;
            case CONTEXT_MENU_SHARE_SELECTED_TEXT:
                shareSelectedText();
                return true;
            case CONTEXT_MENU_AUTOFILL_ID:
                requestAutoFill();
                return true;
            case CONTEXT_MENU_RESET_TERMINAL_ID:
                if (session != null) {
                    session.reset();
                    showToast("Terminal reset", true);
                }
                return true;
            case CONTEXT_MENU_KILL_PROCESS_ID:
                if (session != null) {
                    showKillSessionDialog(session);
                }
                return true;
            case CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON:
                toggleKeepScreenOn();
                return true;
            case CONTEXT_MENU_HELP_ID:
                ActivityUtils.startActivity(requireContext(), new Intent(requireContext(), HelpActivity.class));
                return true;
            case CONTEXT_MENU_REPORT_ID:
                showReportIssueActivity();
                return true;
            default:
                return super.onContextItemSelected(item);
        }
    }

    protected void showUrlSelection() {
        if (mTermuxTerminalViewClient != null) {
            mTermuxTerminalViewClient.showUrlSelection();
        }
    }

    protected void shareTranscript() {
        if (mTermuxTerminalViewClient != null) {
            mTermuxTerminalViewClient.shareSessionTranscript();
        }
    }

    protected void shareSelectedText() {
        if (mTermuxTerminalViewClient != null) {
            mTermuxTerminalViewClient.shareSelectedText();
        }
    }

    protected void requestAutoFill() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && mTerminalView != null) {
            AutofillManager autofillManager = requireContext().getSystemService(AutofillManager.class);
            if (autofillManager != null && autofillManager.isEnabled()) {
                autofillManager.requestAutofill(mTerminalView);
            }
        }
    }

    protected void showKillSessionDialog(TerminalSession session) {
        new AlertDialog.Builder(requireContext())
            .setTitle("Confirm")
            .setMessage("Kill this process?")
            .setPositiveButton(android.R.string.yes, (dialog, which) -> {
                session.finishIfRunning();
            })
            .setNegativeButton(android.R.string.no, null)
            .show();
    }

    protected void toggleKeepScreenOn() {
        if (requireActivity().getWindow() != null) {
            boolean keepScreenOn = (requireActivity().getWindow().getAttributes().flags &
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0;

            if (keepScreenOn) {
                requireActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                requireActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }

            showToast(keepScreenOn ? "Keep screen on disabled" : "Keep screen on enabled", true);
        }
    }

    protected void showReportIssueActivity() {
        if (mTermuxTerminalViewClient != null) {
            mTermuxTerminalViewClient.reportIssueFromTranscript();
        }
    }

    public void showToast(String message, boolean cancelPrevious) {
        if (cancelPrevious && mLastToast != null) {
            mLastToast.cancel();
        }

        mLastToast = Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT);
        mLastToast.setGravity(Gravity.TOP, 0, 0);
        mLastToast.show();
    }

    @Nullable
    public TermuxService getTermuxService() {
        return mTermuxService;
    }

    @Nullable
    public TerminalView getTerminalView() {
        return mTerminalView;
    }

    @Nullable
    public TerminalSession getCurrentSession() {
        if (mTerminalView != null)
            return mTerminalView.getCurrentSession();
        else
            return null;
    }

    @Nullable
    public TermuxAppSharedPreferences getPreferences() {
        return mPreferences;
    }

    @Nullable
    public TermuxAppSharedProperties getProperties() {
        return mProperties;
    }

    public boolean isFragmentVisible() {
        return mIsVisible;
    }

    public void createNewSession() {
        if (mTermuxTerminalSessionActivityClient != null) {
            mTermuxTerminalSessionActivityClient.addNewSession(false, null);
        }
    }

    public void switchToSession(int index) {
        if (mTermuxService != null && mTerminalView != null) {
            if (index >= 0 && index < mTermuxService.getTermuxSessions().size()) {
                TermuxSession termuxSession = mTermuxService.getTermuxSessions().get(index);
                mTerminalView.attachSession(termuxSession.getTerminalSession());

                if (mListener != null) {
                    mListener.onSessionChanged(termuxSession.getTerminalSession());
                }
            }
        }
    }

    public int getSessionCount() {
        if (mTermuxService != null) {
            return mTermuxService.getTermuxSessions().size();
        }
        return 0;
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
    }

    // ========== Standalone wrapper classes that don't require TermuxActivity ==========

    /**
     * Standalone terminal view client that works without TermuxActivity
     */
    private static class StandaloneTerminalViewClient extends TermuxTerminalViewClient {
        private final TerminalFragment mFragment;
        private boolean mVirtualControlKeyDown;
        private boolean mVirtualFnKeyDown;

        public StandaloneTerminalViewClient(TerminalFragment fragment) {
            super(createDummyActivity(fragment), null);
            mFragment = fragment;
        }

        private static TermuxActivity createDummyActivity(TerminalFragment fragment) {
            // We need a TermuxActivity instance but we can't create one
            // So we'll use null and override all methods that use it
            return null;
        }

        @Override
        public TermuxActivity getActivity() {
            // Return null - methods will be overridden to not use it
            return null;
        }

        @Override
        public void onCreate() {
            if (mFragment.mTerminalView != null && mFragment.mPreferences != null) {
                mFragment.mTerminalView.setTextSize(mFragment.mPreferences.getFontSize());
                mFragment.mTerminalView.setKeepScreenOn(mFragment.mPreferences.shouldKeepScreenOn());
            }
        }

        @Override
        public void onStart() {
            if (mFragment.mTerminalView != null && mFragment.mPreferences != null) {
                boolean isTerminalViewKeyLoggingEnabled = mFragment.mPreferences.isTerminalViewKeyLoggingEnabled();
                mFragment.mTerminalView.setIsTerminalViewKeyLoggingEnabled(isTerminalViewKeyLoggingEnabled);
            }
        }

        @Override
        public void onResume() {
            // Simplified version without keyboard showing
        }

        @Override
        public void onStop() {
            // Simplified version
        }

        @Override
        public void onEmulatorSet() {
            // Override to prevent NullPointerException when trying to access activity
            // Simplified version that doesn't try to set cursor blinker state through activity
            if (mFragment.mTerminalView != null && mFragment.mTerminalView.mEmulator != null) {
                // Just set basic emulator properties without accessing the activity
                mFragment.mTerminalView.setTerminalCursorBlinkerState(true, true);
            }
        }

        @Override
        public void setTerminalCursorBlinkerState(boolean start) {
            // Override to use the fragment's terminal view directly
            if (mFragment.mTerminalView != null) {
                if (start) {
                    // Set default blink rate and enable cursor blinker
                    if (mFragment.mProperties != null) {
                        int blinkRate = mFragment.mProperties.getTerminalCursorBlinkRate();
                        if (mFragment.mTerminalView.setTerminalCursorBlinkerRate(blinkRate)) {
                            mFragment.mTerminalView.setTerminalCursorBlinkerState(true, true);
                        }
                    } else {
                        mFragment.mTerminalView.setTerminalCursorBlinkerState(true, true);
                    }
                } else {
                    // Disable cursor blinker
                    mFragment.mTerminalView.setTerminalCursorBlinkerState(false, true);
                }
            }
        }

        @Override
        public boolean shouldBackButtonBeMappedToEscape() {
            return mFragment.mProperties != null && mFragment.mProperties.isBackKeyTheEscapeKey();
        }

        @Override
        public boolean shouldEnforceCharBasedInput() {
            return mFragment.mProperties != null && mFragment.mProperties.isEnforcingCharBasedInput();
        }

        @Override
        public boolean shouldUseCtrlSpaceWorkaround() {
            return mFragment.mProperties != null && mFragment.mProperties.isUsingCtrlSpaceWorkaround();
        }

        @Override
        public boolean isTerminalViewSelected() {
            // In standalone mode, terminal view is always selected
            return true;
        }

        @Override
        public void copyModeChanged(boolean copyMode) {
            // Simplified - no drawer to lock/unlock in standalone mode
        }

        @Override
        public void onToggleSoftKeyboardRequest() {
            // Simplified - skip keyboard toggle for now
        }

        @Override
        public void onSingleTapUp(MotionEvent e) {
            // Simplified version without URL clicking for now
            if (mFragment.mTerminalView != null && mFragment.mTerminalView.mEmulator != null) {
                TerminalEmulator term = mFragment.mTerminalView.mEmulator;
                
                // Only show keyboard if mouse tracking is not active and it's not from a mouse
                if (!term.isMouseTrackingActive() && !e.isFromSource(InputDevice.SOURCE_MOUSE)) {
                    // Show the soft keyboard
                    Context context = mFragment.requireContext();
                    android.view.inputmethod.InputMethodManager imm = 
                        (android.view.inputmethod.InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        mFragment.mTerminalView.requestFocus();
                        imm.showSoftInput(mFragment.mTerminalView, 0);
                    }
                }
            }
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession currentSession) {
            if (handleVirtualKeys(keyCode, e, true)) return true;

            if (keyCode == KeyEvent.KEYCODE_ENTER && !currentSession.isRunning()) {
                if (mFragment.mTermuxService != null) {
                    mFragment.mTermuxService.removeTermuxSession(currentSession);
                }
                return true;
            }
            // Simplified - skip keyboard shortcuts for now
            return false;
        }

        @Override
        public boolean onKeyUp(int keyCode, KeyEvent e) {
            // Simplified - just handle virtual keys
            return handleVirtualKeys(keyCode, e, false);
        }

        private boolean handleVirtualKeys(int keyCode, KeyEvent event, boolean down) {
            // Check if virtual volume keys are disabled
            if (mFragment.mProperties != null && mFragment.mProperties.areVirtualVolumeKeysDisabled()) {
                return false;
            }
            
            InputDevice inputDevice = event.getDevice();
            if (inputDevice != null && inputDevice.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
                // Do not steal dedicated buttons from a full external keyboard.
                return false;
            } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                mVirtualControlKeyDown = down;
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                mVirtualFnKeyDown = down;
                return true;
            }
            return false;
        }

        @Override
        public boolean readControlKey() {
            return readExtraKeysSpecialButton(com.termux.shared.termux.extrakeys.SpecialButton.CTRL) || mVirtualControlKeyDown;
        }

        @Override
        public boolean readAltKey() {
            return readExtraKeysSpecialButton(com.termux.shared.termux.extrakeys.SpecialButton.ALT);
        }

        @Override
        public boolean readShiftKey() {
            return readExtraKeysSpecialButton(com.termux.shared.termux.extrakeys.SpecialButton.SHIFT);
        }

        @Override
        public boolean readFnKey() {
            return readExtraKeysSpecialButton(com.termux.shared.termux.extrakeys.SpecialButton.FN) || mVirtualFnKeyDown;
        }

        public boolean readExtraKeysSpecialButton(com.termux.shared.termux.extrakeys.SpecialButton specialButton) {
            if (mFragment.mExtraKeysView == null) return false;
            Boolean state = mFragment.mExtraKeysView.readSpecialButton(specialButton, true);
            if (state == null) {
                Logger.logError(LOG_TAG, "Failed to read an unregistered " + specialButton + " special button value from extra keys.");
                return false;
            }
            return state;
        }

        @Override
        public void changeFontSize(boolean increase) {
            // Override to use fragment's preferences instead of activity
            if (mFragment.mPreferences != null && mFragment.mTerminalView != null) {
                mFragment.mPreferences.changeFontSize(increase);
                mFragment.mTerminalView.setTextSize(mFragment.mPreferences.getFontSize());
            }
        }
    }

    /**
     * Standalone terminal session client that works without TermuxActivity
     */
    private static class StandaloneTerminalSessionClient extends TermuxTerminalSessionActivityClient {
        private final TerminalFragment mFragment;

        public StandaloneTerminalSessionClient(TerminalFragment fragment) {
            super(createDummyActivity(fragment));
            mFragment = fragment;
        }

        private static TermuxActivity createDummyActivity(TerminalFragment fragment) {
            return null;
        }

        @Override
        public void onCreate() {
            // Simplified - skip font/color checks for now
        }

        @Override
        public void onStart() {
            if (mFragment.mTermuxService != null && mFragment.mTerminalView != null) {
                if (!mFragment.mTermuxService.getTermuxSessions().isEmpty()) {
                    TermuxSession session = mFragment.mTermuxService.getTermuxSessions().get(0);
                    mFragment.mTerminalView.attachSession(session.getTerminalSession());
                }
                mFragment.mTerminalView.onScreenUpdated();
            }
        }

        @Override
        public void onResume() {
            // Simplified
        }

        @Override
        public void onStop() {
            // Simplified
        }

        @Override
        public void addNewSession(boolean isFailSafe, String sessionName) {
            addNewSession(isFailSafe, sessionName, null);
        }

        @Override
        public void addNewSession(boolean isFailSafe, String sessionName, String workingDirectory) {
            // CRITICAL FIX: Check if service is connected, if not rebind first
            if (mFragment.mTermuxService == null) {
                Logger.logDebug(LOG_TAG, "Service null when adding new session, rebinding...");
                mFragment.bindToTermuxService();
                
                // Make final copy for lambda
                final String finalWorkingDirectory = workingDirectory;
                
                // Wait a bit for service to connect, then try again
                mFragment.mHandler.postDelayed(() -> {
                    if (mFragment.mTermuxService != null) {
                        addNewSession(isFailSafe, sessionName, finalWorkingDirectory);
                    } else {
                        Logger.logError(LOG_TAG, "Failed to rebind to service, cannot create session");
                        if (mFragment.getContext() != null) {
                            Toast.makeText(mFragment.getContext(), "Failed to connect to terminal service. Please restart the app.", Toast.LENGTH_LONG).show();
                        }
                    }
                }, 1000);
                return;
            }

            TerminalSession currentSession = mFragment.getCurrentSession();

            String finalWorkingDir = workingDirectory;
            if (finalWorkingDir == null) {
                if (currentSession == null) {
                    finalWorkingDir = mFragment.mProperties.getDefaultWorkingDirectory();
                } else {
                    String cwd = currentSession.getCwd();
                    finalWorkingDir = (cwd != null) ? cwd : mFragment.mProperties.getDefaultWorkingDirectory();
                }
            }

            TermuxSession newTermuxSession = mFragment.mTermuxService.createTermuxSession(
                null, null, null, finalWorkingDir, isFailSafe, sessionName);
            if (newTermuxSession == null) {
                Logger.logError(LOG_TAG, "Failed to create new TermuxSession");
                if (mFragment.getContext() != null) {
                    Toast.makeText(mFragment.getContext(), "Failed to create new session", Toast.LENGTH_SHORT).show();
                }
                return;
            }

            TerminalSession newTerminalSession = newTermuxSession.getTerminalSession();
            if (mFragment.mTerminalView != null) {
                mFragment.mTerminalView.attachSession(newTerminalSession);
            }

            if (mFragment.mListener != null) {
                mFragment.mListener.onSessionChanged(newTerminalSession);
            }
            
            mFragment.updateSessionTabs();
        }

        @Override
        public void onTextChanged(@NonNull TerminalSession changedSession) {
            if (!mFragment.mIsVisible) return;
            if (mFragment.getCurrentSession() == changedSession && mFragment.mTerminalView != null) {
                mFragment.mTerminalView.onScreenUpdated();
            }
        }

        @Override
        public void onTitleChanged(@NonNull TerminalSession updatedSession) {
            // Simplified - no toast
        }

        @Override
        public void onSessionFinished(@NonNull TerminalSession finishedSession) {
            if (mFragment.mTermuxService == null) return;
            
            int index = mFragment.mTermuxService.removeTermuxSession(finishedSession);
            int size = mFragment.mTermuxService.getTermuxSessionsSize();
            
            if (size == 0) {
                // No sessions left - detach terminal view but keep service connection alive
                if (mFragment.mTerminalView != null) {
                    mFragment.mTerminalView.attachSession(null);
                }
                mFragment.updateSessionTabs();
                
                mFragment.mHandler.postDelayed(() -> {
                    if (mFragment.mTermuxService == null && !mFragment.isDetached() && mFragment.getContext() != null) {
                        Logger.logDebug(LOG_TAG, "Service connection lost after closing all sessions, rebinding...");
                        mFragment.bindToTermuxService();
                    }
                }, 500);
                
                return;
            }
            
            if (index >= size) {
                index = size - 1;
            }
            
            TermuxSession termuxSession = mFragment.mTermuxService.getTermuxSession(index);
            if (termuxSession != null && mFragment.mTerminalView != null) {
                mFragment.mTerminalView.attachSession(termuxSession.getTerminalSession());
            }
            
            mFragment.updateSessionTabs();
        }

        @Override
        public void termuxSessionListNotifyUpdated() {
            // Override to prevent NullPointerException when mActivity is null
            // In standalone mode, we don't have a session list view to update
            // The fragment handles session changes through its listener
        }

        @Override
        public Integer getTerminalCursorStyle() {
            // Return default cursor style since we don't have access to properties through activity
            if (mFragment.mProperties != null) {
                return mFragment.mProperties.getTerminalCursorStyle();
            }
            return null;
        }

        @Override
        public void onBell(@NonNull TerminalSession session) {
            // Simplified - ignore bell in standalone mode
            // Could implement vibration here if needed
        }

        @Override
        public void onColorsChanged(@NonNull TerminalSession changedSession) {
            // Simplified - skip background color update
        }

        @Override
        public void onTerminalCursorStateChange(boolean enabled) {
            // Simplified - skip cursor blinking control
            // In standalone mode, cursor blinking is handled by default terminal view behavior
        }

        @Override
        public void setTerminalShellPid(@NonNull TerminalSession terminalSession, int pid) {
            if (mFragment.mTermuxService == null) return;
            
            TermuxSession termuxSession = mFragment.mTermuxService.getTermuxSessionForTerminalSession(terminalSession);
            if (termuxSession != null)
                termuxSession.getExecutionCommand().mPid = pid;
        }

        @Override
        public void onPasteTextFromClipboard(@Nullable TerminalSession session) {
            // Simplified - skip clipboard paste for now
            // This would require clipboard access in the fragment
        }

        @Override
        public void onCopyTextToClipboard(@NonNull TerminalSession session, String text) {
            // Copy text to clipboard using the fragment's context
            if (mFragment.getContext() != null && text != null) {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) 
                    mFragment.requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    android.content.ClipData clip = android.content.ClipData.newPlainText("Termux", text);
                    clipboard.setPrimaryClip(clip);
                    
                    // Show a toast
                    if (mFragment.getActivity() != null) {
                        Toast.makeText(mFragment.getActivity(), "Text copied", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        }
    }

    /**
     * Standalone toolbar adapter
     */
    private static class StandaloneToolbarAdapter extends TerminalToolbarViewPager.PageAdapter {
        private final TerminalFragment mFragment;
        private String mSavedTextInputLocal;

        public StandaloneToolbarAdapter(TerminalFragment fragment, String savedTextInput) {
            super(createDummyActivity(fragment), savedTextInput);
            mFragment = fragment;
            mSavedTextInputLocal = savedTextInput;
        }

        private static TermuxActivity createDummyActivity(TerminalFragment fragment) {
            return null;
        }

        @NonNull
        @Override
        public Object instantiateItem(@NonNull ViewGroup collection, int position) {
            Context context = mFragment.requireContext();
            LayoutInflater inflater = LayoutInflater.from(context);
            View layout;
            
            if (position == 0) {
                // Extra keys view
                layout = inflater.inflate(R.layout.view_terminal_toolbar_extra_keys, collection, false);
                ExtraKeysView extraKeysView = (ExtraKeysView) layout;
                
                if (mFragment.mTermuxTerminalExtraKeys != null) {
                    extraKeysView.setExtraKeysViewClient(mFragment.mTermuxTerminalExtraKeys);
                    
                    boolean allCaps = mFragment.mProperties != null && 
                        mFragment.mProperties.shouldExtraKeysTextBeAllCaps();
                    extraKeysView.setButtonTextAllCaps(allCaps);
                    
                    mFragment.mExtraKeysView = extraKeysView;
                    
                    if (mFragment.mExtraKeysInfo != null) {
                        extraKeysView.reload(
                            mFragment.mExtraKeysInfo,
                            mFragment.mTerminalToolbarDefaultHeight
                        );
                    }
                }
            } else {
                // Text input view
                layout = inflater.inflate(R.layout.view_terminal_toolbar_text_input, collection, false);
                final EditText editText = layout.findViewById(R.id.terminal_toolbar_text_input);

                if (mSavedTextInputLocal != null) {
                    editText.setText(mSavedTextInputLocal);
                    mSavedTextInputLocal = null;
                }

                editText.setOnEditorActionListener((v, actionId, event) -> {
                    TerminalSession session = mFragment.getCurrentSession();
                    if (session != null) {
                        if (session.isRunning()) {
                            String textToSend = editText.getText().toString();
                            if (textToSend.length() == 0) textToSend = "\r";
                            session.write(textToSend);
                        } else if (mFragment.mTermuxService != null) {
                            mFragment.mTermuxService.removeTermuxSession(session);
                        }
                        editText.setText("");
                    }
                    return true;
                });
            }
            
            collection.addView(layout);
            return layout;
        }
    }

    /**
     * Standalone toolbar page listener
     */
    private static class StandaloneToolbarPageListener extends TerminalToolbarViewPager.OnPageChangeListener {
        private final TerminalFragment mFragment;

        public StandaloneToolbarPageListener(TerminalFragment fragment, ViewPager viewPager) {
            super(createDummyActivity(fragment), viewPager);
            mFragment = fragment;
        }

        private static TermuxActivity createDummyActivity(TerminalFragment fragment) {
            return null;
        }

        @Override
        public void onPageSelected(int position) {
            if (position == 0 && mFragment.mTerminalView != null) {
                mFragment.mTerminalView.requestFocus();
            } else if (mFragment.getView() != null) {
                final EditText editText = mFragment.getView().findViewById(R.id.terminal_toolbar_text_input);
                if (editText != null) editText.requestFocus();
            }
        }
    }

    /**
     * Standalone extra keys handler - extends TerminalExtraKeys directly to avoid null activity issues
     */
    private static class StandaloneTerminalExtraKeys extends com.termux.shared.termux.terminal.io.TerminalExtraKeys {
        private final TerminalFragment mFragment;
        private final TermuxTerminalViewClient mTermuxTerminalViewClient;
        private final TermuxTerminalSessionActivityClient mTermuxTerminalSessionActivityClient;
        private ExtraKeysInfo mExtraKeysInfo;
        private static final String LOG_TAG = "StandaloneTerminalExtraKeys";

        public StandaloneTerminalExtraKeys(TerminalFragment fragment, TerminalView terminalView,
                                          TerminalViewClient viewClient, TerminalSessionClient sessionClient) {
            super(terminalView);
            mFragment = fragment;
            mTermuxTerminalViewClient = (TermuxTerminalViewClient) viewClient;
            mTermuxTerminalSessionActivityClient = (TermuxTerminalSessionActivityClient) sessionClient;
            setExtraKeys();
        }

        private void setExtraKeys() {
            mExtraKeysInfo = null;

            try {
                String extrakeys = null;
                String extraKeysStyle = null;

                if (mFragment.mProperties != null) {
                    extrakeys = (String) mFragment.mProperties.getInternalPropertyValue(
                        TermuxPropertyConstants.KEY_EXTRA_KEYS, true);
                    extraKeysStyle = (String) mFragment.mProperties.getInternalPropertyValue(
                        TermuxPropertyConstants.KEY_EXTRA_KEYS_STYLE, true);
                }

                if (extrakeys == null) {
                    extrakeys = TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS;
                }
                if (extraKeysStyle == null) {
                    extraKeysStyle = TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE;
                }

                ExtraKeysConstants.ExtraKeyDisplayMap extraKeyDisplayMap = 
                    ExtraKeysInfo.getCharDisplayMapForStyle(extraKeysStyle);
                if (ExtraKeysConstants.EXTRA_KEY_DISPLAY_MAPS.DEFAULT_CHAR_DISPLAY.equals(extraKeyDisplayMap) 
                    && !TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE.equals(extraKeysStyle)) {
                    Logger.logError(TermuxSharedProperties.LOG_TAG, 
                        "The style \"" + extraKeysStyle + "\" for the key \"" + 
                        TermuxPropertyConstants.KEY_EXTRA_KEYS_STYLE + "\" is invalid. Using default style instead.");
                    extraKeysStyle = TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE;
                }

                mExtraKeysInfo = new ExtraKeysInfo(extrakeys, extraKeysStyle, 
                    ExtraKeysConstants.CONTROL_CHARS_ALIASES);
            } catch (JSONException e) {
                Logger.logStackTraceWithMessage(LOG_TAG, 
                    "Could not load and set the \"" + TermuxPropertyConstants.KEY_EXTRA_KEYS + 
                    "\" property from the properties file: ", e);

                try {
                    mExtraKeysInfo = new ExtraKeysInfo(
                        TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS, 
                        TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE, 
                        ExtraKeysConstants.CONTROL_CHARS_ALIASES);
                } catch (JSONException e2) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "Could not create default extra keys: ", e);
                    mExtraKeysInfo = null;
                }
            }
        }

        public ExtraKeysInfo getExtraKeysInfo() {
            return mExtraKeysInfo;
        }

        @Override
        public void onTerminalExtraKeyButtonClick(View view, String key, boolean ctrlDown, 
                                                  boolean altDown, boolean shiftDown, boolean fnDown) {
            if ("KEYBOARD".equals(key)) {
                // Simplified - skip keyboard toggle
            } else if ("DRAWER".equals(key)) {
                // Simplified - no drawer in standalone mode
            } else if ("PASTE".equals(key)) {
                // Simplified - skip paste for now
            } else if ("SCROLL".equals(key)) {
                if (mFragment.mTerminalView != null && mFragment.mTerminalView.mEmulator != null) {
                    mFragment.mTerminalView.mEmulator.toggleAutoScrollDisabled();
                }
            } else {
                super.onTerminalExtraKeyButtonClick(view, key, ctrlDown, altDown, shiftDown, fnDown);
            }
        }
    }
}