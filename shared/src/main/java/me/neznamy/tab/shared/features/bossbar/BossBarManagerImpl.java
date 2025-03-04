package me.neznamy.tab.shared.features.bossbar;

import lombok.Getter;
import me.neznamy.tab.api.TabConstants;
import me.neznamy.tab.api.feature.*;
import me.neznamy.tab.api.TabPlayer;
import me.neznamy.tab.api.bossbar.BarColor;
import me.neznamy.tab.api.bossbar.BarStyle;
import me.neznamy.tab.api.bossbar.BossBar;
import me.neznamy.tab.api.bossbar.BossBarManager;
import me.neznamy.tab.shared.TAB;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Class for handling BossBar feature
 */
public class BossBarManagerImpl extends TabFeature implements BossBarManager, JoinListener, CommandListener, Loadable,
        UnLoadable, WorldSwitchListener, ServerSwitchListener, Refreshable {

    //default BossBars
    private final List<String> defaultBars = new ArrayList<>();

    //registered BossBars
    @Getter private final Map<String, BossBar> registeredBossBars = new HashMap<>();
    private BossBar[] lineValues;

    //config options
    @Getter private final String toggleCommand = TAB.getInstance().getConfiguration().getConfig().getString("bossbar.toggle-command", "/bossbar");
    private final boolean hiddenByDefault = TAB.getInstance().getConfiguration().getConfig().getBoolean("bossbar.hidden-by-default", false);
    private final boolean rememberToggleChoice = TAB.getInstance().getConfiguration().getConfig().getBoolean("bossbar.remember-toggle-choice", false);
    private final String toggleOnMessage = TAB.getInstance().getConfiguration().getMessages().getBossBarOn();
    private final String toggleOffMessage = TAB.getInstance().getConfiguration().getMessages().getBossBarOff();

    //list of currently running BossBar announcements
    @Getter private final List<BossBar> announcedBossBars = new ArrayList<>();

    //players with toggled BossBar
    private final List<String> bossBarOffPlayers = rememberToggleChoice ? TAB.getInstance().getConfiguration().getPlayerDataFile()
            .getStringList("bossbar-off", new ArrayList<>()) : Collections.emptyList();

    //time when BossBar announce ends, used for placeholder
    private long announceEndTime;

    private final Set<TabPlayer> visiblePlayers = Collections.newSetFromMap(new WeakHashMap<>());

    @Getter private final String featureName = "BossBar";
    @Getter private final String refreshDisplayName = "Updating display conditions";

    /**
     * Constructs new instance and loads configuration
     */
    public BossBarManagerImpl() {
        super("bossbar");
        for (Object bar : TAB.getInstance().getConfiguration().getConfig().getConfigurationSection("bossbar.bars").keySet()) {
            BossBarLine line = loadFromConfig(bar.toString());
            registeredBossBars.put(bar.toString(), line);
            if (!line.isAnnouncementBar()) defaultBars.add(bar.toString());
        }
        lineValues = registeredBossBars.values().toArray(new BossBar[0]);
    }

    /**
     * Loads BossBar from config by its name
     *
     * @param   bar
     *          name of BossBar in config
     * @return  loaded BossBar
     */
    private BossBarLine loadFromConfig(String bar) {
        Map<String, Object> bossBar = TAB.getInstance().getConfiguration().getConfig().getConfigurationSection("bossbar.bars." + bar);
        String condition = (String) bossBar.get("display-condition");
        String style = (String) bossBar.get("style");
        String color = (String) bossBar.get("color");
        String progress = String.valueOf(bossBar.get("progress"));
        String text = (String) bossBar.get("text");
        if (style == null) {
            TAB.getInstance().getErrorManager().missingAttribute(getFeatureName(), bar, "style");
            style = "PROGRESS";
        }
        if (color == null) {
            TAB.getInstance().getErrorManager().missingAttribute(getFeatureName(), bar, "color");
            color = "WHITE";
        }
        if (progress == null) {
            progress = "100";
            TAB.getInstance().getErrorManager().missingAttribute(getFeatureName(), bar, "progress");
        }
        if (text == null) {
            text = "";
            TAB.getInstance().getErrorManager().missingAttribute(getFeatureName(), bar, "text");
        }
        return new BossBarLine(this, bar, condition, color, style, text, progress, (boolean) bossBar.getOrDefault("announcement-bar", false));
    }

    @Override
    public void load() {
        TAB.getInstance().getPlaceholderManager().registerServerPlaceholder(TabConstants.Placeholder.COUNTDOWN, 100, () -> (announceEndTime - System.currentTimeMillis()) / 1000);
        for (TabPlayer p : TAB.getInstance().getOnlinePlayers()) {
            onJoin(p);
        }
    }

    @Override
    public void refresh(TabPlayer p, boolean force) {
        if (!hasBossBarVisible(p) || isDisabledPlayer(p)) return;
        for (BossBar line : lineValues) {
            line.removePlayer(p); //remove all BossBars and then resend them again to keep them displayed in defined order
        }
        showBossBars(p, defaultBars);
        showBossBars(p, announcedBossBars.stream().map(BossBar::getName).collect(Collectors.toList()));
    }

    @Override
    public void unload() {
        for (BossBar line : lineValues) {
            for (TabPlayer p : TAB.getInstance().getOnlinePlayers()) {
                line.removePlayer(p);
            }
        }
    }

    @Override
    public void onJoin(TabPlayer connectedPlayer) {
        if (isDisabled(connectedPlayer.getServer(), connectedPlayer.getWorld())) {
            addDisabledPlayer(connectedPlayer);
        }
        setBossBarVisible(connectedPlayer, hiddenByDefault == bossBarOffPlayers.contains(connectedPlayer.getName()), false);
    }

    @Override
    public void onServerChange(TabPlayer p, String from, String to) {
        onWorldChange(p, null, null);
    }

    @Override
    public void onWorldChange(TabPlayer p, String from, String to) {
        if (isDisabled(p.getServer(), p.getWorld())) {
            addDisabledPlayer(p);
        } else {
            removeDisabledPlayer(p);
        }
        for (BossBar line : lineValues) {
            line.removePlayer(p);
        }
        detectBossBarsAndSend(p);
    }

    @Override
    public boolean onCommand(TabPlayer sender, String message) {
        if (message.equalsIgnoreCase(toggleCommand)) {
            TAB.getInstance().getCommand().execute(sender, new String[] {"bossbar"});
            return true;
        }
        return false;
    }

    /**
     * Clears and resends all BossBars to specified player
     *
     * @param   p
     *          player to process
     */
    protected void detectBossBarsAndSend(TabPlayer p) {
        if (isDisabledPlayer(p) || !hasBossBarVisible(p)) return;
        showBossBars(p, defaultBars);
        showBossBars(p, announcedBossBars.stream().map(BossBar::getName).collect(Collectors.toList()));
    }

    /**
     * Shows BossBars to player if display condition is met
     *
     * @param   p
     *          player to show BossBars to
     * @param   bars
     *          list of BossBars to check
     */
    private void showBossBars(TabPlayer p, List<String> bars) {
        for (String defaultBar : bars) {
            BossBarLine bar = (BossBarLine) registeredBossBars.get(defaultBar);
            if (bar.isConditionMet(p) && !bar.containsPlayer(p)) {
                bar.addPlayer(p);
            }
        }
    }

    @Override
    public BossBar createBossBar(String title, float progress, BarColor color, BarStyle style) {
        return createBossBar(title, String.valueOf(progress), color.toString(), style.toString());
    }

    @Override
    public BossBar createBossBar(String title, String progress, String color, String style) {
        UUID id = UUID.randomUUID();
        BossBar bar = new BossBarLine(this, id.toString(), null, color, style, title, progress, true);
        registeredBossBars.put(id.toString(), bar);
        lineValues = registeredBossBars.values().toArray(new BossBar[0]);
        return bar;
    }

    @Override
    public BossBar getBossBar(String name) {
        return registeredBossBars.get(name);
    }

    @Override
    public BossBar getBossBar(UUID id) {
        for (BossBar line : lineValues) {
            if (line.getUniqueId() == id) return line;
        }
        return null;
    }

    @Override
    public void toggleBossBar(TabPlayer player, boolean sendToggleMessage) {
        setBossBarVisible(player, !hasBossBarVisible(player), sendToggleMessage);
    }

    @Override
    public boolean hasBossBarVisible(TabPlayer player) {
        return visiblePlayers.contains(player);
    }

    @Override
    public void setBossBarVisible(TabPlayer player, boolean visible, boolean sendToggleMessage) {
        if (visiblePlayers.contains(player) == visible) return;
        if (visible) {
            visiblePlayers.add(player);
            detectBossBarsAndSend(player);
            if (sendToggleMessage) player.sendMessage(toggleOnMessage, true);
            if (rememberToggleChoice) {
                if (hiddenByDefault) {
                    if (!bossBarOffPlayers.contains(player.getName())) bossBarOffPlayers.add(player.getName());
                } else {
                    bossBarOffPlayers.remove(player.getName());
                }
                TAB.getInstance().getConfiguration().getPlayerDataFile().set("bossbar-off", new ArrayList<>(bossBarOffPlayers));
            }
        } else {
            visiblePlayers.remove(player);
            for (BossBar l : lineValues) {
                l.removePlayer(player);
            }
            if (sendToggleMessage) player.sendMessage(toggleOffMessage, true);
            if (rememberToggleChoice) {
                if (hiddenByDefault) {
                    bossBarOffPlayers.remove(player.getName());
                } else {
                    if (!bossBarOffPlayers.contains(player.getName())) bossBarOffPlayers.add(player.getName());
                }
                TAB.getInstance().getConfiguration().getPlayerDataFile().set("bossbar-off", new ArrayList<>(bossBarOffPlayers));
            }
        }
        TAB.getInstance().getPlaceholderManager().getTabExpansion().setBossBarVisible(player, visible);
    }

    @Override
    public void sendBossBarTemporarily(TabPlayer player, String bossBar, int duration) {
        if (!hasBossBarVisible(player)) return;
        BossBar line = registeredBossBars.get(bossBar);
        if (line == null) throw new IllegalArgumentException("No registered BossBar found with name " + bossBar);
        TAB.getInstance().getCPUManager().runTask(() -> line.addPlayer(player));
        TAB.getInstance().getCPUManager().runTaskLater(duration*1000,
                this, "Removing temporary BossBar", () -> line.removePlayer(player));
    }

    @Override
    public void announceBossBar(String bossBar, int duration) {
        BossBar line = registeredBossBars.get(bossBar);
        if (line == null) throw new IllegalArgumentException("No registered BossBar found with name " + bossBar);
        List<TabPlayer> players = Arrays.stream(TAB.getInstance().getOnlinePlayers()).filter(
                p -> !isDisabledPlayer(p) && hasBossBarVisible(p)).collect(Collectors.toList());
        TAB.getInstance().getCPUManager().runTask(() -> {
            TAB.getInstance().getPlaceholderManager().getPlaceholder(TabConstants.Placeholder.COUNTDOWN).markAsUsed();
            announcedBossBars.add(line);
            announceEndTime = System.currentTimeMillis() + duration* 1000L;
            for (TabPlayer all : players) {
                if (((BossBarLine)line).isConditionMet(all)) line.addPlayer(all);
            }
        });
        TAB.getInstance().getCPUManager().runTaskLater(duration*1000,
                this, "Removing announced BossBar", () -> {
            for (TabPlayer all : players) {
                line.removePlayer(all);
            }
            announcedBossBars.remove(line);
        });
    }
}