package dev.dueley.fabric;

import com.mojang.datafixers.util.Pair;
import dev.dueley.fabric.menu.SeedMenu;
import dev.dueley.fabric.mixin.FoodDataAccessor;
import dev.dueley.fabric.world.ArenaGenerator;
import dev.dueley.fabric.world.Loc;
import dev.dueley.fabric.world.WorldService;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Runs the whole Dueley match: lobby → seed choice → world generation → countdown → gather → fight → lobby. */
public final class GameManager {
    public static final int PLAYERS = 2;
    /** Solo practice: how long the player must survive at the final border size to complete it. */
    public static final int SOLO_SURVIVE_SECONDS = 60;
    /** Custom data key that marks the Nether Star menu item (the plugin's persistent-data key). */
    public static final String MENU_ITEM_KEY = "dueley:seed_menu_item";

    private final MinecraftServer server;
    private final Scheduler scheduler;
    private final WorldService worlds;

    private GameState state = GameState.LOBBY;
    /**
     * Solo practice mode: one participant instead of {@link #PLAYERS}. Only toggled in LOBBY (by /dueley solo, or
     * turned off when a second player joins) and always reset to false when the game returns to the lobby, so it is
     * constant for the whole game.
     */
    private boolean solo;
    private final List<UUID> joinOrder = new ArrayList<>();
    private final List<UUID> participants = new ArrayList<>();
    private final Map<UUID, ServerLevel> overworlds = new HashMap<>();
    private final Map<UUID, ServerLevel> nethers = new HashMap<>();
    private final Map<ResourceKey<Level>, ServerLevel> portalLinks = new HashMap<>(); // world -> linked world
    private final List<ServerLevel> gameWorlds = new ArrayList<>();
    private final Set<UUID> timerHidden = new HashSet<>();
    /** Last known name of every player (Bukkit.getOfflinePlayer(id).getName()). */
    private final Map<UUID, String> names = new HashMap<>();
    private ServerLevel arena;

    // seed choice
    private UUID customSeedTyper;
    private boolean menuShown;
    private long seed;
    private SeedMode seedMode;
    private GoodStructure goodStructure;
    private Vec3 structureSpot; // world-less x/y/z, same in every copy of the seed
    private double structureDistance;
    private Vec3 spawnOverride; // world-less; set when the spawn was moved closer to the structure

    // generation
    private Scheduler.Task generationTask;
    private final Deque<Runnable> generationSteps = new ArrayDeque<>();
    private String generationStatus = "";
    private String worldPrefix = "";

    // timers
    private Scheduler.Task ticker;
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

    public GameManager(MinecraftServer server, Scheduler scheduler, WorldService worlds) {
        this.server = server;
        this.scheduler = scheduler;
        this.worlds = worlds;
        loadSettings();
    }

    // ═════════════════════════ lifecycle ═════════════════════════

    public void start() {
        worlds.sweepLeftovers();
        worlds.lobby();
        freeze(true);
        for (ServerPlayer p : online()) {
            joinOrder.add(p.getUUID());
            names.put(p.getUUID(), name(p));
            sendToLobby(p);
        }
        ticker = scheduler.runTimer(20L, 20L, this::tick);
    }

    public void shutdown() {
        if (ticker != null) {
            ticker.cancel();
        }
        cancelGeneration();
        freeze(false);
    }

    private void loadSettings() {
        DueleyConfig c = DueleyConfig.load();
        gatherMinutes = Math.max(1, c.getInt("gather-minutes", 60));
        startCountdown = Math.max(1, c.getInt("start-countdown-seconds", 10));
        fightCountdown = Math.max(1, c.getInt("fight-countdown-seconds", 10));
        warningMinutes = c.getIntegerList("warning-minutes");
        if (warningMinutes.isEmpty()) {
            warningMinutes = List.of(5, 4, 3, 2, 1);
        }
        try {
            difficulty = Difficulty.valueOf(c.getString("difficulty", "NORMAL").toUpperCase(Locale.ROOT));
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
        int online = onlineCount();
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
            ServerPlayer typer = player(customSeedTyper);
            String name = typer == null ? "Someone" : name(typer);
            actionBarAll("<light_purple>✎ " + name + " is typing a custom seed...");
        } else if (solo) {
            actionBarAll("<green>✔ Solo practice! <gray>Choose a seed — right-click the <aqua>Nether Star");
        } else {
            actionBarAll("<green>✔ " + online + "/" + PLAYERS + " players! <gray>Choose a seed — right-click the <aqua>Nether Star");
        }
        if (!menuShown) {
            menuShown = true;
            for (ServerPlayer p : online()) {
                Msg.sound(p, SoundEvents.NOTE_BLOCK_PLING, 1f, 2f);
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
            for (ServerPlayer p : participantsOnline()) {
                Msg.title(p, countdownColor(countdown) + "<b>" + countdown, "<gray>Get ready to gather!", 0, 25, 5);
                Msg.sound(p, SoundEvents.NOTE_BLOCK_HAT, 1f, 1f);
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
                for (ServerPlayer p : participantsOnline()) {
                    Msg.title(p, "<yellow>Resuming in " + resumeCountdown, solo ? "<gray>You're back!" : "<gray>Both players are back!", 0, 25, 5);
                    Msg.sound(p, SoundEvents.NOTE_BLOCK_HAT, 1f, 1f);
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
            for (ServerPlayer p : online()) {
                Msg.title(p, "<yellow><b>" + m + " " + unit, "<gray>until the <red>fight</red>!", 5, 50, 10);
                Msg.sound(p, SoundEvents.NOTE_BLOCK_PLING, 1f, 1f);
            }
            Msg.broadcast("<yellow>The fight starts in <white><b>" + m + " " + unit.toLowerCase(Locale.ROOT) + "</b></white>!");
        }
        if (remaining <= fightCountdown) {
            for (ServerPlayer p : online()) {
                Msg.title(p, countdownColor(remaining) + "<b>" + remaining, "<gray>until the <red>fight</red>!", 0, 25, 5);
                Msg.sound(p, SoundEvents.NOTE_BLOCK_HAT, 1f, remaining <= 3 ? 2f : 1f);
            }
            if (remaining == fightCountdown) {
                Msg.broadcast("<red>The fight starts in <white><b>" + remaining + " seconds</b></white>!");
            }
        }
        for (ServerPlayer p : online()) {
            if (!timerHidden.contains(p.getUUID())) {
                String color = remaining <= 60 ? "<red>" : remaining <= 300 ? "<yellow>" : "<white>";
                Msg.actionBar(p, Msg.mm("<gold>⌚ " + color + "<b>" + Msg.clock(remaining)
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
        for (ServerPlayer p : online()) {
            if (!timerHidden.contains(p.getUUID())) {
                Msg.actionBar(p, Msg.mm("<red>⚔ <b>FIGHT</b> <dark_gray>|</dark_gray> <gold>Border <white><b>"
                        + size + "×" + size + "</b></white> <dark_gray>|</dark_gray> " + tail));
            }
        }
        // solo practice has no opponent: it is complete once the player survives a while at the final border size
        if (solo && fightSeconds >= shrinkMinutes * 60 + SOLO_SURVIVE_SECONDS) {
            endPractice(PracticeEnd.SURVIVED);
        }
    }

    // ═════════════════════════ seed choice ═════════════════════════

    public void openMainMenu(ServerPlayer p) {
        if (state != GameState.LOBBY) {
            Msg.send(p, "<red>A game is already running.");
            return;
        }
        if (onlineCount() < neededPlayers()) {
            Msg.send(p, "<yellow>Waiting for a second player before you can choose a seed. " + SOLO_HINT);
            return;
        }
        List<ServerPlayer> lobby = lobbyPlayersInOrder();
        String line = solo
                ? "<white>" + name(p) + " <gray>(solo practice)"
                : lobby.size() >= 2
                ? "<white>" + name(lobby.get(0)) + " <red>vs</red> " + name(lobby.get(1))
                : "<white>" + lobby.size();
        SeedMenu.main(this, p, line, gatherMinutes).open();
    }

    public void openGoodMenu(ServerPlayer p) {
        if (state == GameState.LOBBY) {
            SeedMenu.good(this, p).open();
            Msg.sound(p, SoundEvents.UI_BUTTON_CLICK, 1f, 1f);
        }
    }

    private boolean canChoose(ServerPlayer p) {
        if (state != GameState.LOBBY) {
            Msg.send(p, "<red>A game is already running.");
            return false;
        }
        if (onlineCount() < neededPlayers()) {
            Msg.send(p, "<yellow>Waiting for a second player. " + SOLO_HINT);
            return false;
        }
        if (customSeedTyper != null && !customSeedTyper.equals(p.getUUID())) {
            Msg.send(p, "<yellow>Someone else is typing a custom seed.");
            return false;
        }
        return true;
    }

    public void chooseRandom(ServerPlayer p) {
        if (canChoose(p)) {
            loadSettings();
            beginGame(p, SeedMode.RANDOM, ThreadLocalRandom.current().nextLong(), null);
        }
    }

    public void chooseGood(ServerPlayer p, GoodStructure s) {
        if (canChoose(p)) {
            loadSettings();
            beginGame(p, SeedMode.GOOD, 0L, s);
        }
    }

    public void chooseCustom(ServerPlayer p) {
        if (!canChoose(p)) {
            return;
        }
        customSeedTyper = p.getUUID();
        p.closeContainer();
        Msg.send(p, "<light_purple>Type your seed in chat. <gray>(numbers or text — type <white>cancel</white> to go back)");
        Msg.title(p, "<light_purple><b>Custom Seed", "<gray>Type the seed in chat", 5, 60, 10);
        Msg.sound(p, SoundEvents.UI_BUTTON_CLICK, 1f, 1f);
    }

    public boolean isTypingSeed(ServerPlayer p) {
        return p.getUUID().equals(customSeedTyper);
    }

    /** Called on the main thread with whatever the player typed. */
    public void handleCustomSeed(ServerPlayer p, String text) {
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
        if (state != GameState.LOBBY || onlineCount() < neededPlayers()) {
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
    public void toggleSolo(ServerPlayer p) {
        if (state != GameState.LOBBY) {
            Msg.send(p, "<red>A game is already running.");
            return;
        }
        if (solo) {
            soloOff("<gray>Solo practice is off.");
            return;
        }
        if (onlineCount() != 1) {
            Msg.send(p, "<red>Solo practice is only for when you're alone in the lobby.");
            return;
        }
        solo = true;
        menuShown = true; // opened right here, tickLobby must not open it again
        Msg.send(p, "<green>Solo practice is on! <gray>Choose a seed. <dark_gray>(/dueley solo again to turn it off)");
        Msg.sound(p, SoundEvents.NOTE_BLOCK_PLING, 1f, 2f);
        openMainMenu(p);
    }

    /** Turn solo practice off in the lobby: close seed menus, cancel a custom seed being typed, tell everyone. */
    private void soloOff(String message) {
        solo = false;
        menuShown = false; // with 2+ players online, tickLobby re-opens the menu for everyone
        customSeedTyper = null;
        for (ServerPlayer other : online()) {
            if (SeedMenu.isOpen(other)) {
                other.closeContainer();
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

    private void beginGame(ServerPlayer chooser, SeedMode mode, long chosenSeed, GoodStructure structure) {
        List<ServerPlayer> lobby = lobbyPlayersInOrder();
        int needed = neededPlayers();
        if (lobby.size() < needed) {
            return;
        }
        participants.clear();
        for (int i = 0; i < needed; i++) {
            participants.add(lobby.get(i).getUUID());
        }
        state = GameState.GENERATING;
        seedMode = mode;
        seed = chosenSeed;
        goodStructure = structure;
        structureSpot = null;
        spawnOverride = null;
        customSeedTyper = null;
        worldPrefix = WorldService.GAME_PREFIX + Long.toString(System.currentTimeMillis() % 100_000_000L, 36) + "_";

        for (ServerPlayer p : online()) {
            if (SeedMenu.isOpen(p)) {
                p.closeContainer();
            }
            Msg.title(p, "<aqua><b>" + mode.displayName(), structure == null
                    ? "<gray>Generating your worlds..."
                    : "<gray>Searching for a <white>" + structure.displayName() + "</white> seed...", 5, 60, 10);
            Msg.sound(p, SoundEvents.PLAYER_LEVELUP, 1f, 1f);
        }
        Msg.broadcast("<white>" + name(chooser) + "</white> chose <aqua>" + mode.displayName()
                + (structure != null ? " <gray>(" + structure.displayName() + ")" : "") + "<gray>!");
        if (solo) {
            Msg.broadcast("<gray>Solo practice: <white>" + name(lobby.get(0)));
        } else {
            Msg.broadcast("<gray>Match: <white>" + name(lobby.get(0)) + " <red>vs</red> " + name(lobby.get(1)));
        }

        generationSteps.clear();
        if (mode == SeedMode.GOOD) {
            searchState = new SearchState();
            generationSteps.add(this::searchStep);
        } else {
            generationSteps.add(() -> {
                generationStatus = "Generating " + playerName(0) + "'s world...";
                ServerLevel w = worlds.createSurvivalWorld(worldPrefix + "p1", seed, false, difficulty);
                registerOverworld(0, w);
            });
        }
        generationSteps.add(() -> createRemainingWorlds());
        generationTask = scheduler.runTimer(10L, 2L, this::runGenerationStep);
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
            DueleyMod.LOGGER.error("World generation failed: " + e, e);
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
                registerNether(0, worlds.createSurvivalWorld(worldPrefix + "p1_nether", seed, true, difficulty));
            });
        }
        if (!solo) {
            generationSteps.add(() -> {
                generationStatus = "Generating " + playerName(1) + "'s world...";
                registerOverworld(1, worlds.createSurvivalWorld(worldPrefix + "p2", seed, false, difficulty));
            });
            if (netherEnabled) {
                generationSteps.add(() -> {
                    generationStatus = "Generating " + playerName(1) + "'s nether...";
                    registerNether(1, worlds.createSurvivalWorld(worldPrefix + "p2_nether", seed, true, difficulty));
                });
            }
        }
        generationSteps.add(() -> {
            generationStatus = "Building the arena...";
            arena = worlds.createArena(worldPrefix + "arena");
            gameWorlds.add(arena);
        });
    }

    private static final class SearchState {
        int attempt;
        ServerLevel best;
        long bestSeed;
        BlockPos bestSpot;
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
        ServerLevel w = worlds.createSurvivalWorld(worldPrefix + "p1_" + s.attempt, candidate, false, difficulty);
        BlockPos origin = worlds.spawnPoint(w);
        int radiusChunks = goodMaxDistance / 16 + 4;
        BlockPos found = null;
        double dist = Double.MAX_VALUE;
        for (ResourceKey<Structure> key : goodStructure.structures()) {
            Optional<Holder.Reference<Structure>> structure = server.registryAccess().lookupOrThrow(Registries.STRUCTURE).get(key);
            if (structure.isEmpty()) {
                continue;
            }
            Pair<BlockPos, Holder<Structure>> r = w.getChunkSource().getGenerator()
                    .findNearestMapStructure(w, HolderSet.direct(structure.get()), origin, radiusChunks, false);
            if (r == null) {
                continue;
            }
            double d = horizontalDistance(origin.getX(), origin.getZ(), r.getFirst().getX(), r.getFirst().getZ());
            if (d < dist) {
                dist = d;
                found = r.getFirst();
            }
        }
        DueleyMod.LOGGER.info("Good-seed try " + s.attempt + ": seed " + candidate + " -> "
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
                        worlds.createSurvivalWorld(worldPrefix + "p1", seed, false, difficulty)));
                return;
            }
            seed = s.bestSeed;
            structureSpot = Vec3.atLowerCornerOf(s.bestSpot);
            structureDistance = s.bestDistance;
            if (!good) {
                // nothing close enough: move the spawn point towards the structure instead
                Loc moved = worlds.findSpawnNear(s.best, s.bestSpot, worlds.spawnPoint(s.best), goodMaxDistance / 3.0);
                if (moved != null) {
                    worlds.setSpawnPoint(s.best, new BlockPos(moved.blockX(), moved.blockY(), moved.blockZ()));
                    spawnOverride = moved.pos();
                    structureDistance = horizontalDistance(moved.x(), moved.z(), s.bestSpot.getX(), s.bestSpot.getZ());
                }
            }
            registerOverworld(0, s.best);
            Msg.broadcast("<green>Found a seed with a <white>" + goodStructure.displayName() + "</white> <white>"
                    + (int) structureDistance + "</white> blocks from spawn! <dark_gray>(" + s.attempt + " tries)");
        } else {
            generationSteps.addFirst(this::searchStep);
        }
    }

    private void registerOverworld(int index, ServerLevel w) {
        if (spawnOverride != null) {
            worlds.setSpawnPoint(w, BlockPos.containing(spawnOverride));
        }
        overworlds.put(participants.get(index), w);
        gameWorlds.add(w);
    }

    private void registerNether(int index, ServerLevel nether) {
        UUID id = participants.get(index);
        nethers.put(id, nether);
        gameWorlds.add(nether);
        ServerLevel over = overworlds.get(id);
        portalLinks.put(over.dimension(), nether);
        portalLinks.put(nether.dimension(), over);
    }

    private void finishGeneration() {
        if (state != GameState.GENERATING) {
            return;
        }
        state = GameState.COUNTDOWN;
        countdown = startCountdown;
        paused = false;

        for (ServerPlayer p : online()) {
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

    private void placeParticipantAtSpawn(ServerPlayer p) {
        ServerLevel w = overworlds.get(p.getUUID());
        if (w == null) {
            return;
        }
        resetPlayer(p, GameType.SURVIVAL);
        p.getEnderChestInventory().clearContent();
        p.setRespawnPosition(null, false);
        worlds.safeSpawn(w).teleport(p);
        if (structureSpot != null) {
            p.getInventory().add(trackerCompass(w));
        }
    }

    private ItemStack trackerCompass(ServerLevel w) {
        ItemStack compass = new ItemStack(Items.COMPASS);
        compass.set(DataComponents.LODESTONE_TRACKER,
                new LodestoneTracker(Optional.of(GlobalPos.of(w.dimension(), BlockPos.containing(structureSpot))), false));
        compass.set(DataComponents.CUSTOM_NAME, Msg.item("<gold><b>" + goodStructure.displayName() + " Tracker"));
        compass.set(DataComponents.LORE, new ItemLore(List.of(
                Msg.item("<gray>Points to the <white>" + goodStructure.displayName() + "</white>."),
                Msg.item("<gray>About <white>" + (int) structureDistance + "</white> blocks from spawn."),
                Msg.item("<dark_gray>Works in your overworld."))));
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
        for (ServerPlayer p : online()) {
            Msg.title(p, "<green><b>GO!", "<gray>You have <white>" + gatherMinutes + " minutes</white> to get gear", 0, 40, 10);
            Msg.sound(p, SoundEvents.ENDER_DRAGON_GROWL, 0.6f, 1.2f);
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
        for (ServerPlayer p : participantsOnline()) {
            Msg.title(p, "<green><b>GO!", "<gray>The game continues", 0, 30, 10);
            Msg.sound(p, SoundEvents.NOTE_BLOCK_PLING, 1f, 2f);
        }
    }

    private void startFight() {
        state = GameState.FIGHT;
        fightSeconds = 0;
        freeze(false);

        WorldBorder wb = arena.getWorldBorder();
        wb.setCenter(0.5, 0.5);
        wb.setSize(arenaStart);
        wb.setSafeZone(0);
        wb.setDamagePerBlock(borderDamage);
        wb.setWarningBlocks(4);
        wb.setWarningTime(10 * 20);
        changeSize(wb, arenaEnd, shrinkMinutes * 60L * 20L);

        double half = arenaStart / 2 - 10;
        int y = ArenaGenerator.FLOOR_Y + 1;
        Loc[] spawns = {
                new Loc(arena, 0.5 - half, y, 0.5, -90f, 0f), // faces east
                new Loc(arena, 0.5 + half, y, 0.5, 90f, 0f)   // faces west
        };
        for (int i = 0; i < participants.size(); i++) {
            ServerPlayer p = player(participants.get(i));
            if (p == null) {
                continue;
            }
            if (p.isDeadOrDying()) {
                p = respawn(p);
            }
            p.closeContainer();
            spawns[i].teleport(p);
            healFully(p);
            p.setGameMode(GameType.SURVIVAL);
            Msg.title(p, "<red><b>FIGHT!", "<gray>Last one standing wins", 0, 50, 15);
            Msg.sound(p, SoundEvents.ENDER_DRAGON_GROWL, 1f, 1f);
        }
        for (ServerPlayer p : online()) {
            if (!isParticipant(p)) {
                makeSpectator(p);
            }
        }
        Msg.broadcast("<red><b>FIGHT!</b> <gray>Health and hunger were reset. The border shrinks from <white>"
                + (int) arenaStart + "×" + (int) arenaStart + "</white> to <white>" + (int) arenaEnd + "×" + (int) arenaEnd
                + "</white> over <white>" + shrinkMinutes + " minutes</white>.");

        List<ServerPlayer> online = participantsOnline();
        if (solo) {
            if (online.isEmpty()) {
                endPractice(PracticeEnd.LEFT);
            }
        } else if (online.size() < PLAYERS) {
            endGame(online.isEmpty() ? null : online.get(0), "their opponent is offline");
        }
    }

    /** WorldBorder#changeSize(newSize, ticks) from Bukkit. */
    private void changeSize(WorldBorder wb, double newSize, long ticks) {
        wb.lerpSizeBetween(wb.getSize(), newSize, ticks, arena.getGameTime());
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
    private void endGame(ServerPlayer winner, String reason) {
        if (!enterEnded()) {
            return;
        }
        String winnerName = winner == null ? "Nobody" : name(winner);
        for (ServerPlayer p : online()) {
            if (winner != null && p.getUUID().equals(winner.getUUID())) {
                Msg.title(p, "<gold><b>VICTORY!", "<yellow>You won the fight!", 5, 80, 20);
                Msg.sound(p, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            } else if (isParticipant(p)) {
                Msg.title(p, "<red><b>DEFEAT", "<white>" + winnerName + "</white> <gray>won the fight", 5, 80, 20);
                Msg.sound(p, SoundEvents.WITHER_DEATH, 0.5f, 1f);
            } else {
                Msg.title(p, "<gold><b>" + winnerName + " wins!", "<gray>" + reason, 5, 80, 20);
            }
        }
        Msg.broadcast("<gold><b>" + winnerName + "</b> won the fight! <gray>(" + reason + ")");
        Msg.broadcast("<gray>Back to the lobby in " + returnSeconds + " seconds...");
        if (winner != null) {
            winner.setInvulnerable(true);
            celebrate(winner.getUUID());
        }
    }

    /** 4 fireworks, 15 ticks apart, while the game is still ENDED. */
    private void celebrate(UUID id) {
        for (int i = 0; i < 4; i++) {
            scheduler.runLater(i * 15L, () -> {
                ServerPlayer p = player(id);
                if (p != null && state == GameState.ENDED) {
                    launchFirework(p.level(), p.getX(), p.getY(), p.getZ());
                }
            });
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
        for (ServerPlayer p : online()) {
            boolean own = isParticipant(p);
            Msg.title(p, title, own ? ownSubtitle : othersSubtitle, 5, 80, 20);
            if (own) {
                Msg.sound(p, how == PracticeEnd.SURVIVED ? SoundEvents.UI_TOAST_CHALLENGE_COMPLETE : SoundEvents.WITHER_DEATH,
                        how == PracticeEnd.SURVIVED ? 1f : 0.5f, 1f);
            }
        }
        Msg.broadcast(broadcast);
        Msg.broadcast("<gray>Back to the lobby in " + returnSeconds + " seconds...");
        if (how == PracticeEnd.SURVIVED) {
            for (ServerPlayer p : participantsOnline()) {
                p.setInvulnerable(true);
                celebrate(p.getUUID());
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
        for (ServerPlayer p : online()) {
            sendToLobby(p);
        }
        List<ServerLevel> toDelete = new ArrayList<>(gameWorlds);
        gameWorlds.clear();
        overworlds.clear();
        nethers.clear();
        portalLinks.clear();
        participants.clear();
        arena = null;
        structureSpot = null;
        spawnOverride = null;
        goodStructure = null;
        scheduler.runLater(20L, () -> toDelete.forEach(worlds::deleteWorld));
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
                changeSize(wb, arenaEnd, shrinkLeft * 20L);
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

    public void onJoin(ServerPlayer p) {
        names.put(p.getUUID(), name(p));
        joinOrder.remove(p.getUUID());
        joinOrder.add(p.getUUID());
        switch (state) {
            case LOBBY, GENERATING -> {
                sendToLobby(p);
                if (state == GameState.LOBBY && solo && onlineCount() > 1) {
                    // a second player turns solo practice off; tickLobby then opens the menu for both (normal 1v1 flow)
                    soloOff("<yellow>" + name(p) + " joined — solo practice is off. <gray>Choose a seed for a 1v1!");
                }
            }
            case COUNTDOWN, GATHER -> {
                if (isParticipant(p)) {
                    ServerLevel own = overworlds.get(p.getUUID());
                    if (!isOwnWorld(p, p.level()) && own != null) {
                        if (state == GameState.COUNTDOWN) {
                            placeParticipantAtSpawn(p);
                        } else {
                            p.setGameMode(GameType.SURVIVAL);
                            worlds.safeSpawn(own).teleport(p);
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

    public void onQuit(ServerPlayer p) {
        UUID id = p.getUUID();
        String quitName = name(p);
        joinOrder.remove(id);
        if (id.equals(customSeedTyper)) {
            customSeedTyper = null;
        }
        // evaluate once the player is really gone
        scheduler.run(() -> {
            switch (state) {
                case LOBBY -> {
                    if (solo && onlineCount() == 0) {
                        solo = false; // the solo player left
                    }
                    if (onlineCount() < neededPlayers()) {
                        menuShown = false;
                        for (ServerPlayer other : online()) {
                            if (SeedMenu.isOpen(other)) {
                                other.closeContainer();
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
                    if (participants.contains(id) && solo) {
                        endPractice(PracticeEnd.LEFT);
                    } else if (participants.contains(id)) {
                        List<ServerPlayer> left = participantsOnline();
                        endGame(left.isEmpty() ? null : left.get(0), quitName + " left the game");
                    }
                }
                default -> {
                }
            }
        });
    }

    public void onDeath(ServerPlayer p) {
        if (state == GameState.FIGHT && isParticipant(p)) {
            if (solo) {
                endPractice(PracticeEnd.DIED);
            } else {
                ServerPlayer winner = participantsOnline().stream().filter(o -> !o.getUUID().equals(p.getUUID())).findFirst().orElse(null);
                endGame(winner, name(p) + " was defeated");
            }
            // skip the death screen: respawn straight into spectator mode to watch the winner
            UUID id = p.getUUID();
            scheduler.runLater(2L, () -> {
                ServerPlayer now = player(id);
                if (now != null && now.isDeadOrDying()) {
                    respawn(now);
                }
            });
        }
    }

    /** Where a player should respawn, or null to keep the vanilla location. */
    public Loc respawnLocation(ServerPlayer p, boolean bedOrAnchor, ServerLevel vanillaLevel) {
        switch (state) {
            case LOBBY, GENERATING -> {
                return worlds.lobbySpawn();
            }
            case COUNTDOWN, GATHER -> {
                if (!isParticipant(p)) {
                    return null;
                }
                ServerLevel own = overworlds.get(p.getUUID());
                if (bedOrAnchor && vanillaLevel != null && isOwnWorld(p, vanillaLevel)) {
                    return null;
                }
                return own == null ? null : worlds.safeSpawn(own);
            }
            case FIGHT, ENDED -> {
                if (arena != null) {
                    UUID id = p.getUUID();
                    scheduler.run(() -> {
                        ServerPlayer now = player(id);
                        if (now != null) {
                            now.setGameMode(GameType.SPECTATOR);
                        }
                    });
                    return new Loc(arena, 0.5, ArenaGenerator.FLOOR_Y + 6, 0.5);
                }
                return worlds.lobbySpawn();
            }
        }
        return null;
    }

    /**
     * Where a nether portal in {@code from} should lead, or null when the portal should do nothing.
     * Returns a location in {@code from} itself when Dueley doesn't manage that world.
     */
    public Loc netherPortalTarget(Loc from) {
        ServerLevel w = from.level();
        if (!isManagedWorld(w)) {
            return from;
        }
        ServerLevel target = portalLinks.get(w.dimension());
        if (target == null || state != GameState.GATHER) {
            return null;
        }
        double scale = WorldService.isNether(w) ? 8.0 : 1.0 / 8.0;
        int maxHeight = target.getMaxY() + 1; // Bukkit's getMaxHeight is exclusive
        double y = Math.max(target.getMinY() + 8, Math.min(from.y(), WorldService.isNether(target) ? 120 : maxHeight - 10));
        return new Loc(target, from.x() * scale, y, from.z() * scale, from.yaw(), from.pitch());
    }

    public boolean isManagedWorld(ServerLevel w) {
        return w != null && (gameWorlds.contains(w) || (searchState != null && Objects.equals(searchState.best, w)));
    }

    // ═════════════════════════ queries ═════════════════════════

    public GameState state() {
        return state;
    }

    public boolean isParticipant(ServerPlayer p) {
        return participants.contains(p.getUUID());
    }

    public boolean isLobby(Level w) {
        return w != null && w.dimension().equals(WorldService.LOBBY_KEY);
    }

    /** Player can't walk (start countdown, or paused while the opponent is offline). */
    public boolean isLocked(ServerPlayer p) {
        if (!isParticipant(p)) {
            return false;
        }
        return state == GameState.COUNTDOWN || (state == GameState.GATHER && paused);
    }

    /** Player can't take damage, get hungry, break blocks... */
    public boolean isProtected(ServerPlayer p) {
        return isLobby(p.level()) || isLocked(p) || state == GameState.ENDED;
    }

    public boolean isMenuItem(ItemStack item) {
        if (item == null || !item.is(Items.NETHER_STAR)) {
            return false;
        }
        CustomData data = item.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().contains(MENU_ITEM_KEY);
    }

    public boolean toggleTimer(ServerPlayer p) {
        if (!timerHidden.remove(p.getUUID())) {
            timerHidden.add(p.getUUID());
            Msg.actionBar(p, Msg.mm(""));
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
                    .map(id -> Objects.requireNonNullElse(names.get(id), "?")).toList()));
        }
        if (state == GameState.GATHER) {
            sb.append("\n<gray>Time left: <white>").append(Msg.clock(remaining)).append(paused ? " <red>(paused)" : "");
        }
        if (state == GameState.FIGHT && arena != null) {
            sb.append("\n<gray>Border: <white>").append((int) arena.getWorldBorder().getSize());
        }
        return sb.toString();
    }

    public WorldService worlds() {
        return worlds;
    }

    // ═════════════════════════ helpers ═════════════════════════

    private void freeze(boolean frozen) {
        if (server.tickRateManager().isFrozen() != frozen) {
            server.tickRateManager().setFrozen(frozen);
        }
    }

    private List<ServerPlayer> online() {
        return new ArrayList<>(server.getPlayerList().getPlayers());
    }

    private int onlineCount() {
        return server.getPlayerList().getPlayerCount();
    }

    private ServerPlayer player(UUID id) {
        return server.getPlayerList().getPlayer(id);
    }

    public static String name(ServerPlayer p) {
        return p.getGameProfile().name();
    }

    /** The player after a respawn (the server replaces the player object when it respawns). */
    private ServerPlayer respawn(ServerPlayer p) {
        UUID id = p.getUUID();
        p.connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
        ServerPlayer now = player(id);
        return now != null ? now : p;
    }

    private boolean isOwnWorld(ServerPlayer p, ServerLevel w) {
        if (w == null) {
            return false;
        }
        return w.equals(overworlds.get(p.getUUID())) || w.equals(nethers.get(p.getUUID()));
    }

    private boolean allParticipantsOnline() {
        return participantsOnline().size() == participants.size() && !participants.isEmpty();
    }

    private List<ServerPlayer> participantsOnline() {
        List<ServerPlayer> list = new ArrayList<>();
        for (UUID id : participants) {
            ServerPlayer p = player(id);
            if (p != null) {
                list.add(p);
            }
        }
        return list;
    }

    private String missingNames() {
        List<String> names = new ArrayList<>();
        for (UUID id : participants) {
            if (player(id) == null) {
                names.add(Objects.requireNonNullElse(this.names.get(id), "a player"));
            }
        }
        return names.isEmpty() ? "nobody" : String.join(" & ", names);
    }

    private String playerName(int index) {
        if (index >= participants.size()) {
            return "?";
        }
        return Objects.requireNonNullElse(names.get(participants.get(index)), "Player " + (index + 1));
    }

    private List<ServerPlayer> lobbyPlayersInOrder() {
        List<ServerPlayer> list = new ArrayList<>();
        for (UUID id : joinOrder) {
            ServerPlayer p = player(id);
            if (p != null) {
                list.add(p);
            }
        }
        for (ServerPlayer p : online()) {
            if (!list.contains(p)) {
                list.add(p);
            }
        }
        return list;
    }

    private void actionBarAll(String miniMessage) {
        Component component = Msg.mm(miniMessage);
        for (ServerPlayer p : online()) {
            Msg.actionBar(p, component);
        }
    }

    private static String countdownColor(int n) {
        return n <= 3 ? "<red>" : n <= 5 ? "<gold>" : "<green>";
    }

    private static double horizontalDistance(double ax, double az, double bx, double bz) {
        double dx = ax - bx;
        double dz = az - bz;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void sendToLobby(ServerPlayer p) {
        resetPlayer(p, GameType.ADVENTURE);
        p.setRespawnPosition(null, false);
        p.getInventory().setItem(4, menuItem());
        p.getInventory().setSelectedSlot(4);
        p.connection.send(new ClientboundSetHeldSlotPacket(4));
        if (!isLobby(p.level()) || p.getY() < 90) {
            worlds.lobbySpawn().teleport(p);
        }
    }

    private void makeSpectator(ServerPlayer p) {
        p.setGameMode(GameType.SPECTATOR);
        ServerLevel target = state == GameState.FIGHT || state == GameState.ENDED ? arena
                : participants.isEmpty() ? null : overworlds.get(participants.get(0));
        if (target != null && !target.equals(p.level())) {
            (target == arena ? new Loc(arena, 0.5, ArenaGenerator.FLOOR_Y + 15, 0.5) : worlds.safeSpawn(target)).teleport(p);
        }
        Msg.send(p, "<gray>A game is in progress — you are spectating.");
    }

    private ItemStack menuItem() {
        ItemStack star = SeedMenu.item(Items.NETHER_STAR, "<aqua><b>Choose Seed <gray>(Right-click)",
                "<gray>Open the seed selection screen.");
        CompoundTag tag = new CompoundTag();
        tag.putByte(MENU_ITEM_KEY, (byte) 1);
        star.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return star;
    }

    private void resetPlayer(ServerPlayer p, GameType mode) {
        p.closeContainer();
        p.getInventory().clearContent();
        p.containerMenu.setCarried(ItemStack.EMPTY);
        p.setGameMode(mode);
        p.setExperienceLevels(0);
        p.setExperiencePoints(0);
        p.totalExperience = 0;
        p.removeAllEffects();
        p.setInvulnerable(false);
        healFully(p);
    }

    private void healFully(ServerPlayer p) {
        p.setHealth(p.getMaxHealth());
        p.getFoodData().setFoodLevel(20);
        p.getFoodData().setSaturation(20f);
        ((FoodDataAccessor) p.getFoodData()).dueley$setExhaustion(0f);
        p.setRemainingFireTicks(0);
        p.setTicksFrozen(0);
        p.resetFallDistance();
        p.setAirSupply(p.getMaxAirSupply());
    }

    private void launchFirework(ServerLevel level, double x, double y, double z) {
        ItemStack rocket = new ItemStack(Items.FIREWORK_ROCKET);
        rocket.set(DataComponents.FIREWORKS, new Fireworks(1, List.of(new FireworkExplosion(
                FireworkExplosion.Shape.LARGE_BALL,
                IntList.of(0xFFA500, 0xFFFF00), // Bukkit Color.ORANGE, Color.YELLOW
                IntList.of(0xFF0000),           // fade: Color.RED
                true, true))));
        level.addFreshEntity(new FireworkRocketEntity(level, x, y + 1, z, rocket));
    }
}
