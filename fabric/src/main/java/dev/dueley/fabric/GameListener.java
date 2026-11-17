package dev.dueley.fabric;

import dev.dueley.fabric.world.Loc;
import dev.dueley.fabric.world.WorldService;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Wires Fabric events (and the few mixins in {@code dev.dueley.fabric.mixin}) into the {@link GameManager}.
 * Same rules as the plugin's GameListener.
 */
public final class GameListener {
    private GameListener() {
    }

    private static GameManager game() {
        return DueleyMod.game();
    }

    static void register() {
        // ───────── join / quit ─────────
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (game() != null) {
                game().onJoin(handler.player);
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (game() != null) {
                game().onQuit(handler.player);
            }
        });

        // ───────── custom seed typed in chat ─────────
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            GameManager game = game();
            if (game == null || !game.isTypingSeed(sender)) {
                return true;
            }
            String text = message.signedContent();
            UUID id = sender.getUUID();
            DueleyMod.scheduler().run(() -> {
                ServerPlayer p = sender.level().getServer().getPlayerList().getPlayer(id);
                if (p != null) {
                    game.handleCustomSeed(p, text);
                }
            });
            return false;
        });

        // ───────── freeze / protection ─────────
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
                !(entity instanceof ServerPlayer p && game() != null && game().isProtected(p)));

        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) ->
                !(player instanceof ServerPlayer p && blocked(p)));

        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (!(player instanceof ServerPlayer p) || game() == null) {
                return InteractionResult.PASS;
            }
            if (game().isMenuItem(p.getItemInHand(hand))) {
                game().openMainMenu(p);
                return InteractionResult.FAIL;
            }
            return game().isLocked(p) ? InteractionResult.FAIL : InteractionResult.PASS;
        });

        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!(player instanceof ServerPlayer p) || game() == null) {
                return InteractionResult.PASS;
            }
            if (game().isLocked(p)) {
                p.containerMenu.sendAllDataToRemote();
                return InteractionResult.FAIL;
            }
            // BlockPlaceEvent: no block placing in the lobby (creative admins excepted)
            if (p.getItemInHand(hand).getItem() instanceof BlockItem && blocked(p)) {
                p.containerMenu.sendAllDataToRemote();
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
                player instanceof ServerPlayer p && game() != null && game().isLocked(p) ? InteractionResult.FAIL : InteractionResult.PASS);

        // ───────── death ─────────
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer p && game() != null) {
                game().onDeath(p);
            }
        });

        // ───────── lobby: fall back onto the platform ─────────
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            GameManager game = game();
            if (game == null) {
                return;
            }
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (game.isLobby(p.level()) && p.getY() < 60) {
                    new Loc(p.level(), 0.5, 100, 0.5, p.getYRot(), p.getXRot()).teleport(p);
                }
            }
        });
    }

    // ═════════════════════════ hooks used by the mixins ═════════════════════════

    /** Lobby, or locked by the countdown/pause; creative admins may still build. */
    public static boolean blocked(ServerPlayer p) {
        GameManager game = game();
        if (game == null) {
            return false;
        }
        if (p.gameMode() == GameType.CREATIVE && DueleyCommand.isAdmin(p.createCommandSourceStack())) {
            return false;
        }
        return game.isLobby(p.level()) || game.isLocked(p);
    }

    /** Hunger: a protected player's food level doesn't go down. */
    public static boolean noHunger(ServerPlayer p) {
        return game() != null && game().isProtected(p);
    }

    /**
     * PlayerMoveEvent: a locked player can't walk or jump (falling and looking around are fine).
     * Returns true when the move was refused and the player was put back.
     */
    public static boolean refuseMove(ServerPlayer p, ServerboundMovePlayerPacket packet) {
        GameManager game = game();
        if (game == null || !packet.hasPosition() || !game.isLocked(p)) {
            return false;
        }
        double toX = packet.getX(p.getX());
        double toY = packet.getY(p.getY());
        double toZ = packet.getZ(p.getZ());
        if (toX != p.getX() || toZ != p.getZ() || toY > p.getY()) {
            p.connection.teleport(p.getX(), p.getY(), p.getZ(), packet.getYRot(p.getYRot()), packet.getXRot(p.getXRot()));
            return true;
        }
        return false;
    }

    /** PlayerSwapHandItemsEvent: no swapping hands in the lobby. */
    public static boolean refuseSwap(ServerPlayer p) {
        return game() != null && game().isLobby(p.level());
    }

    /** InventoryClickEvent: the Nether Star can't be moved in the lobby (except in creative). */
    public static boolean refuseClick(ServerPlayer p, AbstractContainerMenu menu, int slotIndex) {
        GameManager game = game();
        if (game == null || !game.isLobby(p.level()) || p.gameMode() == GameType.CREATIVE) {
            return false;
        }
        if (slotIndex < 0 || slotIndex >= menu.slots.size()) {
            return false;
        }
        return game.isMenuItem(menu.getSlot(slotIndex).getItem());
    }

    /**
     * PlayerDropItemEvent: a blocked player keeps the item. Returns true when the drop was refused (the item is
     * back in the inventory).
     */
    public static boolean refuseDrop(ServerPlayer p, ItemStack stack, boolean thrownFromHand) {
        if (stack.isEmpty() || !blocked(p)) {
            return false;
        }
        if (thrownFromHand && p.getInventory().getSelectedItem().isEmpty()) {
            p.getInventory().setItem(p.getInventory().getSelectedSlot(), stack);
            return true;
        }
        return p.getInventory().add(stack);
    }

    /** EntityPickupItemEvent: a blocked player doesn't pick items up. */
    public static boolean refusePickup(ServerPlayer p) {
        return blocked(p);
    }

    /** PlayerRespawnEvent: Dueley's respawn location, or the vanilla one. */
    public static TeleportTransition respawn(ServerPlayer p, TeleportTransition vanilla) {
        GameManager game = game();
        if (game == null) {
            return vanilla;
        }
        Loc loc = game.respawnLocation(p, usedBedOrAnchor(p, vanilla), vanilla.newLevel());
        if (loc == null) {
            return vanilla;
        }
        return new TeleportTransition(loc.level(), loc.pos(), Vec3.ZERO, loc.yaw(), loc.pitch(), TeleportTransition.DO_NOTHING);
    }

    /** PlayerRespawnEvent#isBedSpawn() || isAnchorSpawn(). */
    private static boolean usedBedOrAnchor(ServerPlayer p, TeleportTransition vanilla) {
        ServerPlayer.RespawnConfig config = p.getRespawnConfig();
        if (config == null || vanilla.missingRespawnBlock()) {
            return false;
        }
        ServerLevel level = p.level().getServer().getLevel(config.respawnData().dimension());
        if (level == null || level != vanilla.newLevel()) {
            return false;
        }
        BlockState state = level.getBlockState(config.respawnData().pos());
        return state.getBlock() instanceof BedBlock || state.is(Blocks.RESPAWN_ANCHOR);
    }

    /**
     * Nether portals: every player has their own nether. Returns null to let vanilla handle the portal, or a
     * {@link PortalTarget} (whose level may be null: the portal does nothing).
     */
    public static PortalTarget netherPortal(ServerLevel from, Entity entity) {
        GameManager game = game();
        if (game == null || !game.isManagedWorld(from)) {
            return null;
        }
        return new PortalTarget(game.netherPortalTarget(
                new Loc(from, entity.getX(), entity.getY(), entity.getZ(), entity.getYRot(), entity.getXRot())));
    }

    public record PortalTarget(Loc target) {
        public BlockPos approximateExit() {
            return target.level().getWorldBorder().clampToBounds(target.x(), target.y(), target.z());
        }

        public boolean toNether() {
            return WorldService.isNether(target.level());
        }
    }

    /** Can a nether portal be lit here? Like Paper: any world with the overworld or nether dimension type. */
    public static boolean portalDimension(ServerLevel level) {
        return level.dimensionTypeRegistration().is(BuiltinDimensionTypes.OVERWORLD) || WorldService.isNether(level);
    }

    /** End portals are closed in Dueley's worlds. Returns true when the portal must do nothing. */
    public static boolean endPortalClosed(ServerLevel from, Entity entity) {
        GameManager game = game();
        if (game == null || !game.isManagedWorld(from)) {
            return false;
        }
        if (entity instanceof ServerPlayer p) {
            Msg.send(p, "<red>The End is closed during Dueley — focus on your gear!");
        }
        return true;
    }

    /** Lobby and arena never have weather (Paper keeps weather per world; since 26.1 vanilla's is server-wide). */
    public static boolean noWeather(ServerLevel level) {
        GameManager game = game();
        return game != null && game.worlds().isStaticWorld(level);
    }
}
