package com.lgnetwork.plugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.*;

import java.time.Duration;
import java.util.*;

public final class LGPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private enum State { IDLE, STARTING, ACTIVE }
    private enum Card { HEARTS, DIAMOND, SPADE, CLOVER }
    private State state = State.IDLE;
    private long sessionId = 0;
    private final Map<UUID, Card> cards = new HashMap<>();
    private final Map<UUID, String> rpNames = new HashMap<>();
    private final Set<UUID> participants = new LinkedHashSet<>();
    private final Set<UUID> eliminated = new HashSet<>();
    private final Map<UUID, Integer> kills = new HashMap<>();
    private final Map<UUID, Integer> deaths = new HashMap<>();
    private final Map<UUID, Set<UUID>> heartSeen = new HashMap<>();
    private final Map<UUID, BukkitTask> heartbeatTasks = new HashMap<>();
    private final List<BukkitTask> sessionTasks = new ArrayList<>();
    private BossBar bossBar;
    private Scoreboard previousBoard;

    @Override public void onEnable() {
        saveDefaultConfig();
        Objects.requireNonNull(getCommand("phase1")).setExecutor(this);
        Objects.requireNonNull(getCommand("lg")).setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("LGPlugin enabled. Phase state: IDLE");
    }

    @Override public void onDisable() { fullReset(false); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender) && !(sender instanceof Player p && (p.isOp() || p.hasPermission("lg.admin")))) {
            sender.sendMessage(ChatColor.RED + "You do not have permission."); return true;
        }
        String action;
        if (command.getName().equalsIgnoreCase("lg")) {
            if (args.length == 0) { help(sender); return true; }
            if (!args[0].equalsIgnoreCase("phase1")) { action = args[0]; }
            else action = args.length > 1 ? args[1] : "start";
        } else action = args.length == 0 ? "start" : args[0];
        switch (action.toLowerCase(Locale.ROOT)) {
            case "start" -> startPhase(sender);
            case "reset" -> { fullReset(true); sender.sendMessage(ChatColor.GREEN + "Phase 1 has been fully reset."); }
            case "restart" -> { fullReset(false); startPhase(sender); }
            case "stop" -> { fullReset(true); sender.sendMessage(ChatColor.YELLOW + "Phase 1 stopped."); }
            case "status" -> sender.sendMessage(ChatColor.GOLD + "Phase 1 state: " + state + ChatColor.GRAY + " | participants: " + participants.size());
            default -> help(sender);
        }
        return true;
    }

    private void help(CommandSender s) {
        s.sendMessage(ChatColor.DARK_RED + "LGPlugin: /phase1 <start|reset|restart|stop|status>");
        s.sendMessage(ChatColor.GRAY + "Aliases: /lg phase1 [action]");
    }

    private void startPhase(CommandSender sender) {
        if (state != State.IDLE) { sender.sendMessage(ChatColor.RED + "Phase 1 is already running. Use /phase1 restart."); return; }
        List<Player> players = Bukkit.getOnlinePlayers().stream().filter(p -> !p.hasPermission("lg.bypass")).toList();
        if (players.isEmpty()) { sender.sendMessage(ChatColor.RED + "No eligible online players."); return; }
        World world = resolveWorld();
        if (world == null) { sender.sendMessage(ChatColor.RED + "No valid world found. Check config.yml."); return; }

        state = State.STARTING;
        long run = ++sessionId;
        participants.clear(); eliminated.clear(); cards.clear(); heartSeen.clear();
        participants.addAll(players.stream().map(Player::getUniqueId).toList());
        List<Card> deck = new ArrayList<>(Arrays.asList(Card.values()));
        Collections.shuffle(deck);
        for (int i = 0; i < players.size(); i++) cards.put(players.get(i).getUniqueId(), deck.get(i % deck.size()));
        // All cards are assigned before the first teleport begins.
        bossBar = Bukkit.createBossBar("§4LAST GAMBLE §7| §fPHASE 1", BarColor.RED, BarStyle.SOLID);
        bossBar.setProgress(1.0);
        for (Player player : players) {
            bossBar.addPlayer(player);
            preparePlayer(player);
            announceOpening(player);
        }
        sender.sendMessage(ChatColor.GREEN + "Phase 1 starting for " + players.size() + " players. Cards assigned.");
        teleportNext(players, 0, run, world);
    }

    private void announceOpening(Player p) {
        if (!getConfig().getBoolean("phase1.dramatic-entrance", true)) return;
        p.showTitle(Title.title(Component.text("LAST GAMBLE", NamedTextColor.DARK_RED), Component.text("RE: LIFE", NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(2), Duration.ofMillis(500))));
        p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.75f, 0.65f);
        p.playSound(p.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 0.8f, 0.55f);
        p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, Math.max(1, getConfig().getInt("phase1.darkness-seconds", 3)) * 20, 0, false, false, false));
        p.sendMessage(ChatColor.DARK_RED + "A new game is about to begin...");
    }

    private void teleportNext(List<Player> players, int index, long run, World world) {
        if (run != sessionId || state != State.STARTING) return;
        if (index >= players.size()) {
            state = State.ACTIVE;
            for (Player p : players) if (p.isOnline()) entranceFinish(p);
            getLogger().info("Phase 1 session " + run + " is now ACTIVE.");
            return;
        }
        Player p = players.get(index);
        if (p.isOnline()) {
            Location target = safeSpawnOffset(world, index);
            p.teleportAsync(target).thenAccept(success -> Bukkit.getScheduler().runTask(this, () -> {
                if (run != sessionId || state != State.STARTING) return;
                if (!success) { getLogger().warning("Teleport failed for " + p.getUniqueId()); }
                teleportNext(players, index + 1, run, world);
            }));
        } else teleportNext(players, index + 1, run, world);
    }

    private Location safeSpawnOffset(World world, int index) {
        Location spawn = world.getSpawnLocation();
        int radius = Math.max(0, Math.min(32, getConfig().getInt("phase1.teleport-radius", 8)));
        // Keep targets close to spawn to avoid unbounded random searches and expensive chunk scans.
        int angleStep = index * 137;
        double radians = Math.toRadians(angleStep);
        double distance = radius == 0 ? 0 : Math.sqrt((index % 16) / 15.0) * radius;
        int x = spawn.getBlockX() + (int)Math.round(Math.cos(radians) * distance);
        int z = spawn.getBlockZ() + (int)Math.round(Math.sin(radians) * distance);
        int y = world.getHighestBlockYAt(x, z) + 1;
        return new Location(world, x + 0.5, y, z + 0.5, spawn.getYaw(), spawn.getPitch());
    }

    private void entranceFinish(Player p) {
        if (!p.isOnline()) return;
        p.removePotionEffect(PotionEffectType.DARKNESS);
        p.spawnParticle(Particle.PORTAL, p.getLocation().add(0, 1, 0), 90, 0.8, 1.2, 0.8, 0.15);
        p.spawnParticle(Particle.SOUL, p.getLocation().add(0, 1, 0), 35, 0.6, 1.0, 0.6, 0.04);
        p.playSound(p.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.9f, 0.7f);
        p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.45f, 0.8f);
        p.showTitle(Title.title(Component.text("THE SHOW HAS BEGUN", NamedTextColor.RED), Component.text("YOUR FATE IS SEALED", NamedTextColor.DARK_RED),
                Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(3), Duration.ofMillis(900))));
        p.sendMessage(ChatColor.DARK_RED + "♦ " + ChatColor.GRAY + "Your card: " + ChatColor.WHITE + cards.get(p.getUniqueId()));
    }

    private void preparePlayer(Player p) {
        if (getConfig().getBoolean("phase1.inventory.clear", true)) p.getInventory().clear();
        if (getConfig().getBoolean("phase1.inventory.leather-armor", true)) {
            p.getInventory().setHelmet(leather(Material.LEATHER_HELMET));
            p.getInventory().setChestplate(leather(Material.LEATHER_CHESTPLATE));
            p.getInventory().setLeggings(leather(Material.LEATHER_LEGGINGS));
            p.getInventory().setBoots(leather(Material.LEATHER_BOOTS));
        }
        if (getConfig().getBoolean("phase1.inventory.starter-items", true)) {
            p.getInventory().addItem(new ItemStack(Material.STONE_SWORD), new ItemStack(Material.STONE_PICKAXE), new ItemStack(Material.STONE_SHOVEL), new ItemStack(Material.COOKED_BEEF, 10));
        }
        p.setHealth(Objects.requireNonNull(p.getAttribute(Attribute.MAX_HEALTH)).getValue());
        p.setFoodLevel(20); p.setSaturation(10); p.setFireTicks(0);
        p.setGameMode(GameMode.SURVIVAL);
    }

    private ItemStack leather(Material material) {
        ItemStack item = new ItemStack(material);
        if (item.getItemMeta() instanceof LeatherArmorMeta meta) { meta.setColor(Color.fromRGB(80, 20, 25)); item.setItemMeta(meta); }
        return item;
    }

    private World resolveWorld() {
        String configured = getConfig().getString("phase1.world", "");
        if (configured != null && !configured.isBlank()) return Bukkit.getWorld(configured);
        return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
    }

    private void fullReset(boolean notify) {
        sessionId++;
        for (BukkitTask task : sessionTasks) task.cancel(); sessionTasks.clear();
        for (BukkitTask task : heartbeatTasks.values()) task.cancel(); heartbeatTasks.clear();
        if (bossBar != null) { bossBar.removeAll(); bossBar.setVisible(false); bossBar = null; }
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.removePotionEffect(PotionEffectType.DARKNESS);
            p.resetTitle();
            if (getConfig().getBoolean("reset.clear-inventory", true)) p.getInventory().clear();
            if (getConfig().getBoolean("reset.restore-health", true)) {
                p.setHealth(Objects.requireNonNull(p.getAttribute(Attribute.MAX_HEALTH)).getValue());
                p.setFireTicks(0);
            }
            if (getConfig().getBoolean("reset.restore-food", true)) { p.setFoodLevel(20); p.setSaturation(10); }
            if (notify) p.sendMessage(ChatColor.GRAY + "Phase 1 has been reset.");
        }
        cards.clear(); participants.clear(); eliminated.clear(); heartSeen.clear(); kills.clear(); deaths.clear();
        state = State.IDLE;
        getLogger().info("Phase 1 reset. State=IDLE");
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) {
        if (bossBar != null && participants.contains(e.getPlayer().getUniqueId())) bossBar.addPlayer(e.getPlayer());
    }

    @EventHandler public void onDeath(PlayerDeathEvent e) {
        Player dead = e.getEntity();
        if (state == State.ACTIVE && participants.contains(dead.getUniqueId())) {
            deaths.merge(dead.getUniqueId(), 1, Integer::sum);
            e.deathMessage(null);
            String rp = rpNames.getOrDefault(dead.getUniqueId(), dead.getName());
            for (Player online : Bukkit.getOnlinePlayers()) if (participants.contains(online.getUniqueId()))
                online.sendMessage(ChatColor.DARK_RED + "☠ " + ChatColor.GRAY + rp + " telah gugur dari permainan.");
            eliminated.add(dead.getUniqueId());
            dead.setGameMode(GameMode.SPECTATOR);
        }
    }

    public void setRpName(UUID player, String name) { rpNames.put(player, name); }
}
