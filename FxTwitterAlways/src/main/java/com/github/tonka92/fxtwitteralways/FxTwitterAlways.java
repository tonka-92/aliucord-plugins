package com.github.tonka92.fxtwitteralways;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.aliucord.Logger;
import com.aliucord.Utils;
import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.api.SettingsAPI;
import com.aliucord.entities.Plugin;
import com.aliucord.fragments.SettingsPage;
import com.aliucord.patcher.*;
import com.aliucord.utils.DimenUtils;
import com.aliucord.utils.ReflectUtils;
import com.aliucord.views.TextInput;
import com.discord.models.message.Message;
import com.discord.stores.StoreStream;
import com.discord.utilities.view.text.SimpleDraweeSpanTextView;
import com.discord.views.CheckedSetting;
import com.discord.widgets.chat.MessageContent;
import com.discord.widgets.chat.MessageManager;
import com.discord.widgets.chat.input.ChatInputViewModel;
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage;
import com.discord.widgets.chat.list.entries.MessageEntry;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import kotlin.jvm.functions.Function1;

// Aliucord Plugin annotation. Must be present on the main class of your plugin
@AliucordPlugin(requiresRestart = true /* Whether your plugin requires a restart after being installed/updated */)
// Plugin class. Must extend Plugin and override start and stop
// Learn more: https://github.com/Aliucord/documentation/blob/main/plugin-dev/1_introduction.md#basic-plugin-structure
public class FxTwitterAlways extends Plugin {

    private static final Logger logger = new Logger("FxTwitterAlways");

    public static final String ENABLE_OUTGOING_REPLACE = "OutgoingReplace";
    public static final String OUTGOING_REPLACE_DOMAIN = "OutgoingReplaceDomain";
    public static final String ENABLE_INCOMING_REPLACE = "IncomingReplace";

    public static final String DEFAULT_REPLACE_DOMAIN = "pxtwitter.com";

    public static final Pattern TWITTER_PATTERN = Pattern.compile("https?://(www\\.)?twitter\\.com/[^/]+/status/[\\d]+(\\?[^\\s]*)?");

    public FxTwitterAlways() {
        settingsTab = new SettingsTab(PluginSettings.class).withArgs(settings);
    }

    public static class PluginSettings extends SettingsPage {
        private final SettingsAPI settings;

        public PluginSettings(SettingsAPI settings) {
            this.settings = settings;
        }

        @Override
        public void onViewBound(View view) {
            super.onViewBound(view);

            Context ctx = view.getContext();

            setActionBarTitle("FxTwitterAlways");
            setPadding(0);
            addView(createCheckedSetting(ctx, ENABLE_OUTGOING_REPLACE, true, "Enable outgoing domain & query param replacement", "Replaces `twitter.com` with the below domain in messages you write, also removes query params that track you"));
            addView(createTextSetting(ctx, OUTGOING_REPLACE_DOMAIN, DEFAULT_REPLACE_DOMAIN, "Outgoing replacement domain, e.g. pxtwitter.com"));
            addView(createCheckedSetting(ctx, ENABLE_INCOMING_REPLACE, true, "Enable incoming query param replacement", "Removes query params from incoming messages"));
        }

        private View createCheckedSetting(Context ctx, String key, boolean defVal, String title, String subtitle) {
            CheckedSetting cs = Utils.createCheckedSetting(ctx, CheckedSetting.ViewType.SWITCH, title, subtitle);
            cs.setChecked(settings.getBool(key, defVal));
            cs.setOnCheckedListener(check -> settings.setBool(key, check));
            return cs;
        }

        private View createTextSetting(Context ctx, String key, String defValue, String title) {
            String value = settings.getString(key, defValue);
            TextInput ti = new TextInput(ctx, title, value, new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {}

                @Override
                public void afterTextChanged(Editable s) {
                    settings.setString(key, s.toString());
                }
            });
            int defPadding = DimenUtils.getDefaultPadding();
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(defPadding, defPadding, defPadding, 0);
            ti.setLayoutParams(lp);
            return ti;
        }
    }

    @Override
    public void start(Context context) throws Throwable {

        // Modify outgoing message
        patcher.patch(ChatInputViewModel.class.getDeclaredMethod("sendMessage", Context.class, MessageManager.class, MessageContent.class, List.class, boolean.class, Function1.class),
                new PreHook(cf -> {
                    if (settings.getBool(ENABLE_OUTGOING_REPLACE, true)) {
                        ChatInputViewModel obj = (ChatInputViewModel) cf.thisObject;
                        MessageContent content = (MessageContent) cf.args[2];
                        String currentText = content.getTextContent();
                        String newText = currentText;
                        Matcher m = TWITTER_PATTERN.matcher(currentText);
                        while (m.find()) {
                            String linkText = m.group();
                            String replacedText = linkText.replace("twitter.com", settings.getString(OUTGOING_REPLACE_DOMAIN, DEFAULT_REPLACE_DOMAIN));
                            if (m.group(2) != null) {
                                replacedText = replacedText.replace(m.group(2), "");
                            }
                            newText = newText.replace(linkText, replacedText);
                        }
                        if (!currentText.equals(newText)) {
                            try {
                                ReflectUtils.setField(content, "textContent", newText);
                            } catch (NoSuchFieldException | IllegalAccessException e) {
                                logger.error("Error setting textContent", e);
                            }
                        }
                    }
                })
        );
        // Patch that adds an embed with message statistics to each message
        // Patched method is WidgetChatListAdapterItemMessage.onConfigure(int type, ChatListEntry entry)
        patcher.patch(
                // see https://docs.oracle.com/javase/tutorial/reflect/class/classNew.html
                WidgetChatListAdapterItemMessage.class.getDeclaredMethod("processMessageText", SimpleDraweeSpanTextView.class, MessageEntry.class),
                new PreHook(param -> { // see https://api.xposed.info/reference/de/robv/android/xposed/XC_MethodHook.MethodHookParam.html
                    // Obtain the second argument passed to the method, so the ChatListEntry
                    // Because this is a Message item, it will always be a MessageEntry, so cast it to that
                    var entry = (MessageEntry) param.args[1];

                    Message message = entry.getMessage();

                    // You need to be careful when messing with messages, because they may be loading
                    // (user sent a message, and it is currently sending)
                    if (message.isLoading()) return;

                    if (settings.getBool(ENABLE_INCOMING_REPLACE, true)) {
                        String currentText = message.getContent();
                        String newText = currentText;
                        Matcher m = TWITTER_PATTERN.matcher(currentText);
//                        List<String> twitterLinks = new ArrayList<>();
                        while (m.find()) {
                            String linkText = m.group();
//                            String replacedText = linkText.replace("twitter.com", settings.getString(OUTGOING_REPLACE_DOMAIN, DEFAULT_REPLACE_DOMAIN));
                            String replacedText = linkText;
                            if (m.group(2) != null) {
                                replacedText = replacedText.replace(m.group(2), "");
                            }
//                            twitterLinks.add(replacedText);
                            newText = newText.replace(linkText, replacedText);
                        }
                        if (!currentText.equals(newText)) {
                            try {
                                ReflectUtils.setField(message, "content", newText);
                            } catch (NoSuchFieldException | IllegalAccessException e) {
                                logger.error("Error setting content", e);
                            }
/*
                            if (!(message.getEmbeds() instanceof ArrayList)) {
                                try {
                                    ReflectUtils.setField(message, "embeds", new ArrayList<>(message.getEmbeds()));
                                } catch (NoSuchFieldException | IllegalAccessException e) {
                                    logger.error("Error making embeds an arraylist", e);
                                }
                            }
*/
/*
                            for (MessageEmbed embed: message.getEmbeds()) {
                                logger.debug(embed.toString());
                            }
*/
/*
                            message.getEmbeds().clear();
                            for (String link: twitterLinks) {
                                MessageEmbedBuilder builder = new MessageEmbedBuilder();
                                builder.setUrl(link);
                                message.getEmbeds().add(builder.build());
                            }
*/
                            StoreStream.getMessages().handleMessageUpdate(message.synthesizeApiMessage());
                        }
                    }
                })
        );
    }


    @Override
    public void stop(Context context) {
        // Remove all patches
        patcher.unpatchAll();
    }
}
