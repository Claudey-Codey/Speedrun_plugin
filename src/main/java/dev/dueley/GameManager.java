package dev.dueley;

import dev.dueley.menu.SeedMenu;
import dev.dueley.world.ArenaGenerator;
import dev.dueley.world.WorldService;
import io.papermc.paper.util.Tick;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Difficulty;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.generator.structure.Structure;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.StructureSearchResult;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Runs the whole Dueley match: lobby → seed choice → world generation → countdown → gather → fight → lobby. */
public final class GameManager {
    public static final int PLAYERS = 2;
    /** Solo practice: how long the player must survive at the final border size to complete it. */
    public static final int SOLO_SURVIVE_SECONDS = 60;

    private final DueleyPlugin plugin;
    private final WorldService worlds;
    private final NamespacedKey menuItemKey;

    private GameState state = GameState.LOBBY;
    /**
     * Solo practice mode: one participant instead of {@link #PLAYERS}. Only toggled in LOBBY (by /dueley solo, or
     * turned off when a second player joins) and always reset to false when the game returns to the lobby, so it is
     * constant for the whole game.
     */
    private boolean solo;
    private final List<UUID> joinOrder = new ArrayList<>();
    private final List<UUID> participants = new ArrayList<>();
    private final Map<UUID, World> overworlds = new HashMap<>();
    private final Map<UUID, World> nethers = new HashMap<>();
    private final Map<UUID, World> portalLinks = new HashMap<>(); // world uid -> linked world
    private final List<World> gameWorlds = new ArrayList<>();
    private final Set<UUID> timerHidden = new HashSet<>();
    private World arena;

    // seed choice
    private UUID customSeedTyper;
    private boolean menuShown;
    private long seed;
    private SeedMode seedMode;
    private GoodStructure goodStructure;
    private Location structureSpot; // world-less x/y/z, same in every copy of the seed
    private double structureDistance;
    private Location spawnOverride; // world-less; set when the spawn was moved closer to the structure

    // generation
    private BukkitTask generationTask;
    private final Deque<Runnable> generationSteps = new ArrayDeque<>();
    private String generationStatus = "";
    private String worldPrefix = "";

    // timers
    private BukkitTask ticker;
    private int countdown;
    private int remaining;
    private boolean paused;
    private int resumeCountdown = -1;
    private int fightSeconds;
    private int endCountdown;

    // settings (read when a game starts)
    private int gatherMinutes;
    private int startCountdown;
    private int fightCountdown;
    private List<Integer> warningMinutes;
    private Difficulty difficulty;
    private boolean netherEnabled;
    private double arenaStart;
    private double arenaEnd;
    private int shrinkMinutes;
    private double borderDamage;
    private int goodMaxDistance;
    private int goodMaxAttempts;
    private int returnSeconds;

    public GameManager(DueleyPlugin plugin, WorldService worlds) {
        this.plugin = plugin;
        this.worlds = worlds;
        this.menuItemKey = new NamespacedKey(plugin, "seed_menu_item");
        loadSettings();
    }

    // ═════════════════════════ lifecycle ═════════════════════════

    public void start() {
        worlds.sweepLeftovers();
        worlds.lobby();
        freeze(true);
        for (Player p : Bukkit.getOnlinePlayers()) {
            joinOrder.add(p.getUniqueId());
            sendToLobby(p);
        }
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void shutdown() {
        if (ticker != null) {
            ticker.cancel();
        }
        cancelGeneration();
        freeze(false);
    }

    private void loadSettings() {
        plugin.reloadConfig();
        FileConfiguration c = plugin.getConfig();
        gatherMinutes = Math.max(1, c.getInt("gather-minutes", 60));
        startCountdown = Math.max(1, c.getInt("start-countdown-seconds", 10));
        fightCountdown = Math.max(1, c.getInt("fight-countdown-seconds", 10));
        warningMinutes = c.getIntegerList("warning-minutes");
        if (warningMinutes.isEmpty()) {
            warningMinutes = List.of(5, 4, 3, 2, 1);
        }
        try {
            difficulty = Difficulty.valueOf(c.getString("difficulty", "NORMAL").toUpperCase());
        } catch (IllegalArgumentException e) {
            difficulty = Difficulty.NORMAL;
        }
        netherEnabled = c.getBoolean("enable-nether", true);
        arenaStart = Math.max(5, c.getDouble("arena.start-size", 100));
        arenaEnd = Math.max(1, c.getDouble("arena.end-size", 5));
        shrinkMinutes = Math.max(1, c.getInt("arena.shrink-minutes", 15));
        borderDamage = c.getDouble("arena.border-damage", 1.0);
        goodMaxDistance = Math.max(32, c.getInt("good-seed.max-distance", 400));
        goodMaxAttempts = Math.max(1, c.getInt("good-seed.max-attempts", 6));
        returnSeconds = Math.max(3, c.getInt("return-to-lobby-seconds", 15));
    }

    // ═════════════════════════ main loop (every second) ═════════════════════════

    private void tick() {
        switch (state) {
            case LOBBY -> tickLobby();
            case GENERATING -> actionBarAll("<aqua>⚙ " + generationStatus);
            case COUNTDOWN -> tickCountdown();
            case GATHER -> tickGather();
            case FIGHT -> tickFight();
            case ENDED -> {
                if (--endCountdown <= 0) {
                    resetToLobby();
                }
            }
        }
    }

    private void tickLobby() {
        freeze(true);
        int online = Bukkit.getOnlinePlayers().size();
        if (solo && online != 1) {
            // safety net: onJoin/onQuit normally turn solo off already
            soloOff(online > 1 ? "<yellow>Someone joined — solo practice is off." : null);
        }
        if (online < neededPlayers()) {
            menuShown = false;
            String hint = online == 1 ? "/dueley solo to practice alone" : "The game is frozen";
            actionBarAll("<yellow>⌛ Waiting for players <white>(" + online + "/" + PLAYERS + ")</white> <dark_gray>|</dark_gray> <gray>" + hint);
            return;
        }
        if (customSeedTyper != null) {
            Player typer = Bukkit.getPlayer(customSeedTyper);
            String name = typer == null ? "Someone" : typer.getName();
            actionBarAll("<light_purple>✎ " + name + " is typing a custom seed...");
        } else if (solo) {
            actionBarAll("<green>✔ Solo practice! <gray>Choose a seed — right-click the <aqua>Nether Star");
        } else {
            actionBarAll("<green>✔ " + online + "/" + PLAYERS + " players! <gray>Choose a seed — right-click the <aqua>Nether Star");
        }
        if (!menuShown) {
            menuShown = true;
            for (Player p : Bukkit.getOnlinePlayers()) {
                Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 2f);
                openMainMenu(p);
            }
        }
    }

    private void tickCountdown() {
        if (!allParticipantsOnline()) {
            countdown = startCountdown;
            actionBarAll("<red>⏸ Waiting for " + missingNames() + " to come back...");
            return;
        }
        if (countdown > 0) {
            for (Player p : participantsOnline()) {
                Msg.title(p, countdownColor(countdown) + "<b>" + countdown, "<gray>Get ready to gather!", 0, 25, 5);
                Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1f);
            }
            actionBarAll("<yellow>Starting in <white><b>" + countdown + "</b></white>...");
            countdown--;
        } else {
            startGather();
        }
    }

    private void tickGather() {
        if (!allParticipantsOnline()) {
            if (!paused) {
                pause();
            }
            actionBarAll("<red>⏸ PAUSED <dark_gray>|</dark_gray> <gray>Waiting for <white>" + missingNames() + "</white> to come back");
            return;
        }
        if (paused) {
            if (resumeCountdown < 0) {
                resumeCountdown = 5;
            }
            if (resumeCountdown > 0) {
                for (Player p : participantsOnline()) {
                    Msg.title(p, "<yellow>Resuming in " + resumeCountdown, solo ? "<gray>You're back!" : "<gray>Both players are back!", 0, 25, 5);
                    Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1f);
                }
                resumeCountdown--;
                return;
            }
            unpause();
        }

        remaining--;
        if (remaining <= 0) {
            startFight();
            return;
        }
        if (remaining % 60 == 0 && warningMinutes.contains(remaining / 60)) {
            int m = remaining / 60;
            String unit = m == 1 ? "Minute" : "Minutes";
            for (Player p : Bukkit.getOnlinePlayers()) {
                Msg.title(p, "<yellow><b>" + m + " " + unit, "<gray>until the <red>fight</red>!", 5, 50, 10);
                Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1f);
            }
            Msg.broadcast("<yellow>The fight starts in <white><b>" + m + " " + unit.toLowerCase() + "</b></white>!");
        }
        if (remaining <= fightCountdown) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                Msg.title(p, countdownColor(remaining) + "<b>" + remaining, "<gray>until the <red>fight</red>!", 0, 25, 5);
                Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_HAT, 1f, remaining <= 3 ? 2f : 1f);
            }
            if (remaining == fightCountdown) {
                Msg.broadcast("<red>The fight starts in <white><b>" + remaining + " seconds</b></white>!");
            }
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!timerHidden.contains(p.getUniqueId())) {
                String color = remaining <= 60 ? "<red>" : remaining <= 300 ? "<yellow>" : "<white>";
                p.sendActionBar(Msg.mm("<gold>⌚ " + color + "<b>" + Msg.clock(remaining)
                        + "</b></" + color.substring(1) + " <dark_gray>|</dark_gray> <gray>Gather gear! Fight soon"));
            }
        }
    }

    private void tickFight() {
        fightSeconds++;
        if (arena == null) {
            return;
        }
        WorldBorder wb = arena.getWorldBorder();
        int size = (int) Math.ceil(wb.getSize());
        int shrinkLeft = shrinkMinutes * 60 - fightSeconds;
        String tail = shrinkLeft > 0
                ? "<gray>Shrinks to " + (int) arenaEnd + "×" + (int) arenaEnd + " in <white>" + Msg.clock(shrinkLeft)
                : "<red>Final size!";
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!timerHidden.contains(p.getUniqueId())) {
                p.sendActionBar(Msg.mm("<red>⚔ <b>FIGHT</b> <dark_gray>|</dark_gray> <gold>Border <white><b>"
                        + size + "×" + size + "</b></white> <dark_gray>|</dark_gray> " + tail));
            }
        }
        // solo practice has no opponent: it is complete once the player survives a while at the final border size
        if (solo && fightSeconds >= shrinkMinutes * 60 + SOLO_SURVIVE_SECONDS) {
            endPractice(PracticeEnd.SURVIVED);
        }
    }

    // ═════════════════════════ seed choice ═════════════════════════

    public void openMainMenu(Player p) {
        if (state != GameState.LOBBY) {
            Msg.send(p, "<red>A game is already running.");
            return;
        }
        if (Bukkit.getOnlinePlayers().size() < neededPlayers()) {
            Msg.send(p, "<yellow>Waiting for a second player before you can choose a seed. " + SOLO_HINT);
            return;
        }
        List<Player> lobby = lobbyPlayersInOrder();
        String line = solo
                ? "<white>" + p.getName() + " <gray>(solo practice)"
                : lobby.size() >= 2
                ? "<white>" + lobby.get(0).getName() + " <red>vs</red> " + lobby.get(1).getName()
                : "<white>" + lobby.size();
        p.openInventory(SeedMenu.main(line, gatherMinutes).getInventory());
    }

    public void openGoodMenu(Player p) {
        if (state == GameState.LOBBY) {
            p.openInventory(SeedMenu.good().getInventory());
            Msg.sound(p, Sound.UI_BUTTON_CLICK, 1f, 1f);
        }
    }

    private boolean canChoose(Player p) {
        if (state != GameState.LOBBY) {
            Msg.send(p, "<red>A game is already running.");
            return false;
        }
        if (Bukkit.getOnlinePlayers().size() < neededPlayers()) {
            Msg.send(p, "<yellow>Waiting for a second player. " + SOLO_HINT);
            return false;
        }
        if (customSeedTyper != null && !customSeedTyper.equals(p.getUniqueId())) {
            Msg.send(p, "<yellow>Someone else is typing a custom seed.");
            return false;
        }
        return true;
    }

    public void chooseRandom(Player p) {
        if (canChoose(p)) {
            loadSettings();
            beginGame(p, SeedMode.RANDOM, ThreadLocalRandom.current().nextLong(), null);
        }
    }

    public void chooseGood(Player p, GoodStructure s) {
        if (canChoose(p)) {
            loadSettings();
            beginGame(p, SeedMode.GOOD, 0L, s);
        }
    }

    public void chooseCustom(Player p) {
        if (!canChoose(p)) {
            return;
        }
        customSeedTyper = p.getUniqueId();
        p.closeInventory();
        Msg.send(p, "<light_purple>Type your seed in chat. <gray>(numbers or text — type <white>cancel</white> to go back)");
        Msg.title(p, "<light_purple><b>Custom Seed", "<gray>Type the seed in chat", 5, 60, 10);
        Msg.sound(p, Sound.UI_BUTTON_CLICK, 1f, 1f);
    }

    public boolean isTypingSeed(Player p) {
        return p.getUniqueId().equals(customSeedTyper);
    }

    /** Called on the main thread with whatever the player typed. */
    public void handleCustomSeed(Player p, String text) {
        if (!isTypingSeed(p)) {
            return;
        }
        customSeedTyper = null;
        String input = text.trim();
        if (input.equalsIgnoreCase("cancel")) {
            Msg.send(p, "<gray>Cancelled.");
            openMainMenu(p);
            return;
        }
        if (state != GameState.LOBBY || Bukkit.getOnlinePlayers().size() < neededPlayers()) {
            Msg.send(p, "<red>You can't start right now.");
            return;
        }
        loadSettings();
        beginGame(p, SeedMode.CUSTOM, parseSeed(input), null);
    }

    private static final String SOLO_HINT = "<gray>Alone? Practice with <white>/dueley solo</white>.";

    /** Participants needed to start: 1 in solo practice, otherwise {@link #PLAYERS}. */
    private int neededPlayers() {
        return solo ? 1 : PLAYERS;
    }

    public boolean isSolo() {
        return solo;
    }

    /** /dueley solo: turn solo practice on (and open the seed menu) or off. Only in LOBBY with exactly 1 player online. */
    public void toggleSolo(Player p) {
        if (state != GameState.LOBBY) {
            Msg.send(p, "<red>A game is already running.");
            return;
        }
        if (solo) {
            soloOff("<gray>Solo practice is off.");
            return;
        }
        if (Bukkit.getOnlinePlayers().size() != 1) {
            Msg.send(p, "<red>Solo practice is only for when you're alone in the lobby.");
            return;
        }
        solo = true;
        menuShown = true; // opened right here, tickLobby must not open it again
        Msg.send(p, "<green>Solo practice is on! <gray>Choose a seed. <dark_gray>(/dueley solo again to turn it off)");
        Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 2f);
        openMainMenu(p);
    }

    /** Turn solo practice off in the lobby: close seed menus, cancel a custom seed being typed, tell everyone. */
    private void soloOff(String message) {
        solo = false;
        menuShown = false; // with 2+ players online, tickLobby re-opens the menu for everyone
        customSeedTyper = null;
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.getOpenInventory().getTopInventory().getHolder() instanceof SeedMenu) {
                other.closeInventory();
            }
        }
        if (message != null) {
            Msg.broadcast(message);
        }
    }

    /** Same rules as the vanilla "Seed for the World Generator" box. */
    static long parseSeed(String input) {
        if (input.isEmpty()) {
            return ThreadLocalRandom.current().nextLong();
        }
        try {
            return Long.parseLong(input);
        } catch (NumberFormatException e) {
            return input.hashCode();
        }
    }

    // ═════════════════════════ generation ═════════════════════════

    private void beginGame(Player chooser, SeedMode mode, long chosenSeed, GoodStructure structure) {
        List<Player> lobby = lobbyPlayersInOrder();
        int needed = neededPlayers();
        if (lobby.size() < needed) {
            return;
        }
        participants.clear();
        for (int i = 0; i < needed; i++) {
            participants.add(lobby.get(i).getUniqueId());
        }
        state = GameState.GENERATING;
        seedMode = mode;
        seed = chosenSeed;
        goodStructure = structure;
        structureSpot = null;
        spawnOverride = null;
        customSeedTyper = null;
        worldPrefix = WorldService.GAME_PREFIX + Long.toString(System.currentTimeMillis() % 100_000_000L, 36) + "_";

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof SeedMenu) {
                p.closeInventory();
            }
            Msg.title(p, "<aqua><b>" + mode.displayName(), structure == null
                    ? "<gray>Generating your worlds..."
                    : "<gray>Searching for a <white>" + structure.displayName() + "</white> seed...", 5, 60, 10);
            Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        }
        Msg.broadcast("<white>" + chooser.getName() + "</white> chose <aqua>" + mode.displayName()
                + (structure != null ? " <gray>(" + structure.displayName() + ")" : "") + "<gray>!");
        if (solo) {
            Msg.broadcast("<gray>Solo practice: <white>" + lobby.get(0).getName());
        } else {
            Msg.broadcast("<gray>Match: <white>" + lobby.get(0).getName() + " <red>vs</red> " + lobby.get(1).getName());
        }

        generationSteps.clear();
        if (mode == SeedMode.GOOD) {
            searchState = new SearchState();
            generationSteps.add(this::searchStep);
        } else {
            generationSteps.add(() -> {
                generationStatus = "Generating " + playerName(0) + "'s world...";
                World w = worlds.createSurvivalWorld(worldPrefix + "p1", seed, World.Environment.NORMAL, difficulty);
                registerOverworld(0, w);
            });
        }
        generationSteps.add(() -> createRemainingWorlds());
        generationTask = Bukkit.getScheduler().runTaskTimer(plugin, this::runGenerationStep, 10L, 2L);
    }

    private void runGenerationStep() {
        Runnable step = generationSteps.poll();
        if (step == null) {
            generationTask.cancel();
            generationTask = null;
            finishGeneration();
            return;
        }
        try {
            step.run();
        } catch (RuntimeException e) {
            plugin.getLogger().severe("World generation failed: " + e);
            e.printStackTrace();
            Msg.broadcast("<red>World generation failed: " + e.getMessage());
            cancelGeneration();
            resetToLobby();
        }
    }

    /** Called after the first overworld exists: queue nether, player 2 worlds (not in solo practice) and the arena. */
    private void createRemainingWorlds() {
        if (netherEnabled) {
            generationSteps.add(() -> {
                generationStatus = "Generating " + playerName(0) + "'s nether...";
                registerNether(0, worlds.createSurvivalWorld(worldPrefix + "p1_nether", seed, World.Environment.NETHER, difficulty));
            });
        }
        if (!solo) {
            generationSteps.add(() -> {
                generationStatus = "Generating " + playerName(1) + "'s world...";
                registerOverworld(1, worlds.createSurvivalWorld(worldPrefix + "p2", seed, World.Environment.NORMAL, difficulty));
            });
            if (netherEnabled) {
                generationSteps.add(() -> {
                    generationStatus = "Generating " + playerName(1) + "'s nether...";
                    registerNether(1, worlds.createSurvivalWorld(worldPrefix + "p2_nether", seed, World.Environment.NETHER, difficulty));
                });
            }
        }
        generationSteps.add(() -> {
            generationStatus = "Building the arena...";
            arena = worlds.createArena(worldPrefix + "arena");
            gameWorlds.add(arena);
        });
    }

    private final class SearchState {
        int attempt;
        World best;
        long bestSeed;
        Location bestSpot;
        double bestDistance = Double.MAX_VALUE;
    }

    private SearchState searchState;

    /** One attempt at finding a seed with the structure near spawn. Re-queues itself until found. */
    private void searchStep() {
        SearchState s = searchState;
        s.attempt++;
        generationStatus = "Searching for a " + goodStructure.displayName() + " seed... <gray>(try " + s.attempt + "/" + goodMaxAttempts + ")";
        actionBarAll("<aqua>⚙ " + generationStatus);
        long candidate = ThreadLocalRandom.current().nextLong();
        World w = worlds.createSurvivalWorld(worldPrefix + "p1_" + s.attempt, candidate, World.Environment.NORMAL, difficulty);
        Location origin = w.getSpawnLocation();
        int radiusChunks = goodMaxDistance / 16 + 4;
        Location found = null;
        double dist = Double.MAX_VALUE;
        for (Structure structure : goodStructure.structures()) {
            StructureSearchResult r = w.locateNearestStructure(origin, structure, radiusChunks, false);
            if (r == null) {
                continue;
            }
            double d = horizontalDistance(origin, r.getLocation());
            if (d < dist) {
                dist = d;
                found = r.getLocation();
            }
        }
        plugin.getLogger().info("Good-seed try " + s.attempt + ": seed " + candidate + " -> "
                + (found == null ? "no " + goodStructure.displayName() : goodStructure.displayName() + " at " + (int) dist + " blocks"));

        if (found != null && dist < s.bestDistance) {
            if (s.best != null) {
                worlds.deleteWorld(s.best);
            }
            s.best = w;
            s.bestSeed = candidate;
            s.bestSpot = found;
            s.bestDistance = dist;
        } else {
            worlds.deleteWorld(w);
        }

        boolean good = s.bestDistance <= goodMaxDistance;
        if (good || s.attempt >= goodMaxAttempts) {
            if (s.best == null) {
                // nothing found at all: fall back to a plain random seed
                Msg.broadcast("<yellow>Couldn't find a " + goodStructure.displayName() + " seed, using a random seed instead.");
                seed = candidate;
                goodStructure = null;
                generationSteps.addFirst(() -> registerOverworld(0,
                        worlds.createSurvivalWorld(worldPrefix + "p1", seed, World.Environment.NORMAL, difficulty)));
                return;
            }
            seed = s.bestSeed;
            structureSpot = s.bestSpot.clone();
            structureSpot.setWorld(null);
            structureDistance = s.bestDistance;
            if (!good) {
                // nothing close enough: move the spawn point towards the structure instead
                Location moved = worlds.findSpawnNear(s.best, s.bestSpot, s.best.getSpawnLocation(), goodMaxDistance / 3.0);
                if (moved != null) {
                    s.best.setSpawnLocation(moved);
                    spawnOverride = moved.clone();
                    spawnOverride.setWorld(null);
                    structureDistance = horizontalDistance(moved, s.bestSpot);
                }
            }
            registerOverworld(0, s.best);
            Msg.broadcast("<green>Found a seed with a <white>" + goodStructure.displayName() + "</white> <white>"
                    + (int) structureDistance + "</white> blocks from spawn! <dark_gray>(" + s.attempt + " tries)");
        } else {
            generationSteps.addFirst(this::searchStep);
        }
    }

    private void registerOverworld(int index, World w) {
        if (spawnOverride != null) {
            w.setSpawnLocation(spawnOverride.getBlockX(), spawnOverride.getBlockY(), spawnOverride.getBlockZ());
        }
        overworlds.put(participants.get(index), w);
        gameWorlds.add(w);
    }

    private void registerNether(int index, World nether) {
        UUID id = participants.get(index);
        nethers.put(id, nether);
        gameWorlds.add(nether);
        World over = overworlds.get(id);
        portalLinks.put(over.getUID(), nether);
        portalLinks.put(nether.getUID(), over);
    }

    private void finishGeneration() {
        if (state != GameState.GENERATING) {
            return;
        }
        state = GameState.COUNTDOWN;
        countdown = startCountdown;
        paused = false;

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (isParticipant(p)) {
                placeParticipantAtSpawn(p);
            } else {
                makeSpectator(p);
            }
        }
        String seedText = Long.toString(seed);
        Msg.broadcast("<gray>Seed: <click:copy_to_clipboard:'" + seedText + "'><hover:show_text:'<gray>Click to copy'><aqua><u>"
                + seedText + "</u></aqua></hover></click> <dark_gray>(" + seedMode.displayName() + ")");
        if (structureSpot != null) {
            Msg.broadcast("<gold>Follow your compass to the <white>" + goodStructure.displayName() + "</white>!");
        }
        Msg.broadcast("<yellow>You have <white><b>" + gatherMinutes + " minutes</b></white> to gather gear. Starting in "
                + startCountdown + " seconds...");
    }

    private void placeParticipantAtSpawn(Player p) {
        World w = overworlds.get(p.getUniqueId());
        if (w == null) {
            return;
        }
        resetPlayer(p, GameMode.SURVIVAL);
        p.getEnderChest().clear();
        p.setRespawnLocation(null, true);
        p.teleportAsync(worlds.safeSpawn(w)).thenRun(() -> {
            if (structureSpot != null) {
                p.getInventory().addItem(trackerCompass(w));
            }
        });
    }

    private ItemStack trackerCompass(World w) {
        ItemStack compass = new ItemStack(Material.COMPASS);
        CompassMeta meta = (CompassMeta) compass.getItemMeta();
        Location target = structureSpot.clone();
        target.setWorld(w);
        meta.setLodestone(target);
        meta.setLodestoneTracked(false);
        meta.displayName(Msg.item("<gold><b>" + goodStructure.displayName() + " Tracker"));
        meta.lore(List.of(
                Msg.item("<gray>Points to the <white>" + goodStructure.displayName() + "</white>."),
                Msg.item("<gray>About <white>" + (int) structureDistance + "</white> blocks from spawn."),
                Msg.item("<dark_gray>Works in your overworld.")));
        compass.setItemMeta(meta);
        return compass;
    }

    private void cancelGeneration() {
        if (generationTask != null) {
            generationTask.cancel();
            generationTask = null;
        }
        generationSteps.clear();
        if (searchState != null && searchState.best != null && !gameWorlds.contains(searchState.best)) {
            gameWorlds.add(searchState.best);
        }
        searchState = null;
    }

    // ═════════════════════════ phases ═════════════════════════

    private void startGather() {
        state = GameState.GATHER;
        remaining = gatherMinutes * 60;
        paused = false;
        resumeCountdown = -1;
        freeze(false);
        for (Player p : Bukkit.getOnlinePlayers()) {
            Msg.title(p, "<green><b>GO!", "<gray>You have <white>" + gatherMinutes + " minutes</white> to get gear", 0, 40, 10);
            Msg.sound(p, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1.2f);
        }
        Msg.broadcast("<green><b>GO!</b> <gray>Gather your gear, the fight starts in <white>" + gatherMinutes + " minutes</white>.");
    }

    private void pause() {
        paused = true;
        resumeCountdown = -1;
        freeze(true);
        Msg.broadcast("<red>" + missingNames() + " left — the game is <b>paused</b> until they come back.");
    }

    private void unpause() {
        paused = false;
        resumeCountdown = -1;
        freeze(false);
        for (Player p : participantsOnline()) {
            Msg.title(p, "<green><b>GO!", "<gray>The game continues", 0, 30, 10);
            Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 2f);
        }
    }

    private void startFight() {
        state = GameState.FIGHT;
        fightSeconds = 0;
        freeze(false);

        WorldBorder wb = arena.getWorldBorder();
        wb.setCenter(0.5, 0.5);
        wb.setSize(arenaStart);
        wb.setDamageBuffer(0);
        wb.setDamageAmount(borderDamage);
        wb.setWarningDistance(4);
        wb.setWarningTimeTicks(Tick.tick().fromDuration(Duration.ofSeconds(10)));
        wb.changeSize(arenaEnd, Tick.tick().fromDuration(Duration.ofMinutes(shrinkMinutes)));

        double half = arenaStart / 2 - 10;
        int y = ArenaGenerator.FLOOR_Y + 1;
        Location[] spawns = {
                new Location(arena, 0.5 - half, y, 0.5, -90f, 0f), // faces east
                new Location(arena, 0.5 + half, y, 0.5, 90f, 0f)   // faces west
        };
        for (int i = 0; i < participants.size(); i++) {
            Player p = Bukkit.getPlayer(participants.get(i));
            if (p == null) {
                continue;
            }
            if (p.isDead()) {
                p.spigot().respawn();
            }
            p.closeInventory();
            p.teleport(spawns[i]);
            healFully(p);
            p.setGameMode(GameMode.SURVIVAL);
            Msg.title(p, "<red><b>FIGHT!", "<gray>Last one standing wins", 0, 50, 15);
            Msg.sound(p, Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 1f);
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!isParticipant(p)) {
                makeSpectator(p);
            }
        }
        Msg.broadcast("<red><b>FIGHT!</b> <gray>Health and hunger were reset. The border shrinks from <white>"
                + (int) arenaStart + "×" + (int) arenaStart + "</white> to <white>" + (int) arenaEnd + "×" + (int) arenaEnd
                + "</white> over <white>" + shrinkMinutes + " minutes</white>.");

        List<Player> online = participantsOnline();
        if (solo) {
            if (online.isEmpty()) {
                endPractice(PracticeEnd.LEFT);
            }
        } else if (online.size() < PLAYERS) {
            endGame(online.isEmpty() ? null : online.get(0), "their opponent is offline");
        }
    }

    /** Switch to ENDED and stop the border. Returns false when the game already ended (or there is none). */
    private boolean enterEnded() {
        if (state == GameState.ENDED || state == GameState.LOBBY) {
            return false;
        }
        state = GameState.ENDED;
        endCountdown = returnSeconds;
        if (arena != null) {
            WorldBorder wb = arena.getWorldBorder();
            wb.setSize(wb.getSize()); // stop shrinking
        }
        return true;
    }

    /** End of a 1v1 fight. */
    private void endGame(Player winner, String reason) {
        if (!enterEnded()) {
            return;
        }
        String winnerName = winner == null ? "Nobody" : winner.getName();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (winner != null && p.equals(winner)) {
                Msg.title(p, "<gold><b>VICTORY!", "<yellow>You won the fight!", 5, 80, 20);
                Msg.sound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            } else if (isParticipant(p)) {
                Msg.title(p, "<red><b>DEFEAT", "<white>" + winnerName + "</white> <gray>won the fight", 5, 80, 20);
                Msg.sound(p, Sound.ENTITY_WITHER_DEATH, 0.5f, 1f);
            } else {
                Msg.title(p, "<gold><b>" + winnerName + " wins!", "<gray>" + reason, 5, 80, 20);
            }
        }
        Msg.broadcast("<gold><b>" + winnerName + "</b> won the fight! <gray>(" + reason + ")");
        Msg.broadcast("<gray>Back to the lobby in " + returnSeconds + " seconds...");
        if (winner != null) {
            winner.setInvulnerable(true);
            for (int i = 0; i < 4; i++) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (winner.isOnline() && state == GameState.ENDED) {
                        launchFirework(winner.getLocation());
                    }
                }, i * 15L);
            }
        }
    }

    /** How a solo practice fight ended. */
    private enum PracticeEnd { DIED, SURVIVED, LEFT }

    /** End of a solo practice fight (there is no winner). */
    private void endPractice(PracticeEnd how) {
        if (!enterEnded()) {
            return;
        }
        String name = playerName(0);
        String lasted = Msg.clock(fightSeconds);
        String title;
        String ownSubtitle;
        String othersSubtitle;
        String broadcast;
        switch (how) {
            case SURVIVED -> {
                title = "<gold><b>PRACTICE COMPLETE";
                ownSubtitle = "<yellow>You survived the arena!";
                othersSubtitle = "<white>" + name + "</white> <gray>survived the arena!";
                broadcast = "<gold><b>PRACTICE COMPLETE!</b> <white>" + name + "</white> <gray>survived the arena!";
            }
            case DIED -> {
                title = "<red><b>PRACTICE OVER";
                ownSubtitle = "<gray>You lasted <white>" + lasted + "</white> in the arena";
                othersSubtitle = "<white>" + name + "</white> <gray>lasted <white>" + lasted + "</white> in the arena";
                broadcast = "<red><b>PRACTICE OVER!</b> <white>" + name + "</white> <gray>lasted <white>" + lasted + "</white> in the arena.";
            }
            default -> {
                title = "<red><b>PRACTICE OVER";
                ownSubtitle = "<gray>You left the arena";
                othersSubtitle = "<white>" + name + "</white> <gray>left the arena";
                broadcast = "<red><b>PRACTICE OVER!</b> <white>" + name + "</white> <gray>left the game.";
            }
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean own = isParticipant(p);
            Msg.title(p, title, own ? ownSubtitle : othersSubtitle, 5, 80, 20);
            if (own) {
                Msg.sound(p, how == PracticeEnd.SURVIVED ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_WITHER_DEATH,
                        how == PracticeEnd.SURVIVED ? 1f : 0.5f, 1f);
            }
        }
        Msg.broadcast(broadcast);
        Msg.broadcast("<gray>Back to the lobby in " + returnSeconds + " seconds...");
        if (how == PracticeEnd.SURVIVED) {
            for (Player p : participantsOnline()) {
                p.setInvulnerable(true);
                for (int i = 0; i < 4; i++) {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (p.isOnline() && state == GameState.ENDED) {
                            launchFirework(p.getLocation());
                        }
                    }, i * 15L);
                }
            }
        }
    }

    private void resetToLobby() {
        cancelGeneration();
        state = GameState.LOBBY;
        solo = false;
        freeze(true);
        menuShown = false;
        customSeedTyper = null;
        paused = false;
        for (Player p : Bukkit.getOnlinePlayers()) {
            sendToLobby(p);
        }
        List<World> toDelete = new ArrayList<>(gameWorlds);
        gameWorlds.clear();
        overworlds.clear();
        nethers.clear();
        portalLinks.clear();
        participants.clear();
        arena = null;
        structureSpot = null;
        spawnOverride = null;
        goodStructure = null;
        Bukkit.getScheduler().runTaskLater(plugin, () -> toDelete.forEach(worlds::deleteWorld), 20L);
    }

    /** Admin: abort whatever is happening. */
    public void stop() {
        Msg.broadcast("<red>The game was stopped by an admin.");
        resetToLobby();
    }

    /** Admin: set remaining gather time. */
    public boolean setRemaining(int seconds) {
        if (state != GameState.GATHER) {
            return false;
        }
        remaining = Math.max(1, seconds) + 1; // the next tick shows exactly `seconds`
        return true;
    }

    /** Result of {@link #forwardTimer}: {@code message} is MiniMessage text for the sender (without prefix). */
    public record ForwardResult(boolean ok, String message) {
    }

    /**
     * Admin/testing: fast-forward the gather timer, or the arena border while fighting.
     * Gather: the next tick lands exactly on the new value (so warnings still fire); reaching 0 starts the fight on the next tick.
     */
    public ForwardResult forwardTimer(int seconds) {
        String label = seconds % 60 == 0
                ? (seconds / 60) + (seconds == 60 ? " minute" : " minutes")
                : seconds + (seconds == 1 ? " second" : " seconds");
        if (state == GameState.GATHER) {
            if (paused || !allParticipantsOnline()) {
                return new ForwardResult(false, "<red>The game is paused (a player is offline), can't forward the timer.");
            }
            int target = remaining - seconds;
            if (target <= 0) {
                remaining = 1; // the next tick decrements to 0 and starts the fight
                return new ForwardResult(true, "<green>Timer forwarded " + label + " → the fight starts now.");
            }
            remaining = target + 1; // the next tick decrements first, so it shows exactly `target`
            return new ForwardResult(true, "<green>Timer forwarded " + label + " → " + Msg.clock(target) + " left");
        }
        if (state == GameState.FIGHT && arena != null) {
            int total = shrinkMinutes * 60;
            fightSeconds += seconds;
            int shrinkLeft = total - fightSeconds;
            WorldBorder wb = arena.getWorldBorder();
            int shown;
            if (shrinkLeft > 0) {
                double size = arenaStart + (arenaEnd - arenaStart) * ((double) fightSeconds / total);
                wb.setSize(size);
                wb.changeSize(arenaEnd, Tick.tick().fromDuration(Duration.ofSeconds(shrinkLeft)));
                shown = (int) Math.ceil(size);
                return new ForwardResult(true, "<green>Border forwarded " + label + " → " + shown + "×" + shown
                        + ", shrinks to " + (int) arenaEnd + "×" + (int) arenaEnd + " in " + Msg.clock(shrinkLeft));
            }
            wb.setSize(arenaEnd);
            shown = (int) Math.ceil(arenaEnd);
            return new ForwardResult(true, "<green>Border forwarded " + label + " → " + shown + "×" + shown + ", final size.");
        }
        return new ForwardResult(false, "<red>Only works while gathering or fighting.");
    }

    // ═════════════════════════ events from listeners ═════════════════════════

    public void onJoin(Player p) {
        joinOrder.remove(p.getUniqueId());
        joinOrder.add(p.getUniqueId());
        switch (state) {
            case LOBBY, GENERATING -> {
                sendToLobby(p);
                if (state == GameState.LOBBY && solo && Bukkit.getOnlinePlayers().size() > 1) {
                    // a second player turns solo practice off; tickLobby then opens the menu for both (normal 1v1 flow)
                    soloOff("<yellow>" + p.getName() + " joined — solo practice is off. <gray>Choose a seed for a 1v1!");
                }
            }
            case COUNTDOWN, GATHER -> {
                if (isParticipant(p)) {
                    World own = overworlds.get(p.getUniqueId());
                    if (!isOwnWorld(p, p.getWorld()) && own != null) {
                        if (state == GameState.COUNTDOWN) {
                            placeParticipantAtSpawn(p);
                        } else {
                            p.setGameMode(GameMode.SURVIVAL);
                            p.teleportAsync(worlds.safeSpawn(own));
                        }
                    }
                    Msg.send(p, "<green>Welcome back!");
                } else {
                    makeSpectator(p);
                }
            }
            case FIGHT, ENDED -> makeSpectator(p);
        }
    }

    public void onQuit(Player p) {
        joinOrder.remove(p.getUniqueId());
        if (p.getUniqueId().equals(customSeedTyper)) {
            customSeedTyper = null;
        }
        // evaluate once the player is really gone
        Bukkit.getScheduler().runTask(plugin, () -> {
            switch (state) {
                case LOBBY -> {
                    if (solo && Bukkit.getOnlinePlayers().isEmpty()) {
                        solo = false; // the solo player left
                    }
                    if (Bukkit.getOnlinePlayers().size() < neededPlayers()) {
                        menuShown = false;
                        for (Player other : Bukkit.getOnlinePlayers()) {
                            if (other.getOpenInventory().getTopInventory().getHolder() instanceof SeedMenu) {
                                other.closeInventory();
                            }
                        }
                    }
                }
                case GATHER -> {
                    if (!allParticipantsOnline() && !paused) {
                        pause();
                    }
                }
                case FIGHT -> {
                    if (isParticipant(p) && solo) {
                        endPractice(PracticeEnd.LEFT);
                    } else if (isParticipant(p)) {
                        List<Player> left = participantsOnline();
                        endGame(left.isEmpty() ? null : left.get(0), p.getName() + " left the game");
                    }
                }
                default -> {
                }
            }
        });
    }

    public void onDeath(Player p) {
        if (state == GameState.FIGHT && isParticipant(p)) {
            if (solo) {
                endPractice(PracticeEnd.DIED);
            } else {
                Player winner = participantsOnline().stream().filter(o -> !o.equals(p)).findFirst().orElse(null);
                endGame(winner, p.getName() + " was defeated");
            }
            // skip the death screen: respawn straight into spectator mode to watch the winner
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline() && p.isDead()) {
                    p.spigot().respawn();
                }
            }, 2L);
        }
    }

    /** Where a player should respawn, or null to keep the vanilla location. */
    public Location respawnLocation(Player p, boolean bedOrAnchor, Location vanilla) {
        switch (state) {
            case LOBBY, GENERATING -> {
                return worlds.lobbySpawn();
            }
            case COUNTDOWN, GATHER -> {
                if (!isParticipant(p)) {
                    return null;
                }
                World own = overworlds.get(p.getUniqueId());
                if (bedOrAnchor && vanilla != null && isOwnWorld(p, vanilla.getWorld())) {
                    return null;
                }
                return own == null ? null : worlds.safeSpawn(own);
            }
            case FIGHT, ENDED -> {
                if (arena != null) {
                    Bukkit.getScheduler().runTask(plugin, () -> p.setGameMode(GameMode.SPECTATOR));
                    return new Location(arena, 0.5, ArenaGenerator.FLOOR_Y + 6, 0.5);
                }
                return worlds.lobbySpawn();
            }
        }
        return null;
    }

    /**
     * Where a nether portal in {@code from} should lead, or null when the portal should do nothing.
     * Returns {@code from} itself when Dueley doesn't manage that world.
     */
    public Location netherPortalTarget(Location from) {
        World w = from.getWorld();
        if (!isManagedWorld(w)) {
            return from;
        }
        World target = portalLinks.get(w.getUID());
        if (target == null || state != GameState.GATHER) {
            return null;
        }
        double scale = w.getEnvironment() == World.Environment.NETHER ? 8.0 : 1.0 / 8.0;
        double y = Math.max(target.getMinHeight() + 8, Math.min(from.getY(), target.getEnvironment() == World.Environment.NETHER ? 120 : target.getMaxHeight() - 10));
        return new Location(target, from.getX() * scale, y, from.getZ() * scale, from.getYaw(), from.getPitch());
    }

    public boolean isManagedWorld(World w) {
        return w != null && (gameWorlds.contains(w) || (searchState != null && Objects.equals(searchState.best, w)));
    }

    // ═════════════════════════ queries ═════════════════════════

    public GameState state() {
        return state;
    }

    public boolean isParticipant(Player p) {
        return participants.contains(p.getUniqueId());
    }

    public boolean isLobby(World w) {
        return w != null && w.getKey().equals(WorldService.LOBBY_KEY);
    }

    /** Player can't walk (start countdown, or paused while the opponent is offline). */
    public boolean isLocked(Player p) {
        if (!isParticipant(p)) {
            return false;
        }
        return state == GameState.COUNTDOWN || (state == GameState.GATHER && paused);
    }

    /** Player can't take damage, get hungry, break blocks... */
    public boolean isProtected(Player p) {
        return isLobby(p.getWorld()) || isLocked(p) || state == GameState.ENDED;
    }

    public boolean isMenuItem(ItemStack item) {
        if (item == null || item.getType() != Material.NETHER_STAR || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(menuItemKey, PersistentDataType.BYTE);
    }

    public boolean toggleTimer(Player p) {
        if (!timerHidden.remove(p.getUniqueId())) {
            timerHidden.add(p.getUniqueId());
            p.sendActionBar(Msg.mm(""));
            return false;
        }
        return true;
    }

    public String describe() {
        StringBuilder sb = new StringBuilder("<gray>State: <white>" + state);
        sb.append("\n<gray>Mode: <white>").append(solo ? "Solo practice" : "1v1");
        if (state != GameState.LOBBY) {
            sb.append("\n<gray>Seed: <white>").append(seed).append(" <dark_gray>(").append(seedMode == null ? "?" : seedMode.displayName()).append(")");
            sb.append("\n<gray>Players: <white>").append(String.join(" vs ", participants.stream()
                    .map(id -> Objects.requireNonNullElse(Bukkit.getOfflinePlayer(id).getName(), "?")).toList()));
        }
        if (state == GameState.GATHER) {
            sb.append("\n<gray>Time left: <white>").append(Msg.clock(remaining)).append(paused ? " <red>(paused)" : "");
        }
        if (state == GameState.FIGHT && arena != null) {
            sb.append("\n<gray>Border: <white>").append((int) arena.getWorldBorder().getSize());
        }
        return sb.toString();
    }

    // ═════════════════════════ helpers ═════════════════════════

    private void freeze(boolean frozen) {
        if (Bukkit.getServerTickManager().isFrozen() != frozen) {
            Bukkit.getServerTickManager().setFrozen(frozen);
        }
    }

    private boolean isOwnWorld(Player p, World w) {
        if (w == null) {
            return false;
        }
        return w.equals(overworlds.get(p.getUniqueId())) || w.equals(nethers.get(p.getUniqueId()));
    }

    private boolean allParticipantsOnline() {
        return participantsOnline().size() == participants.size() && !participants.isEmpty();
    }

    private List<Player> participantsOnline() {
        List<Player> list = new ArrayList<>();
        for (UUID id : participants) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                list.add(p);
            }
        }
        return list;
    }

    private String missingNames() {
        List<String> names = new ArrayList<>();
        for (UUID id : participants) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) {
                names.add(Objects.requireNonNullElse(Bukkit.getOfflinePlayer(id).getName(), "a player"));
            }
        }
        return names.isEmpty() ? "nobody" : String.join(" & ", names);
    }

    private String playerName(int index) {
        if (index >= participants.size()) {
            return "?";
        }
        return Objects.requireNonNullElse(Bukkit.getOfflinePlayer(participants.get(index)).getName(), "Player " + (index + 1));
    }

    private List<Player> lobbyPlayersInOrder() {
        List<Player> list = new ArrayList<>();
        for (UUID id : joinOrder) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                list.add(p);
            }
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!list.contains(p)) {
                list.add(p);
            }
        }
        return list;
    }

    private void actionBarAll(String miniMessage) {
        var component = Msg.mm(miniMessage);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendActionBar(component);
        }
    }

    private static String countdownColor(int n) {
        return n <= 3 ? "<red>" : n <= 5 ? "<gold>" : "<green>";
    }

    private static double horizontalDistance(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void sendToLobby(Player p) {
        resetPlayer(p, GameMode.ADVENTURE);
        p.setRespawnLocation(null, true);
        p.getInventory().setItem(4, menuItem());
        p.getInventory().setHeldItemSlot(4);
        if (!isLobby(p.getWorld()) || p.getLocation().getY() < 90) {
            p.teleport(worlds.lobbySpawn());
        }
    }

    private void makeSpectator(Player p) {
        p.setGameMode(GameMode.SPECTATOR);
        World target = state == GameState.FIGHT || state == GameState.ENDED ? arena
                : participants.isEmpty() ? null : overworlds.get(participants.get(0));
        if (target != null && !target.equals(p.getWorld())) {
            p.teleportAsync(target == arena ? new Location(arena, 0.5, ArenaGenerator.FLOOR_Y + 15, 0.5) : worlds.safeSpawn(target));
        }
        Msg.send(p, "<gray>A game is in progress — you are spectating.");
    }

    private ItemStack menuItem() {
        ItemStack star = SeedMenu.item(Material.NETHER_STAR, "<aqua><b>Choose Seed <gray>(Right-click)",
                "<gray>Open the seed selection screen.");
        ItemMeta meta = star.getItemMeta();
        meta.getPersistentDataContainer().set(menuItemKey, PersistentDataType.BYTE, (byte) 1);
        star.setItemMeta(meta);
        return star;
    }

    private void resetPlayer(Player p, GameMode mode) {
        p.closeInventory();
        p.getInventory().clear();
        p.setItemOnCursor(null);
        p.setGameMode(mode);
        p.setLevel(0);
        p.setExp(0f);
        p.setTotalExperience(0);
        for (PotionEffect e : p.getActivePotionEffects()) {
            p.removePotionEffect(e.getType());
        }
        p.setInvulnerable(false);
        healFully(p);
    }

    private void healFully(Player p) {
        AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(max == null ? 20.0 : max.getValue());
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setExhaustion(0f);
        p.setFireTicks(0);
        p.setFreezeTicks(0);
        p.setFallDistance(0f);
        p.setRemainingAir(p.getMaximumAir());
    }

    private void launchFirework(Location at) {
        Firework fw = at.getWorld().spawn(at.clone().add(0, 1, 0), Firework.class);
        FireworkMeta meta = fw.getFireworkMeta();
        meta.addEffect(FireworkEffect.builder()
                .with(FireworkEffect.Type.BALL_LARGE)
                .withColor(Color.ORANGE, Color.YELLOW)
                .withFade(Color.RED)
                .trail(true).flicker(true).build());
        meta.setPower(1);
        fw.setFireworkMeta(meta);
    }
}
