package pl.jaruso99.namemob;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public class NameMob extends JavaPlugin implements Listener, CommandExecutor {

    private static final long MAX_PENDING_AGE_NANOS = 2_500_000_000L;
    private static final double MAX_MATCH_DISTANCE_SQUARED = 64.0D;
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");

    private NamespacedKey spawnedByKey;
    private NamespacedKey spawnedByUuidKey;
    private NamespacedKey spawnTimeKey;
    private NamespacedKey mobToolKey;
    private NamespacedKey nameTaggedByKey;
    private NamespacedKey nameTaggedTextKey;
    private NamespacedKey nameTaggedTimeKey;
    private ZoneId timeZone;

    // Krotka kolejka zdarzen uzycia spawn egg; bez map i bez skanowania graczy.
    private final Deque<EggUseContext> pendingEggUses = new ArrayDeque<>();

    @Override
    public void onEnable() {
        spawnedByKey = new NamespacedKey(this, "spawned_by");
        spawnedByUuidKey = new NamespacedKey(this, "spawned_by_uuid");
        spawnTimeKey = new NamespacedKey(this, "spawn_time");
        mobToolKey = new NamespacedKey(this, "moblog_tool");
        nameTaggedByKey = new NamespacedKey(this, "nametag_by");
        nameTaggedTextKey = new NamespacedKey(this, "nametag_text");
        nameTaggedTimeKey = new NamespacedKey(this, "nametag_time");

        // Load config and timezone (default to Europe/Warsaw)
        getConfig().addDefault("timezone", "Europe/Warsaw");
        getConfig().options().copyDefaults(true);
        saveConfig();
        String tz = getConfig().getString("timezone", "Europe/Warsaw");
        try {
            timeZone = ZoneId.of(tz);
        } catch (Exception e) {
            timeZone = ZoneId.of("Europe/Warsaw");
            getLogger().warning("Nieprawidlowa strefa czasowa w config.yml: " + tz + ", uzywam Europe/Warsaw");
        }

        if (getCommand("mob") != null) {
            getCommand("mob").setExecutor(this);
        }
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Ta komenda jest tylko dla graczy!");
            return true;
        }

        Player player = (Player) sender;

        if (args.length == 0) {
            player.sendMessage(ChatColor.DARK_AQUA + "--- NameMob Help ---");
            player.sendMessage(
                    ChatColor.AQUA + "/mob tool " + ChatColor.WHITE + "- Daje narzedzie do sprawdzania mobow");
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("tool")) {
            if (!player.hasPermission("namemob.admin")) {
                player.sendMessage(ChatColor.RED + "Nie masz uprawnien!");
                return true;
            }

            player.getInventory().addItem(getMobTool());
            player.sendMessage(ChatColor.BLUE + "Otrzymales moblog tool!");
            return true;
        }

        player.sendMessage(ChatColor.RED + "Uzycie: /mob tool");
        return true;
    }

    private ItemStack getMobTool() {
        ItemStack item = new ItemStack(Material.DIAMOND_PICKAXE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.BLUE + "moblog");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Uzyj na mobie, aby sprawdzic");
            lore.add(ChatColor.GRAY + "kto i kiedy go zrespil.");
            meta.setLore(lore);
            meta.addEnchant(Enchantment.LUCK, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            meta.getPersistentDataContainer().set(mobToolKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();
        if (isMobTool(item)) {
            event.getItemDrop().remove();
            event.getPlayer().sendMessage(ChatColor.RED + "Twoj moblog tool zostal usuniety!");
        }
    }

    private boolean isMobTool(ItemStack item) {
        if (item == null || item.getType() != Material.DIAMOND_PICKAXE) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null
                && meta.getPersistentDataContainer().has(mobToolKey, PersistentDataType.BYTE)
                && (ChatColor.BLUE + "moblog").equals(meta.getDisplayName());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        ItemStack item = event.getItem();
        if (item == null || !isSpawnEgg(item.getType())) {
            return;
        }

        long now = System.nanoTime();
        cleanupOldContexts(now);

        Location interactionLocation = event.getClickedBlock() != null
                ? event.getClickedBlock().getLocation().add(0.5D, 0.5D, 0.5D)
                : event.getPlayer().getLocation();

        pendingEggUses.addLast(new EggUseContext(
                event.getPlayer().getUniqueId(),
                event.getPlayer().getName(),
                event.getPlayer().getWorld().getUID(),
                interactionLocation,
                now));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.SPAWNER_EGG) {
            return;
        }

        long now = System.nanoTime();
        cleanupOldContexts(now);

        EggUseContext matchedContext = findAndConsumeContext(event.getEntity().getLocation(),
                event.getEntity().getWorld().getUID(), now);
        if (matchedContext == null) {
            return;
        }

        PersistentDataContainer data = event.getEntity().getPersistentDataContainer();
        data.set(spawnedByKey, PersistentDataType.STRING, matchedContext.playerName);
        data.set(spawnedByUuidKey, PersistentDataType.STRING, matchedContext.playerUuid.toString());
        data.set(spawnTimeKey, PersistentDataType.STRING,
                DATE_TIME_FORMATTER.format(ZonedDateTime.now(timeZone)));
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();

        // Track when a player uses a Name Tag on an entity: save who, when and the
        // text.
        if (item != null && item.getType() == Material.NAME_TAG) {
            if (item.hasItemMeta()) {
                ItemMeta im = item.getItemMeta();
                if (im.hasDisplayName()) {
                    Entity tagged = event.getRightClicked();
                    PersistentDataContainer pdata = tagged.getPersistentDataContainer();
                    pdata.set(nameTaggedByKey, PersistentDataType.STRING, player.getName());
                    pdata.set(nameTaggedTextKey, PersistentDataType.STRING, im.getDisplayName());
                    pdata.set(nameTaggedTimeKey, PersistentDataType.STRING,
                            DATE_TIME_FORMATTER.format(ZonedDateTime.now(timeZone)));
                }
            }
        }

        if (isMobTool(item)) {
            event.setCancelled(true);
            Entity entity = event.getRightClicked();
            PersistentDataContainer data = entity.getPersistentDataContainer();

            if (data.has(spawnedByKey, PersistentDataType.STRING)) {
                String owner = data.get(spawnedByKey, PersistentDataType.STRING);
                String date = data.get(spawnTimeKey, PersistentDataType.STRING);

                player.sendMessage(ChatColor.DARK_AQUA + "--- Mob Info ---");
                player.sendMessage(
                        ChatColor.AQUA + "Plugin version " + ChatColor.WHITE + getDescription().getVersion());
                player.sendMessage(ChatColor.AQUA + "Spawned by " + ChatColor.WHITE + owner + ChatColor.AQUA + " at "
                        + ChatColor.WHITE + date);
            } else {
                player.sendMessage(ChatColor.RED + "Ten mob nie ma zapisanego logu.");
            }

            // If the mob was named with a Name Tag, show that record too
            if (data.has(nameTaggedTextKey, PersistentDataType.STRING)) {
                String namedBy = data.has(nameTaggedByKey, PersistentDataType.STRING)
                        ? data.get(nameTaggedByKey, PersistentDataType.STRING)
                        : "nieznany";
                String nameText = data.get(nameTaggedTextKey, PersistentDataType.STRING);
                String nameTime = data.has(nameTaggedTimeKey, PersistentDataType.STRING)
                        ? data.get(nameTaggedTimeKey, PersistentDataType.STRING)
                        : "unknown";
                player.sendMessage(ChatColor.AQUA + "Named by " + ChatColor.WHITE + namedBy + ChatColor.AQUA + " as "
                        + ChatColor.WHITE + nameText + ChatColor.AQUA + " at " + ChatColor.WHITE + nameTime);
            }
        }
    }

    private EggUseContext findAndConsumeContext(Location spawnLocation, UUID worldUuid, long now) {
        EggUseContext singleWorldCandidate = null;
        int worldCandidates = 0;
        Iterator<EggUseContext> iterator = pendingEggUses.descendingIterator();

        while (iterator.hasNext()) {
            EggUseContext context = iterator.next();
            if (!context.worldUuid.equals(worldUuid)) {
                continue;
            }
            if (now - context.timestampNanos > MAX_PENDING_AGE_NANOS) {
                continue;
            }

            if (context.location.distanceSquared(spawnLocation) <= MAX_MATCH_DISTANCE_SQUARED) {
                iterator.remove();
                return context;
            }

            worldCandidates++;
            if (singleWorldCandidate == null) {
                singleWorldCandidate = context;
            }
        }

        // Jesli w oknie czasowym byl tylko jeden kandydat z tego swiata,
        // mozemy przypisac go bez ryzyka pomylenia graczy.
        if (worldCandidates == 1 && singleWorldCandidate != null) {
            pendingEggUses.remove(singleWorldCandidate);
            return singleWorldCandidate;
        }

        return null;
    }

    private void cleanupOldContexts(long now) {
        while (!pendingEggUses.isEmpty()) {
            EggUseContext first = pendingEggUses.peekFirst();
            if (first == null || now - first.timestampNanos <= MAX_PENDING_AGE_NANOS) {
                break;
            }
            pendingEggUses.pollFirst();
        }
    }

    private boolean isSpawnEgg(Material material) {
        return material != null && material.name().endsWith("_SPAWN_EGG");
    }

    private static final class EggUseContext {
        private final UUID playerUuid;
        private final String playerName;
        private final UUID worldUuid;
        private final Location location;
        private final long timestampNanos;

        private EggUseContext(UUID playerUuid, String playerName, UUID worldUuid, Location location,
                long timestampNanos) {
            this.playerUuid = playerUuid;
            this.playerName = playerName;
            this.worldUuid = worldUuid;
            this.location = location;
            this.timestampNanos = timestampNanos;
        }
    }
}
